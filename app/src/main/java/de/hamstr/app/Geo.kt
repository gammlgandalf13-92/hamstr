package de.hamstr.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Ein Markt aus OpenStreetMap. */
data class NearbyStore(
    val name: String,
    val chain: String,   // Kurzname für den Märkte-Filter, z. B. "rewe", "aldi"
    val lat: Double,
    val lon: Double,
    val distanceKm: Double,
    val address: String,
)

/** Standort, PLZ ↔ Koordinaten und Supermärkte in der Nähe. Netzwerk-Funktionen blockieren → im Hintergrund aufrufen. */
object Geo {
    // Overpass bittet um einen eindeutigen User-Agent mit Kontakt
    private const val USER_AGENT = "Hamstr/1.0 (private Android-App; +https://github.com/gammlgandalf13-92/hamstr)"

    /**
     * Öffentliche Overpass-Server (wiki.openstreetmap.org/wiki/Overpass_API). Der Hauptserver ist oft überlastet
     * (HTTP 429/500/504) – dann nacheinander die anderen, ohne sie mit Wiederholungen zu fluten.
     */
    internal val OVERPASS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://ovp.dresel.systems/api/interpreter",
    )

    /** Gemeldet, wenn kein Overpass-Server antwortet. */
    class BusyException : java.io.IOException(
        "Der Kartendienst (OpenStreetMap) ist gerade überlastet. Bitte in ein paar Minuten noch einmal versuchen."
    )

    // ---------------------------------------------------------------- Standort
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(context: Context): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (Build.VERSION.SDK_INT >= 30) {
            for (p in providers.filter { it != LocationManager.PASSIVE_PROVIDER }) {
                val loc = withTimeoutOrNull(15_000) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        lm.getCurrentLocation(p, null, context.mainExecutor) { l -> if (cont.isActive) cont.resume(l) }
                    }
                }
                if (loc != null) return loc
            }
        }
        return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    // ---------------------------------------------------------------- PLZ ↔ Koordinaten
    @Suppress("DEPRECATION")
    fun postcodeFor(context: Context, lat: Double, lon: Double): String? {
        if (Geocoder.isPresent()) {
            runCatching {
                Geocoder(context, Locale.GERMANY).getFromLocation(lat, lon, 1)
                    ?.firstOrNull()?.postalCode?.takeIf { it.matches(Regex("\\d{5}")) }
            }.getOrNull()?.let { return it }
        }
        return runCatching {
            val json = JSONObject(get(
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&addressdetails=1&zoom=18&lat=$lat&lon=$lon"
            ))
            json.optJSONObject("address")?.optString("postcode")?.takeIf { it.matches(Regex("\\d{5}")) }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    fun coordsFor(context: Context, plz: String): Pair<Double, Double>? {
        if (Geocoder.isPresent()) {
            runCatching {
                Geocoder(context, Locale.GERMANY).getFromLocationName("$plz, Deutschland", 1)
                    ?.firstOrNull()?.let { it.latitude to it.longitude }
            }.getOrNull()?.let { return it }
        }
        return runCatching {
            val arr = JSONArray(get(
                "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&country=de&postalcode=" +
                    URLEncoder.encode(plz, "UTF-8")
            ))
            arr.optJSONObject(0)?.let { it.getString("lat").toDouble() to it.getString("lon").toDouble() }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- Supermärkte (OpenStreetMap / Overpass)
    fun nearbyStores(lat: Double, lon: Double, radiusKm: Int): List<NearbyStore> {
        val r = radiusKm * 1000
        val query = "[out:json][timeout:25];" +
            "nwr[\"shop\"~\"^(supermarket|discount)$\"](around:$r,$lat,$lon);" +
            "out center tags 400;"
        val form = "data=" + URLEncoder.encode(query, "UTF-8")
        var body: String? = null
        for (server in OVERPASS) {
            body = runCatching { post(server, form) }.getOrNull()
                ?.takeIf { it.trimStart().startsWith("{") } // manche Server liefern bei Überlast eine HTML-Seite
            if (body != null) break
        }
        if (body == null) throw BusyException()
        val elements = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val out = mutableListOf<NearbyStore>()
        for (i in 0 until elements.length()) {
            val e = elements.optJSONObject(i) ?: continue
            val tags = e.optJSONObject("tags") ?: continue
            val pLat = if (e.has("lat")) e.optDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: continue
            val pLon = if (e.has("lon")) e.optDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: continue
            val brand = tags.optString("brand").ifBlank { tags.optString("name") }
            val name = tags.optString("name").ifBlank { brand }
            if (name.isBlank()) continue
            val address = listOf(
                listOf(tags.optString("addr:street"), tags.optString("addr:housenumber")).filter { it.isNotBlank() }.joinToString(" "),
                tags.optString("addr:city"),
            ).filter { it.isNotBlank() }.joinToString(", ")
            out += NearbyStore(name, chainKey(brand), pLat, pLon, distanceKm(lat, lon, pLat, pLon), address)
        }
        return out.sortedBy { it.distanceKm }
    }

    /** Kettenname → Kurzname für den Märkte-Filter (passt zu Marktguru: "aldi" = Süd + Nord, "edeka" = alle EDEKA). */
    fun chainKey(brand: String): String {
        val n = Matcher.norm(brand)
        return when {
            n.startsWith("aldi") -> "aldi"
            n.startsWith("edeka") || n.startsWith("e center") || n.startsWith("e aktiv") || n.startsWith("e neukauf") -> "edeka"
            n.startsWith("rewe") -> "rewe"
            n.startsWith("netto") -> "netto"
            n.startsWith("penny") -> "penny"
            n.startsWith("lidl") -> "lidl"
            n.startsWith("kaufland") -> "kaufland"
            else -> n.split(" ").firstOrNull().orEmpty()
        }
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // ---------------------------------------------------------------- HTTP
    private fun get(url: String): String = request(url, null)
    private fun post(url: String, form: String): String = request(url, form)

    private fun request(url: String, form: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 40_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Accept-Language", "de")
        try {
            if (form != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if (code != 200) throw java.io.IOException("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
