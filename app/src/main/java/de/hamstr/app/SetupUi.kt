package de.hamstr.app

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Wie der Assistent endet. */
enum class SetupFinish { SEARCH, IMPORT, LATER }

/**
 * Einrichtung beim ersten Start in drei Schritten: PLZ → Märkte → erstes Produkt (oder Liste importieren).
 * Alles lässt sich später in den Einstellungen ändern.
 */
@Composable
fun SetupWizard(store: Store, onFinish: (SetupFinish) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var plz by rememberSaveable { mutableStateOf(store.plz) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var marketsVersion by remember { mutableIntStateOf(0) }
    var searchedFor by rememberSaveable { mutableStateOf("") }
    val plzOk = Regex("\\d{5}").matches(plz)

    BackHandler(enabled = step > 0) { step-- }

    fun locate() {
        busy = "Standort wird ermittelt…"
        message = null
        scope.launch {
            val loc = Geo.currentLocation(ctx)
            if (loc == null) {
                message = "Kein Standort verfügbar – bitte die PLZ eintippen."
            } else {
                val code = withContext(Dispatchers.IO) { Geo.postcodeFor(ctx, loc.latitude, loc.longitude) }
                store.center = Triple(loc.latitude, loc.longitude, code.orEmpty())
                store.centerIsLocation = true
                if (code != null) plz = code else message = "Keine PLZ gefunden – bitte eintippen."
            }
            busy = null
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locate() else message = "Kein Problem – tipp die PLZ einfach ein."
    }

    /** Märkte im Umkreis suchen und gefundene Ketten einschalten. Fehler sind nicht schlimm – dann bleiben die Standard-Märkte. */
    fun findMarkets() {
        if (searchedFor == plz) return
        searchedFor = plz
        busy = "Suche Supermärkte in deiner Nähe…"
        message = null
        scope.launch {
            try {
                val known = store.center?.takeIf { it.third == plz }?.let { it.first to it.second }
                val c = known ?: withContext(Dispatchers.IO) { Geo.coordsFor(ctx, plz) }
                if (c != null) {
                    if (known == null) {
                        store.center = Triple(c.first, c.second, plz)
                        store.centerIsLocation = false
                    }
                    val found = withContext(Dispatchers.IO) { Geo.nearbyStores(c.first, c.second, store.radiusKm) }
                    store.saveChains(Markets.applyNearby(store.chains(), found))
                    store.saveNearbyStores(found)
                    store.nearbyQuery = "%.3f,%.3f,%d".format(Locale.ROOT, c.first, c.second, store.radiusKm)
                    val chains = found.map { it.chain }.filter { it.isNotBlank() }.distinct()
                    if (chains.isNotEmpty()) store.stores = Markets.ordered(store.chains(), chains.toSet()).filter { it in chains }
                    message = if (found.isEmpty()) "Im Umkreis von ${store.radiusKm} km nichts gefunden – die gängigen Märkte sind eingeschaltet."
                    else null
                }
            } catch (e: Exception) {
                message = "Die Umkreissuche klappt gerade nicht – die gängigen Märkte sind eingeschaltet. " +
                    "Später geht das unter Einstellungen → „Supermärkte im Umkreis finden“."
            }
            marketsVersion++
            busy = null
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        Column(
            Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Schritt ${step + 1} von 3", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth())

            when (step) {
                // ---------------- 1: Willkommen + PLZ
                0 -> {
                    Text("🐹", fontSize = 56.sp)
                    Text("Willkommen bei Hamstr", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Hamstr schaut für dich in die Prospekte deiner Supermärkte und meldet sich, " +
                            "sobald etwas, das du brauchst, im Angebot ist.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text("Wo kaufst du ein?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = plz,
                        onValueChange = { plz = it.filter(Char::isDigit).take(5) },
                        label = { Text("Postleitzahl") },
                        singleLine = true,
                        trailingIcon = {
                            if (busy != null) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            else IconButton(onClick = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                                Icon(Icons.Filled.LocationOn, contentDescription = "Meinen Standort verwenden")
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Tipp: Mit 📍 übernimmt Hamstr die PLZ von deinem Standort.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    message?.let { Hint(it) }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { store.plz = plz; message = null; step = 1; findMarkets() },
                        enabled = plzOk && busy == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Weiter") }
                    TextButton(onClick = { onFinish(SetupFinish.LATER) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Überspringen – ich richte das später ein")
                    }
                }

                // ---------------- 2: Märkte
                1 -> {
                    Text("Deine Märkte", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Hamstr meldet nur Angebote von eingeschalteten Märkten. Schalte aus, wo du nie einkaufst – " +
                            "und markier mit ★ deine Lieblingsmärkte, die stehen dann immer oben.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    busy?.let { b ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(10.dp))
                            Text(b, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    message?.let { Hint(it) }
                    if (busy == null) MarketList(store, marketsVersion)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { message = null; step = 2 }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                        Text("Weiter")
                    }
                    TextButton(onClick = { step = 0 }, modifier = Modifier.fillMaxWidth()) { Text("Zurück") }
                }

                // ---------------- 3: Produkte
                else -> {
                    Text("Was soll Hamstr beobachten?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Leg Produkte an, die du regelmäßig kaufst – z. B. Kaffee, Nutella oder eine bestimmte Marke. " +
                            "Hamstr prüft dann automatisch und meldet sich bei Angeboten.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    ChoiceCard(Icons.Filled.Search, "Produkt suchen oder Barcode scannen",
                        "Per Name suchen oder die Packung zu Hause scannen.") { onFinish(SetupFinish.SEARCH) }
                    ChoiceCard(Icons.Filled.Add, "Produktliste importieren",
                        "Hat dir jemand seine Hamstr-Liste geschickt? Dann einfach übernehmen.") { onFinish(SetupFinish.IMPORT) }
                    Text(
                        "Hamstr prüft jeden Tag um ${store.schedule.first().time} Uhr – " +
                            "das und alles andere kannst du jederzeit in den Einstellungen (⚙) ändern.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { onFinish(SetupFinish.LATER) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Erst mal umschauen")
                    }
                    TextButton(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Zurück") }
                }
            }
        }
    }
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, text: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
}
