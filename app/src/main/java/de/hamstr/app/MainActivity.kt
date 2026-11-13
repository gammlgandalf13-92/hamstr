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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.key
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
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
    /** Geteilte Produktliste, die mit „Öffnen mit Hamstr“ hereinkam. */
    private val incoming = mutableStateOf<Uri?>(null)
    /** Tab aus einer Benachrichtigung (z. B. „Du bist bei REWE“ → Einkaufsliste). */
    private val openTab = mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scheduler.ensureScheduled(this)
        if (savedInstanceState == null) {
            incoming.value = Transfer.uriFrom(intent)
            openTab.value = intent?.getIntExtra(Notifier.EXTRA_TAB, -1)?.takeIf { it >= 0 }
        }
        setContent {
            HamstrTheme {
                HamstrApp(incoming.value, { incoming.value = null }, openTab.value, { openTab.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Transfer.uriFrom(intent)?.let { incoming.value = it }
        intent.getIntExtra(Notifier.EXTRA_TAB, -1).takeIf { it >= 0 }?.let { openTab.value = it }
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

/** Tabs unten: Ergebnisse, Einkaufsliste, Produkte */
private const val TAB_RESULTS = 0
private const val TAB_SHOPPING = 1
private const val TAB_PRODUCTS = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HamstrApp(
    incoming: Uri? = null,
    onIncomingHandled: () -> Unit = {},
    openTab: Int? = null,
    onOpenTabHandled: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(TAB_RESULTS) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = settingsOpen) { settingsOpen = false }
    // Zurück auf anderen Tabs führt erst zu „Ergebnisse“ – wie in anderen Apps mit Tableiste
    androidx.activity.compose.BackHandler(enabled = !settingsOpen && tab != TAB_RESULTS) { tab = TAB_RESULTS }
    var version by remember { mutableIntStateOf(0) }
    var shoppingVersion by remember { mutableIntStateOf(0) }
    var productsVersion by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var shareMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(openTab) {
        openTab?.let { tab = it; settingsOpen = false; shoppingVersion++; onOpenTabHandled() }
    }

    val workFlow = remember { WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(Scheduler.NOW) }
    val work by workFlow.collectAsState(initial = emptyList())
    val running = work.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    LaunchedEffect(running) { if (!running) version++ }
    val hitCount = remember(version) { store.hits().size }
    val shoppingCount = remember(shoppingVersion) { store.shoppingList().count { !it.done && !it.expired() } }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ---------------- Produktliste importieren
    var importing by remember { mutableStateOf<List<ImportedProduct>?>(null) }
    var importNew by remember { mutableStateOf<List<ImportedProduct>>(emptyList()) }
    var importError by remember { mutableStateOf<String?>(null) }
    fun startImport(uri: Uri) {
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { Transfer.read(ctx, uri) }
                val fresh = ProductTransfer.newOnes(store.products(), found.map { it.product })
                importNew = found.filter { f -> fresh.any { it.id == f.product.id } }
                importing = found
            } catch (e: Exception) {
                importError = e.message ?: "Die Datei konnte nicht gelesen werden."
            }
        }
    }
    LaunchedEffect(incoming) {
        incoming?.let { startImport(it); onIncomingHandled() }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport(uri)
    }

    val title = when {
        settingsOpen -> "Einstellungen"
        tab == TAB_SHOPPING -> "Einkaufsliste"
        tab == TAB_PRODUCTS -> "Produkte"
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
                    if (!settingsOpen && tab == TAB_RESULTS) {
                        if (running) {
                            CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.5.dp)
                        } else {
                            IconButton(onClick = { Scheduler.runNow(ctx) }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Jetzt prüfen")
                            }
                        }
                    }
                    if (!settingsOpen && tab == TAB_SHOPPING && shoppingCount > 0) {
                        Box {
                            IconButton(onClick = { shareMenuOpen = true }) {
                                Icon(Icons.Filled.Share, contentDescription = "Einkaufsliste teilen")
                            }
                            DropdownMenu(expanded = shareMenuOpen, onDismissRequest = { shareMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Mit Märkten und Preisen") },
                                    onClick = {
                                        shareMenuOpen = false
                                        shareText(ctx, ShareText.list(store.shoppingList()), "Einkaufsliste teilen")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Nur Artikel, einer pro Zeile") },
                                    onClick = {
                                        shareMenuOpen = false
                                        shareText(ctx, ShareText.names(store.shoppingList()), "Einkaufsliste teilen")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Artikel kopieren (zum Einfügen in eine andere Liste)") },
                                    onClick = {
                                        shareMenuOpen = false
                                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Einkaufsliste",
                                            ShareText.names(store.shoppingList())))
                                        // Ab Android 13 zeigt das System selbst eine Bestätigung
                                        if (Build.VERSION.SDK_INT < 33) {
                                            android.widget.Toast.makeText(ctx, "Kopiert", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (!settingsOpen) {
                        IconButton(onClick = { settingsOpen = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                        }
                    }
                    if (!settingsOpen && tab == TAB_PRODUCTS) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Weitere Optionen")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Produktliste teilen") },
                                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                    enabled = store.products().isNotEmpty(),
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            val file = withContext(Dispatchers.IO) { Transfer.exportFile(ctx, store.products()) }
                                            ctx.startActivity(Transfer.shareIntent(ctx, file))
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Produktliste importieren") },
                                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        pickFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!settingsOpen) NavigationBar {
                NavigationBarItem(
                    selected = tab == TAB_RESULTS,
                    onClick = { tab = TAB_RESULTS },
                    icon = {
                        BadgedBox(badge = { if (hitCount > 0) Badge { Text(if (hitCount > 99) "99+" else "$hitCount") } }) {
                            Icon(painterResource(R.drawable.ic_offer), contentDescription = null)
                        }
                    },
                    label = { Text("Ergebnisse") },
                )
                NavigationBarItem(
                    selected = tab == TAB_SHOPPING,
                    onClick = { tab = TAB_SHOPPING },
                    icon = {
                        BadgedBox(badge = { if (shoppingCount > 0) Badge { Text("$shoppingCount") } }) {
                            Icon(if (tab == TAB_SHOPPING) Icons.Filled.ShoppingCart else Icons.Outlined.ShoppingCart, contentDescription = null)
                        }
                    },
                    label = { Text("Einkaufsliste") },
                )
                NavigationBarItem(
                    selected = tab == TAB_PRODUCTS,
                    onClick = { tab = TAB_PRODUCTS },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("Produkte") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                settingsOpen -> SettingsScreen(store)
                tab == TAB_PRODUCTS -> key(productsVersion) { ProductsScreen(store) }
                tab == TAB_SHOPPING -> ShoppingScreen(store) { shoppingVersion++ }
                else -> HitsScreen(
                    store, version, running,
                    onCheck = { Scheduler.runNow(ctx) },
                    onOpenProducts = { tab = TAB_PRODUCTS },
                    onOpenSettings = { settingsOpen = true },
                    onShoppingChanged = { shoppingVersion++ },
                )
            }
        }
    }

    importing?.let { found ->
        AlertDialog(
            onDismissRequest = { importing = null },
            title = { Text("Produktliste importieren") },
            text = {
                Text(
                    if (importNew.isEmpty()) "Alle ${found.size} Produkte aus der Datei beobachtest du schon."
                    else "In der Datei: ${found.size} Produkte. Neu für dich: ${importNew.size}" +
                        (if (found.size > importNew.size) " (${found.size - importNew.size} hast du schon)." else ".") +
                        "\n\nPLZ, Märkte und Prüfzeiten bleiben, wie du sie eingestellt hast."
                )
            },
            confirmButton = {
                if (importNew.isEmpty()) {
                    TextButton(onClick = { importing = null }) { Text("OK") }
                } else {
                    TextButton(onClick = {
                        val add = importNew
                        importing = null
                        scope.launch {
                            val products = withContext(Dispatchers.IO) { Transfer.materialize(ctx, add) }
                            store.saveProducts(store.products() + products)
                            productsVersion++
                            settingsOpen = false
                            tab = TAB_PRODUCTS
                        }
                    }) { Text("Hinzufügen") }
                }
            },
            dismissButton = {
                if (importNew.isNotEmpty()) TextButton(onClick = { importing = null }) { Text("Abbrechen") }
            },
        )
    }
    importError?.let { msg ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Import nicht möglich") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("OK") } },
        )
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
    onShoppingChanged: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val hits = remember(version) { store.hits() }
    // Einkaufsliste: welche Angebote stehen schon drauf?
    var listIds by remember { mutableStateOf(store.shoppingList().map { it.id }.toSet()) }
    fun toggleList(h: SavedHit) {
        val item = ShoppingItem.fromHit(h)
        val list = store.shoppingList()
        val next = if (list.any { it.id == item.id }) list.filterNot { it.id == item.id } else list + item
        store.saveShoppingList(next)
        listIds = next.map { it.id }.toSet()
        onShoppingChanged()
    }
    // Preisverlauf je Produkt (nur Einzelprodukte – bei Marken mischen sich verschiedene Artikel)
    val statsByProduct = remember(version) {
        val log = store.priceLog()
        store.products().filter { it.names.isNotEmpty() }.associate { p -> p.id to PriceHistory.stats(log[p.id].orEmpty()) }
    }
    val actions = HitActions(
        listIds = listIds,
        onToggleList = { toggleList(it) },
        onShare = { shareText(ctx, ShareText.offer(it), "Angebot teilen") },
        stats = statsByProduct,
    )
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

    val today = remember(version) { LocalDate.now() }
    val scope = rememberCoroutineScope()
    // Wochen: diese, nächste und – falls vorhanden – „später“
    val weekHits = remember(hits, today) {
        (0..2).map { w -> hits.filter { w in Matcher.weeksOf(it.validFrom, it.validTo, today) } }
    }
    val weeks = if (weekHits[2].isNotEmpty()) listOf(0, 1, 2) else listOf(0, 1)
    // Zustände ohne Angebote (keine PLZ, keine Produkte, Fehler, noch nie geprüft): ohne Wochen-Tabs
    val showTabs = hits.isNotEmpty() && status != "no_plz" && status != "no_products"
    // Start in der ersten Woche, die etwas hat (z. B. sonntags schon die nächste Woche)
    val pager = rememberPagerState(initialPage = weeks.firstOrNull { weekHits[it].isNotEmpty() } ?: 0) { weeks.size }

    Column(Modifier.fillMaxSize()) {
        if (showTabs) {
            TabRow(selectedTabIndex = pager.currentPage.coerceAtMost(weeks.lastIndex)) {
                weeks.forEachIndexed { i, w ->
                    val label = when (w) { 0 -> "Diese Woche"; 1 -> "Nächste Woche"; else -> "Später" }
                    Tab(
                        selected = pager.currentPage == i,
                        onClick = { scope.launch { pager.animateScrollToPage(i) } },
                        text = { Text("$label (${weekHits[w].size})", maxLines = 1) },
                    )
                }
            }
        }
        PullToRefreshBox(isRefreshing = running, onRefresh = onCheck, modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (!showTabs) {
                HitsList(store, emptyList(), null, today, status, lastRun, running, favorites, { favorites = it },
                    refByProduct, logEnabled, log, onCheck, onOpenProducts, onOpenSettings, hits.isEmpty(), actions)
            } else {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                    val w = weeks[page]
                    HitsList(store, weekHits[w], w, today, status, lastRun, running, favorites, { favorites = it },
                        refByProduct, logEnabled && page == 0, log, onCheck, onOpenProducts, onOpenSettings, false, actions)
                }
            }
        }
    }
}

/** Was man mit einem Angebot tun kann – und was Hamstr über frühere Angebote weiß. */
class HitActions(
    val listIds: Set<String>,
    val onToggleList: (SavedHit) -> Unit,
    val onShare: (SavedHit) -> Unit,
    val stats: Map<String, PriceStats?>,
)

/** Liste einer Woche: Status, Märkte mit ihren Angeboten, ggf. Prüfprotokoll. */
@Composable
private fun HitsList(
    store: Store,
    hits: List<SavedHit>,
    week: Int?,
    today: LocalDate,
    status: String,
    lastRun: Long,
    running: Boolean,
    favorites: Set<String>,
    onFavorites: (Set<String>) -> Unit,
    refByProduct: Map<String, RefPrice?>,
    logEnabled: Boolean,
    log: String,
    onCheck: () -> Unit,
    onOpenProducts: () -> Unit,
    onOpenSettings: () -> Unit,
    noHitsAtAll: Boolean,
    actions: HitActions,
) {
    val ctx = LocalContext.current
    var showLog by rememberSaveable { mutableStateOf(false) }
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
                    else -> (week?.let { Matcher.weekRangeText(it, today) + " · " } ?: "") +
                        "zuletzt geprüft " + SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(lastRun))
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
            noHitsAtAll && status == "failed" -> item {
                EmptyState(Icons.Filled.Refresh, "Gerade kein Abruf möglich",
                    "Die Angebote konnten nicht geladen werden. Bitte später noch einmal prüfen.",
                    if (running) null else "Erneut versuchen", onCheck)
            }
            noHitsAtAll && lastRun == 0L -> item {
                EmptyState(Icons.Filled.Search, "Noch nichts geprüft",
                    "Hamstr prüft automatisch zu deinen Prüfzeiten. Du kannst auch sofort nachsehen.",
                    if (running) null else "Jetzt prüfen", onCheck)
            }
            noHitsAtAll -> item {
                EmptyState(Icons.Outlined.ShoppingCart, "Keine Angebote",
                    "Zu deinen Produkten gibt es gerade keine Angebote. Hamstr sagt dir Bescheid, sobald sich das ändert.")
            }
            hits.isEmpty() && week == 0 -> item {
                EmptyState(Icons.Outlined.ShoppingCart, "Diese Woche nichts dabei",
                    "Zu deinen Produkten gibt es diese Woche keine Angebote. Wisch nach links für die nächste Woche.")
            }
            hits.isEmpty() && week == 1 -> item {
                EmptyState(Icons.Outlined.ShoppingCart, "Noch nichts für nächste Woche",
                    "Die Prospekte für nächste Woche erscheinen meist erst gegen Ende dieser Woche. " +
                        "Hamstr schaut bei jeder Prüfung nach und meldet sich.")
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
                    onFavorites(store.toggleFavorite(chain))
                }
            }
            items(storeHits) { h ->
                val open = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(h.url))) }
                if (h.price != null || h.validFrom.isNotBlank() || h.description.isNotBlank()) {
                    OfferCard(
                        image = h.image, title = h.title, description = h.description,
                        price = h.price, oldPrice = h.oldPrice, validFrom = h.validFrom, validTo = h.validTo,
                        hint = h.notes.split(" · ").filterNot { it.startsWith("gilt erst ab") }.joinToString(" · "),
                        ref = refByProduct[h.productId],
                        lowestText = PriceHistory.compareText(h.price, actions.stats[h.productId]).orEmpty(),
                        history = PriceHistory.frequencyText(actions.stats[h.productId]).orEmpty(),
                        best = PriceHistory.isBest(h.price, actions.stats[h.productId]),
                        onList = ShoppingItem.fromHit(h).id in actions.listIds,
                        onToggleList = { actions.onToggleList(h) },
                        onShare = { actions.onShare(h) },
                        onClick = { open() },
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
