package de.hamstr.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scheduler.ensureScheduled(this)
        setContent { HamstrTheme { HamstrApp() } }
    }
}

@Composable
fun HamstrTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFFF4B860), secondary = Color(0xFFD9A066), tertiary = Color(0xFFF2D2A9))
    } else {
        lightColorScheme(primary = Color(0xFF8B5A2B), secondary = Color(0xFFB07A3E), tertiary = Color(0xFFF4B860))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HamstrApp() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = settingsOpen) { settingsOpen = false }
    // Zurück auf „Produkte“ führt erst zu „Ergebnisse“ – wie in anderen Apps mit Tableiste
    androidx.activity.compose.BackHandler(enabled = !settingsOpen && tab != 0) { tab = 0 }
    var version by remember { mutableIntStateOf(0) }

    val workFlow = remember { WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(Scheduler.NOW) }
    val work by workFlow.collectAsState(initial = emptyList())
    val running = work.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    LaunchedEffect(running) { if (!running) version++ }
    val hitCount = remember(version) { store.hits().size }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val title = when {
        settingsOpen -> "Einstellungen"
        tab == 1 -> "Produkte"
        else -> "Ergebnisse"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (settingsOpen) {
                        IconButton(onClick = { settingsOpen = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    }
                },
                actions = {
                    if (!settingsOpen && tab == 0) {
                        if (running) {
                            CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.5.dp)
                        } else {
                            IconButton(onClick = { Scheduler.runNow(ctx) }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Jetzt prüfen")
                            }
                        }
                    }
                    if (!settingsOpen) {
                        IconButton(onClick = { settingsOpen = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!settingsOpen) NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = {
                        BadgedBox(badge = { if (hitCount > 0) Badge { Text(if (hitCount > 99) "99+" else "$hitCount") } }) {
                            Icon(if (tab == 0) Icons.Filled.ShoppingCart else Icons.Outlined.ShoppingCart, contentDescription = null)
                        }
                    },
                    label = { Text("Ergebnisse") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("Produkte") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                settingsOpen -> SettingsScreen(store)
                tab == 1 -> ProductsScreen(store)
                else -> HitsScreen(
                    store, version, running,
                    onCheck = { Scheduler.runNow(ctx) },
                    onOpenProducts = { tab = 1 },
                    onOpenSettings = { settingsOpen = true },
                )
            }
        }
    }

    UpdateOnStart()
}

// ------------------------------------------------------------------ Ergebnisse

/** Kopfzeile eines Marktes. Antippen öffnet alle aktuellen Angebote des Marktes bei Marktguru. */
@Composable
private fun StoreHeader(store: String, count: Int, url: String, favorite: Boolean, onToggleFavorite: () -> Unit) {
    val ctx = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .then(if (url.isNotBlank()) Modifier.clickable {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } else Modifier),
    ) {
        Row(Modifier.padding(start = 2.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (favorite) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = if (favorite) "Kein Lieblingsmarkt mehr" else "Als Lieblingsmarkt markieren",
                    tint = if (favorite) Color(0xFFF2A900) else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(store, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text(if (count == 1) "1 Angebot" else "$count Angebote", style = MaterialTheme.typography.bodySmall)
            }
            if (url.isNotBlank()) {
                Text("Alle", style = MaterialTheme.typography.labelLarge)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Alle Angebote des Marktes")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HitsScreen(
    store: Store,
    version: Int,
    running: Boolean,
    onCheck: () -> Unit,
    onOpenProducts: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val hits = remember(version) { store.hits() }
    val log = remember(version) { store.log }
    val status = remember(version) { store.lastStatus }
    val logEnabled = remember { store.showLog }
    var favorites by remember { mutableStateOf(store.favoriteChains) }
    val lastRun = remember(version) { store.lastRun }
    var showLog by rememberSaveable { mutableStateOf(false) }
    // Vergleichspreise: Treffer → Produkt → Barcode → Open-Prices-Preis
    val refByProduct = remember(version) {
        val refs = store.refPrices()
        store.products().associate { p -> p.id to refs[p.ean]?.ref }
    }

    PullToRefreshBox(isRefreshing = running, onRefresh = onCheck, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    when {
                        running -> "Suche nach Angeboten…"
                        lastRun == 0L -> "Noch keine Prüfung gelaufen. Zum Prüfen nach unten ziehen."
                        else -> "Zuletzt geprüft: " + SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(lastRun)) +
                            " · zum Aktualisieren nach unten ziehen"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Verständliche Meldung statt Technik
            when {
                status == "no_plz" -> item {
                    EmptyState(Icons.Filled.LocationOn, "Wo kaufst du ein?",
                        "Trag deine PLZ ein, damit Hamstr Angebote in deiner Region findet.",
                        "Zu den Einstellungen", onOpenSettings)
                }
                status == "no_products" -> item {
                    EmptyState(Icons.AutoMirrored.Filled.List, "Noch keine Produkte",
                        "Leg fest, was Hamstr für dich beobachten soll – per Suche oder Barcode.",
                        "Produkt hinzufügen", onOpenProducts)
                }
                status == "failed" && hits.isEmpty() -> item {
                    EmptyState(Icons.Filled.Refresh, "Gerade kein Abruf möglich",
                        "Die Angebote konnten nicht geladen werden. Bitte später noch einmal prüfen.",
                        if (running) null else "Erneut versuchen", onCheck)
                }
                hits.isEmpty() && lastRun == 0L -> item {
                    EmptyState(Icons.Filled.Search, "Noch nichts geprüft",
                        "Hamstr prüft automatisch zu deinen Prüfzeiten. Du kannst auch sofort nachsehen.",
                        if (running) null else "Jetzt prüfen", onCheck)
                }
                hits.isEmpty() -> item {
                    EmptyState(Icons.Outlined.ShoppingCart, "Keine Angebote",
                        "Zu deinen Produkten gibt es gerade keine Angebote. Hamstr sagt dir Bescheid, sobald sich das ändert.")
                }
            }
            if (status == "partial" && hits.isNotEmpty()) {
                item { InfoBanner("Einige Produkte konnten nicht geprüft werden – die Liste ist evtl. unvollständig.") }
            }
            // Nach Markt gruppiert: Lieblingsmärkte zuerst, sonst alphabetisch
            val byStore = hits.groupBy { it.store.ifBlank { "Sonstige" } }.toList().sortedWith(
                compareBy<Pair<String, List<SavedHit>>>({ Geo.chainKey(it.first) !in favorites }, { it.first.lowercase() })
            )
            byStore.forEach { (storeName, storeHits) ->
                item(key = "store|$storeName") {
                    val chain = Geo.chainKey(storeName)
                    StoreHeader(storeName, storeHits.size, storeHits.first().storeUrl, chain in favorites) {
                        favorites = store.toggleFavorite(chain)
                    }
                }
                items(storeHits) { h ->
                    val open = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(h.url))) }
                    if (h.price != null || h.validFrom.isNotBlank() || h.description.isNotBlank()) {
                        OfferCard(
                            image = h.image, title = h.title, description = h.description,
                            price = h.price, oldPrice = h.oldPrice, validFrom = h.validFrom, validTo = h.validTo,
                            hint = h.notes, ref = refByProduct[h.productId], onClick = { open() },
                        )
                    } else {
                        // Ältere gespeicherte Treffer ohne Einzelfelder
                        Card(Modifier.fillMaxWidth().clickable { open() }) {
                            Row(Modifier.padding(12.dp)) {
                                ProductImage(h.image, 72.dp)
                                Spacer(Modifier.size(12.dp))
                                Column {
                                    Text(h.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(4.dp))
                                    Text(h.body, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
            if (logEnabled && log.isNotBlank()) {
                item {
                    TextButton(onClick = { showLog = !showLog }) {
                        Text(if (showLog) "Prüfprotokoll ausblenden" else "Prüfprotokoll anzeigen")
                    }
                    if (showLog) Text(log, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}
