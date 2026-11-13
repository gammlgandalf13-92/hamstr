package de.hamstr.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(store: Store) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var plz by remember { mutableStateOf(store.plz) }
    var radius by remember { mutableFloatStateOf(store.radiusKm.toFloat()) }
    var center by remember { mutableStateOf(store.center?.let { it.first to it.second }) }
    var centerIsLocation by remember { mutableStateOf(store.centerIsLocation) }
    var nearby by remember { mutableStateOf<List<NearbyStore>?>(null) }
    var chainsVersion by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var resetDone by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(store.showLog) }

    val plzOk = Regex("\\d{5}").matches(plz.trim())

    /** Wie in den Android-Einstellungen: Änderungen gelten sofort, ohne Speichern-Knopf. */
    fun changePlz(value: String) {
        plz = value.filter(Char::isDigit).take(5)
        nearby = null
        if (Regex("\\d{5}").matches(plz)) store.plz = plz
    }

    fun locate() {
        busy = "Standort wird ermittelt…"
        message = null
        scope.launch {
            val loc = Geo.currentLocation(ctx)
            if (loc == null) {
                message = "Kein Standort verfügbar. Ist der Standortdienst eingeschaltet?"
            } else {
                center = loc.latitude to loc.longitude
                centerIsLocation = true
                store.centerIsLocation = true
                val code = withContext(Dispatchers.IO) { Geo.postcodeFor(ctx, loc.latitude, loc.longitude) }
                store.center = Triple(loc.latitude, loc.longitude, code.orEmpty())
                if (code != null) {
                    changePlz(code)
                    message = "PLZ $code übernommen."
                } else {
                    message = "Standort gefunden, aber keine PLZ ermittelbar – bitte von Hand eintragen."
                }
            }
            busy = null
        }
    }

    fun findStores() {
        busy = "Suche Supermärkte…"
        message = null
        scope.launch {
            try {
                // Mittelpunkt: Standort, sonst über die PLZ bestimmen
                val known = store.center
                val reuse = known?.takeIf { it.third == plz.trim() }?.let { it.first to it.second }
                val c = reuse ?: withContext(Dispatchers.IO) { Geo.coordsFor(ctx, plz.trim()) }
                if (reuse == null && c != null) {
                    centerIsLocation = false
                    store.centerIsLocation = false
                }
                if (c == null) {
                    message = "Zur PLZ $plz wurde kein Ort gefunden."
                } else {
                    center = c
                    store.center = Triple(c.first, c.second, plz.trim())
                    val found = withContext(Dispatchers.IO) { Geo.nearbyStores(c.first, c.second, radius.roundToInt()) }
                    nearby = found
                    store.saveChains(Markets.applyNearby(store.chains(), found))
                    store.saveNearbyStores(found)
                    AtStore.sync(ctx)
                    chainsVersion++
                    if (found.isEmpty()) message = "Keine Supermärkte im Umkreis gefunden – Radius vergrößern?"
                }
            } catch (e: Exception) {
                message = "Suche fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}"
            }
            busy = null
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locate() else message = "Ohne Standortfreigabe bitte die PLZ von Hand eintragen."
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---------------- Standort
        SettingsHeader("Standort")
        OutlinedTextField(
            value = plz,
            onValueChange = { changePlz(it) },
            label = { Text("Postleitzahl") },
            isError = plz.isNotEmpty() && !plzOk,
            supportingText = { if (plz.isNotEmpty() && !plzOk) Text("Bitte 5 Ziffern eingeben") },
            singleLine = true,
            trailingIcon = {
                if (busy == "Standort wird ermittelt…") {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(
                        onClick = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                        enabled = busy == null,
                    ) { Icon(Icons.Filled.LocationOn, contentDescription = "Meinen Standort verwenden") }
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Umkreis: ${radius.roundToInt()} km", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = radius,
            onValueChange = { radius = it },
            onValueChangeFinished = { nearby = null; store.radiusKm = radius.roundToInt() },
            valueRange = 1f..25f,
            steps = 23,
        )

        center?.let { (lat, lon) ->
            val label = if (centerIsLocation) "Dein Standort" else "Mitte von PLZ ${plz.trim()}"
            StoreMap(lat, lon, radius.roundToInt(), nearby.orEmpty(), label)
        }

        OutlinedButton(onClick = { findStores() }, enabled = plzOk && busy == null, modifier = Modifier.fillMaxWidth()) {
            Text("Supermärkte im Umkreis finden")
        }
        busy?.let { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(b, style = MaterialTheme.typography.bodySmall)
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

        // ---------------- Märkte
        HorizontalDivider(Modifier.padding(top = 8.dp))
        SettingsHeader("Meine Märkte")
        MarketList(store, chainsVersion)

        // ---------------- Wann
        HorizontalDivider(Modifier.padding(top = 8.dp))
        SettingsHeader("Prüfzeiten")
        ScheduleSection(store)

        // ---------------- Am Markt erinnern
        HorizontalDivider(Modifier.padding(top = 8.dp))
        SettingsHeader("Am Markt erinnern (Test)")
        AtStoreSection(store)

        // ---------------- Benachrichtigungen
        HorizontalDivider(Modifier.padding(top = 8.dp))
        SettingsHeader("Benachrichtigungen")
        SettingsRow(
            "Benachrichtigungen verwalten",
            "Töne und Kanäle (Angebote, App-Updates) in den Android-Einstellungen",
            onClick = { openNotificationSettings(ctx) },
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }
        SettingsRow(
            "Gemeldete Angebote vergessen",
            if (resetDone) "Zurückgesetzt – bei der nächsten Prüfung wird alles neu gemeldet."
            else "Jedes Angebot wird nur einmal gemeldet. Zum Testen zurücksetzen.",
            onClick = { store.resetSeen(); resetDone = true },
        )

        // ---------------- Updates
        HorizontalDivider(Modifier.padding(top = 8.dp))
        SettingsHeader("Updates")
        UpdateSection(store)

        // ---------------- Allgemein
        HorizontalDivider(Modifier.padding(top = 8.dp))
        SettingsHeader("Allgemein")
        SettingsRow(
            "Akku-Einstellungen",
            "Manche Handys (z. B. Samsung, Xiaomi) bremsen Apps im Hintergrund. Stell Hamstr beim Akku auf „Nicht eingeschränkt“.",
            onClick = { openAppSettings(ctx) },
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }
        SettingsRow(
            "Prüfprotokoll anzeigen",
            "Technische Details zur letzten Prüfung unter „Ergebnisse“ – nur für die Fehlersuche.",
            onClick = { showLog = !showLog; store.showLog = showLog },
        ) { Switch(checked = showLog, onCheckedChange = { showLog = it; store.showLog = it }) }

        // ---------------- Über
        HorizontalDivider(Modifier.padding(top = 8.dp))
        SettingsHeader("Über Hamstr")
        Text(
            "Version ${appVersion(ctx)}\n" +
                "Angebote: Marktguru · Produktdaten: Open Food Facts · Vergleichspreise: Open Prices (ODbL) · " +
                "Karte & Märkte: © OpenStreetMap-Mitwirkende",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Markt-Erinnerung ein-/ausschalten. Braucht genauen Standort mit „Immer erlauben“ –
 * Android fragt das in zwei Schritten ab (erst Standort, dann im Hintergrund).
 */
@Composable
private fun AtStoreSection(store: Store) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(store.atStoreEnabled && AtStore.hasPermission(ctx)) }
    var status by remember { mutableStateOf(store.atStoreStatus) }
    var explain by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun activate() {
        busy = true
        status = "Wird eingerichtet…"
        scope.launch {
            // Filialen kennen wir aus der Umkreissuche; fehlt sie, jetzt nachholen
            if (store.nearbyStores().isEmpty()) {
                val c = store.center
                if (c != null) {
                    val found = withContext(Dispatchers.IO) {
                        runCatching { Geo.nearbyStores(c.first, c.second, store.radiusKm) }.getOrDefault(emptyList())
                    }
                    if (found.isNotEmpty()) {
                        store.saveNearbyStores(found)
                        store.saveChains(Markets.applyNearby(store.chains(), found))
                    }
                }
            }
            store.atStoreEnabled = true
            enabled = true
            withContext(Dispatchers.IO) { AtStore.sync(ctx) }
            kotlinx.coroutines.delay(1500) // Ergebnis von Android abwarten
            status = store.atStoreStatus
            busy = false
        }
    }

    val backgroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (AtStore.hasPermission(ctx)) activate()
        else status = "Ohne Standort „Immer erlauben“ geht es nicht. Du kannst das unter „Akku-Einstellungen“ → Berechtigungen → Standort nachholen."
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        when {
            result[Manifest.permission.ACCESS_FINE_LOCATION] != true ->
                status = "Dafür braucht Hamstr den genauen Standort (nicht nur ungefähr)."
            android.os.Build.VERSION.SDK_INT >= 29 && !AtStore.hasPermission(ctx) ->
                backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else -> activate()
        }
    }

    SettingsRow(
        "Hinweis beim Betreten eines Marktes",
        "Bist du ein paar Minuten in einer Filiale deiner Märkte, zeigt Hamstr, was dort auf deiner Einkaufsliste steht " +
            "und was diese Woche für dich im Angebot ist. Pro Markt höchstens alle 3 Stunden.",
        onClick = if (busy) null else ({
            if (enabled) {
                store.atStoreEnabled = false
                enabled = false
                AtStore.sync(ctx)
                status = ""
            } else explain = true
        }),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        else Switch(checked = enabled, onCheckedChange = null)
    }
    if (enabled && status.isNotBlank()) {
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    } else if (!enabled && status.isNotBlank() && !busy) {
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    if (explain) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text("Standort im Hintergrund") },
            text = {
                Text(
                    "Damit Hamstr merkt, dass du in einem Markt bist, braucht es den genauen Standort mit " +
                        "„Immer erlauben“. Android fragt das gleich in zwei Schritten ab – beim zweiten bitte " +
                        "„Immer zulassen“ wählen.\n\nAndroid prüft selbst, ob du in der Nähe einer Filiale bist; " +
                        "Hamstr fragt den Standort nicht laufend ab und schickt ihn nirgendwohin."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    locationPermission.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                    ))
                }) { Text("Weiter") }
            },
            dismissButton = { TextButton(onClick = { explain = false }) { Text("Abbrechen") } },
        )
    }
}

