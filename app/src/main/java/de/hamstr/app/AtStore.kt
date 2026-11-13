package de.hamstr.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import java.time.LocalDate

/**
 * „Am Markt erinnern“ (Test): Android meldet, wenn man sich ein paar Minuten in einer Filiale
 * eines eingeschalteten Marktes aufhält. Dann zeigt Hamstr, was dort auf der Einkaufsliste steht
 * bzw. im Angebot ist. Den Standort prüft Android selbst (Geofencing) – Hamstr fragt ihn nicht laufend ab.
 */
object AtStore {
    const val MAX_FENCES = 90                  // Android erlaubt 100 pro App
    private const val RADIUS_M = 120f
    private const val DWELL_MS = 2 * 60 * 1000 // 2 Minuten im Markt → nicht beim Vorbeifahren
    const val QUIET_MS = 3L * 60 * 60 * 1000   // je Kette höchstens alle 3 Stunden

    fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine && background
    }

    /** Welche Filialen werden überwacht? Nur eingeschaltete Ketten, die nächsten zuerst. */
    fun pick(stores: List<NearbyStore>, enabled: Collection<String>): List<NearbyStore> =
        stores.filter { it.chain.isNotBlank() && it.chain in enabled }.sortedBy { it.distanceKm }.take(MAX_FENCES)

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
    )

    /** Überwachte Filialen an die Einstellungen anpassen (App-Start, Neustart, neue Umkreissuche). */
    @SuppressLint("MissingPermission")
    fun sync(context: Context) {
        val st = Store(context)
        val client = runCatching { LocationServices.getGeofencingClient(context) }.getOrNull() ?: return
        val pi = pendingIntent(context)
        if (!st.atStoreEnabled || !hasPermission(context)) {
            runCatching { client.removeGeofences(pi) }
            return
        }
        val chosen = pick(st.nearbyStores(), st.stores)
        runCatching { client.removeGeofences(pi) }
        if (chosen.isEmpty()) {
            st.atStoreStatus = "Keine Filialen bekannt – bitte „Supermärkte im Umkreis finden“ tippen."
            return
        }
        val fences = chosen.mapIndexed { i, s ->
            Geofence.Builder()
                .setRequestId("${s.chain}|$i")
                .setCircularRegion(s.lat, s.lon, RADIUS_M)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL)
                .setLoiteringDelay(DWELL_MS)
                .build()
        }
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofences(fences)
            .build()
        try {
            client.addGeofences(request, pi)
                .addOnSuccessListener {
                    val chains = chosen.map { Markets.label(it.chain, st.chains()).substringBefore(" (") }.distinct()
                    st.atStoreStatus = "Aktiv für ${chosen.size} Filialen: ${chains.joinToString(", ")}"
                }
                .addOnFailureListener { e ->
                    st.atStoreStatus = "Konnte nicht eingerichtet werden – ist der Standort eingeschaltet? (${e.message ?: "Fehler"})"
                }
        } catch (e: Exception) {
            st.atStoreStatus = "Konnte nicht eingerichtet werden: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Text der Benachrichtigung – null, wenn es dort nichts für dich gibt. */
    fun message(
        chain: String,
        label: String,
        shopping: List<ShoppingItem>,
        hits: List<SavedHit>,
        today: LocalDate = LocalDate.now(),
    ): Pair<String, String>? {
        val onList = shopping.filter { !it.done && !it.expired(today) && Geo.chainKey(it.store) == chain }
        val listIds = shopping.map { it.id }.toSet()
        val offers = hits.filter {
            Geo.chainKey(it.store) == chain && 0 in Matcher.weeksOf(it.validFrom, it.validTo, today) &&
                ShoppingItem.fromHit(it).id !in listIds
        }
        if (onList.isEmpty() && offers.isEmpty()) return null
        fun price(p: Double?) = p?.let { " " + Matcher.euro(it) }.orEmpty()
        val lines = mutableListOf<String>()
        if (onList.isNotEmpty()) {
            lines += "Auf deiner Liste: " + onList.take(5).joinToString(", ") { it.title + price(it.price) } +
                (if (onList.size > 5) " und ${onList.size - 5} weitere" else "")
        }
        if (offers.isNotEmpty()) {
            lines += (if (onList.isEmpty()) "Im Angebot: " else "Außerdem im Angebot: ") +
                offers.take(5).joinToString(", ") { it.title + price(it.price) } +
                (if (offers.size > 5) " und ${offers.size - 5} weitere" else "")
        }
        return "Du bist bei $label" to lines.joinToString("\n")
    }

    /** Ankunft in einer Filiale dieser Kette. */
    fun onArrive(context: Context, chain: String, now: Long = System.currentTimeMillis()) {
        val st = Store(context)
        if (!st.atStoreEnabled) return
        val last = st.atStoreLast()
        if (now - (last[chain] ?: 0L) < QUIET_MS) return
        val label = Markets.label(chain, st.chains()).substringBefore(" (")
        val (title, body) = message(chain, label, st.shoppingList(), st.hits()) ?: return
        if (Notifier.atStore(context, chain, title, body)) st.saveAtStoreLast(last + (chain to now))
    }
}

/** Android meldet: Aufenthalt in einer überwachten Filiale. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val t = event.geofenceTransition
        if (t != Geofence.GEOFENCE_TRANSITION_DWELL && t != Geofence.GEOFENCE_TRANSITION_ENTER) return
        event.triggeringGeofences.orEmpty().map { it.requestId.substringBefore("|") }.distinct()
            .forEach { runCatching { AtStore.onArrive(context, it) } }
    }
}

/** Nach Neustart oder Update sind die überwachten Filialen weg → neu einrichten. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { AtStore.sync(context) }
        }
    }
}
