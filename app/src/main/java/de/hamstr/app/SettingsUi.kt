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
import androidx.compose.material3.Button
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
    var hour by remember { mutableStateOf(store.hour.toString()) }
    var radius by remember { mutableFloatStateOf(store.radiusKm.toFloat()) }
    var center by remember { mutableStateOf(store.center?.let { it.first to it.second }) }
    var centerIsLocation by remember { mutableStateOf(store.centerIsLocation) }
    var nearby by remember { mutableStateOf<List<NearbyStore>?>(null) }
    var chainsVersion by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var resetDone by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(store.showLog) }

    val plzOk = Regex("\\d{5}").matches(plz.trim())
    val hourValue = hour.trim().toIntOrNull()
    val hourOk = hourValue != null && hourValue in 0..23

    fun saveAll() {
        store.plz = plz.trim()
        store.hour = hourValue ?: 7
        store.radiusKm = radius.roundToInt()
        center?.let { store.center = Triple(it.first, it.second, plz.trim()) }
        Scheduler.schedule(ctx, store.hour)
        saved = true
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
                    plz = code
                    saved = false
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
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------------- Wo
        Text("Wo kaufst du ein?", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = plz,
                onValueChange = { plz = it.filter(Char::isDigit).take(5); saved = false; nearby = null },
                label = { Text("Postleitzahl") },
                isError = plz.isNotEmpty() && !plzOk,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                enabled = busy == null) { Text("📍 Standort") }
        }

        Text("Umkreis: ${radius.roundToInt()} km", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = radius,
            onValueChange = { radius = it; saved = false },
            onValueChangeFinished = { nearby = null },
            valueRange = 1f..25f,
            steps = 23,
        )

        center?.let { (lat, lon) ->
            val label = if (centerIsLocation) "Dein Standort" else "Mitte von PLZ ${plz.trim()}"
            StoreMap(lat, lon, radius.roundToInt(), nearby.orEmpty(), label)
        }

        Button(onClick = { findStores() }, enabled = plzOk && busy == null) { Text("Supermärkte im Umkreis finden") }
        busy?.let { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(b, style = MaterialTheme.typography.bodySmall)
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

        MarketList(store, chainsVersion)

        // ---------------- Wann
        HorizontalDivider()
        OutlinedTextField(
            value = hour, onValueChange = { hour = it; saved = false }, label = { Text("Täglicher Check um (Uhr)") },
            isError = !hourOk, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = plzOk && hourOk, onClick = { saveAll() }) { Text("Speichern") }
        if (saved) Text("Gespeichert. Nächster Check täglich gegen ${store.hour}:15 Uhr.")

        // ---------------- Updates
        HorizontalDivider()
        UpdateSection(store)

        // ---------------- Sonstiges
        HorizontalDivider()
        Text("Zuverlässigkeit", fontWeight = FontWeight.Bold)
        Text(
            "Manche Handys (z. B. Samsung, Xiaomi) bremsen Apps im Hintergrund. " +
                "Stell Hamstr dort beim Akku auf „Nicht eingeschränkt“.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { openAppSettings(ctx) }) { Text("App-Einstellungen öffnen") }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Prüfprotokoll anzeigen", fontWeight = FontWeight.Bold)
                Text("Technische Details zur letzten Prüfung auf der Treffer-Seite – nur für die Fehlersuche.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.size(8.dp))
            androidx.compose.material3.Switch(checked = showLog, onCheckedChange = { showLog = it; store.showLog = it })
        }

        Text("Meldungen", fontWeight = FontWeight.Bold)
        Text("Jedes Angebot wird nur einmal gemeldet. Zum Testen kannst du das zurücksetzen.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { store.resetSeen(); resetDone = true }) { Text("Gemeldete Angebote vergessen") }
        if (resetDone) Text("Zurückgesetzt.")

        Spacer(Modifier.height(4.dp))
        Text(
            "Angebote: Marktguru · Karte & Märkte: © OpenStreetMap-Mitwirkende · Version ${appVersion(ctx)}",
            style = MaterialTheme.typography.bodySmall,
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
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    fun check() {
        busy = true
        status = null
        scope.launch {
            try {
                latest = withContext(Dispatchers.IO) { Updater.latest() }
                checked = true
                if (latest == null) status = "Noch keine Version mit APK veröffentlicht."
            } catch (e: Exception) {
                status = e.message ?: "Prüfung fehlgeschlagen."
            }
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

    Text("Updates", fontWeight = FontWeight.Bold)
    Text("Installiert: Version 1.0.$current", style = MaterialTheme.typography.bodySmall)

    val info = latest
    when {
        info != null && info.version > current -> {
            Text("Neu: ${info.name}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            Button(onClick = { installLatest(info) }, enabled = !busy) { Text("Herunterladen und installieren") }
        }
        checked && info != null -> Text("Du hast die neueste Version.", style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(onClick = { check() }, enabled = !busy) { Text("Nach Updates suchen") }
    if (busy && progress > 0f) {
        androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    } else if (busy) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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

private fun openAppSettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
    ctx.startActivity(intent)
}

private fun appVersion(ctx: Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
} catch (e: Exception) {
    "?"
}