/** Updates aus den GitHub-Releases: prüfen, herunterladen, installieren. */
@Composable
private fun UpdateSection(store: Store) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = remember { Updater.currentVersion(ctx) }
    var latest by remember { mutableStateOf<UpdateInfo?>(null) }
    var checked by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    fun check() {
        busy = true
        checking = true
        status = null
        scope.launch {
            val started = System.currentTimeMillis()
            try {
                latest = withContext(Dispatchers.IO) { Updater.latest() }
                checked = true
                if (latest == null) status = "Noch keine Version mit APK veröffentlicht."
            } catch (e: Exception) {
                status = e.message ?: "Prüfung fehlgeschlagen."
            }
            // Animation kurz sichtbar lassen, auch wenn GitHub sehr schnell antwortet
            val elapsed = System.currentTimeMillis() - started
            if (elapsed < 800) kotlinx.coroutines.delay(800 - elapsed)
            checking = false
            busy = false
        }
    }

    fun installLatest(info: UpdateInfo) {
        if (!Updater.canInstall(ctx)) {
            status = "Bitte erlaube Hamstr einmalig, Apps zu installieren – danach erneut tippen."
            Updater.openInstallPermission(ctx)
            return
        }
        busy = true
        progress = 0f
        status = "Lade herunter…"
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    Updater.download(ctx, info) { p -> progress = p }
                }
                status = "Installation wird gestartet…"
                Updater.install(ctx, apk)
            } catch (e: Exception) {
                status = e.message ?: "Download fehlgeschlagen."
            }
            busy = false
        }
    }

    val info = latest
    val newer = info != null && info.version > current
    SettingsRow(
        "Nach Updates suchen",
        when {
            checking -> "Suche…"
            newer -> "Neu: ${info!!.name} · installiert: 1.0.$current"
            checked && info != null -> "Du hast die neueste Version (1.0.$current)."
            else -> "Installiert: Version 1.0.$current"
        },
        onClick = if (busy) null else ({ check() }),
    ) {
        if (checking) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
    }
    if (newer) {
        Button(onClick = { installLatest(info!!) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Herunterladen und installieren")
        }
    }
    if (busy && !checking) {
        if (progress > 0f) {
            androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        } else {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
}

/** OpenStreetMap-Karte mit Umkreis und Märkten. */
@Composable
private fun StoreMap(lat: Double, lon: Double, radiusKm: Int, shops: List<NearbyStore>, centerLabel: String) {
    val ctx = LocalContext.current
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = ctx.packageName
            osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
            osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // Karte im scrollbaren Bereich: Wischen gehört der Karte
            setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }
    DisposableEffect(Unit) { onDispose { map.onDetach() } }

    AndroidView(
        factory = { map },
        modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(12.dp)),
        update = { m ->
            val c = GeoPoint(lat, lon)
            m.overlays.clear()
            val circle = Polygon(m)
            circle.setPoints(Polygon.pointsAsCircle(c, radiusKm * 1000.0))
            circle.fillPaint.color = Color.argb(40, 139, 90, 43)
            circle.outlinePaint.color = Color.argb(200, 139, 90, 43)
            circle.outlinePaint.strokeWidth = 4f
            circle.infoWindow = null
            m.overlays.add(circle)
            shops.take(150).forEach { s ->
                m.overlays.add(Marker(m).apply {
                    position = GeoPoint(s.lat, s.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = s.name
                    snippet = s.address
                })
            }
            // Mittelpunkt des Umkreises – zuletzt hinzugefügt, damit er über den Markt-Pins liegt
            m.overlays.add(Marker(m).apply {
                position = c
                icon = ContextCompat.getDrawable(ctx, R.drawable.ic_my_location)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = centerLabel
            })
            // Zoom so, dass der Kreis gut hineinpasst
            m.controller.setZoom((14.5 - ln(radiusKm.toDouble()) / ln(2.0)).coerceIn(8.0, 16.0))
            m.controller.setCenter(c)
            m.invalidate()
        },
    )
}

private fun openNotificationSettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
    runCatching { ctx.startActivity(intent) }.onFailure { openAppSettings(ctx) }
}

private fun openAppSettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
    ctx.startActivity(intent)
}

private fun appVersion(ctx: Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
} catch (e: Exception) {
    "?"
}
