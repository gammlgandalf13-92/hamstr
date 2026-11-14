package de.hamstr.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Ein Markt, dessen Prospekt man in Hamstr ansehen kann. */
data class MarketTarget(val name: String, val slug: String, val chain: String)

/** Filter und Auswahl für die Marktseite – ohne Android, damit testbar. */
object MarketBrowse {
    /** Suchname bei Marktguru für die Standard-Ketten, falls noch kein Angebot den genauen Namen verraten hat. */
    private val DEFAULT_NAMES = mapOf(
        "rewe" to ("REWE" to "rewe"),
        "lidl" to ("Lidl" to "lidl"),
        "aldi" to ("ALDI SÜD" to "aldi-sued"),
        "edeka" to ("EDEKA" to "edeka"),
        "kaufland" to ("Kaufland" to "kaufland"),
        "netto" to ("Netto Marken-Discount" to "netto-marken-discount"),
        "penny" to ("Penny" to "penny"),
        "norma" to ("Norma" to "norma"),
    )

    /**
     * Welche Prospekte zeigen? Eingeschaltete Märkte, Lieblingsmärkte zuerst. Der genaue Name stammt,
     * wenn möglich, aus bisher gesehenen Angeboten (z. B. „ALDI SÜD“ statt „Aldi“).
     */
    fun targets(enabled: List<String>, favorites: Set<String>, known: List<Pair<String, String>>): List<MarketTarget> {
        val byChain = known.filter { it.first.isNotBlank() }
            .groupBy { Geo.chainKey(it.first) }
            .mapValues { (_, v) -> v.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key }
        return enabled.distinct().mapNotNull { chain ->
            val (name, slug) = byChain[chain] ?: DEFAULT_NAMES[chain]
                ?: (chain.replaceFirstChar { it.uppercase() } to chain)
            MarketTarget(name, slug, chain)
        }.sortedBy { it.chain !in favorites }
    }

    /** Häufigste Kategorien für die Filter-Chips. */
    fun categories(offers: List<Offer>, max: Int = 12): List<String> =
        offers.map { it.category }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(max).map { it.key }

    /** Suche (alle Wörter), Woche (0/1/2, null = alle) und Kategorie anwenden; günstigste zuerst innerhalb gleicher Gültigkeit. */
    fun filter(offers: List<Offer>, query: String, week: Int?, category: String?, today: LocalDate = LocalDate.now()): List<Offer> {
        val words = Matcher.norm(query).split(" ").filter { it.isNotBlank() }
        return offers.filter { o ->
            val text = Matcher.norm(o.rawText + " " + o.category)
            words.all { it in text } &&
                (week == null || week in Matcher.weeksOf(o.validFrom, o.validTo, today)) &&
                (category == null || o.category == category) &&
                Matcher.weeksOf(o.validFrom, o.validTo, today).isNotEmpty()
        }
    }
}

/** Alle Angebote eines Marktes – blättern, suchen, auf die Einkaufsliste setzen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(store: Store, target: MarketTarget, onClose: () -> Unit, onShoppingChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val plz = remember { store.plz }
    val offers = remember { mutableStateListOf<Offer>() }
    var total by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var week by remember { mutableStateOf<Int?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var listIds by remember { mutableStateOf(store.shoppingList().map { it.id }.toSet()) }
    val listState = rememberLazyListState()
    fun isDone() = total >= 0 && offset >= total

    suspend fun loadPage() {
        if (loading || isDone() || !Regex("\\d{5}").matches(plz)) return
        loading = true
        error = null
        try {
            val page = withContext(Dispatchers.IO) { Marktguru.marketPage(target.name, target.slug, plz, offset) }
            val known = offers.map { it.id }.toSet()
            offers.addAll(page.offers.filter { it.id !in known })
            total = page.total
            offset += 100
            if (isDone()) store.setNoProspect(target.chain, plz, offers.isEmpty())
        } catch (e: Exception) {
            error = "Angebote konnten nicht geladen werden. Bitte später noch einmal versuchen."
        } finally {
            loading = false
        }
    }

    /** Für die Suche: restliche Seiten nachladen (mit kurzer Pause, um Marktguru zu schonen). */
    suspend fun loadAll() {
        while (!isDone() && error == null) {
            loadPage()
            delay(300)
        }
    }

    LaunchedEffect(Unit) { loadPage() }
    // Beim Scrollen ans Ende die nächste Seite holen
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= listState.layoutInfo.totalItemsCount - 6) loadPage()
        }
    }
    // Suche, Woche oder Kategorie sollen alle Angebote erfassen
    LaunchedEffect(query.isNotBlank() || week != null || category != null) {
        if (query.isNotBlank() || week != null || category != null) loadAll()
    }

    val today = remember { LocalDate.now() }
    val shown = MarketBrowse.filter(offers, query, week, category, today)
    val weeks = (0..2).filter { w -> offers.any { w in Matcher.weeksOf(it.validFrom, it.validTo, today) } }
    val cats = MarketBrowse.categories(offers)

    fun toggleList(o: Offer) {
        val item = ShoppingItem.fromOffer(o)
        val list = store.shoppingList()
        val next = if (list.any { it.id == item.id }) list.filterNot { it.id == item.id } else list + item
        store.saveShoppingList(next)
        listIds = next.map { it.id }.toSet()
        onShoppingChanged()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(target.name, fontWeight = FontWeight.Bold)
                            Text(
                                when {
                                    total < 0 -> "Prospekt wird geladen…"
                                    // Fertig geladen: nur die Angebote, die wirklich von diesem Markt sind
                                    isDone() && offers.isEmpty() -> "kein Prospekt · PLZ $plz"
                                    isDone() -> "${offers.size} Angebote · PLZ $plz"
                                    else -> "ca. $total Angebote · PLZ $plz"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                    },
                    actions = {
                        RetailerApps.buttonText(ctx, target.chain)?.let { label ->
                            TextButton(onClick = { RetailerApps.open(ctx, target.chain) }) {
                                Text(label.removeSuffix(" öffnen"))
                            }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Weitere Optionen") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Bei Marktguru öffnen") },
                                    onClick = {
                                        menu = false
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.marktguru.de/r/${target.slug}")))
                                    },
                                )
                            }
                        }
                    },
                )
            },
        ) { inner ->
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(inner).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Im Prospekt suchen, z. B. Käse") },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Leeren") }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (weeks.size > 1) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { FilterChip(selected = week == null, onClick = { week = null }, label = { Text("Alle") }) }
                            items(weeks) { w ->
                                val label = when (w) { 0 -> "Diese Woche"; 1 -> "Nächste Woche"; else -> "Später" }
                                FilterChip(selected = week == w, onClick = { week = if (week == w) null else w }, label = { Text(label) })
                            }
                        }
                    }
                }
                if (cats.isNotEmpty()) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(cats) { c ->
                                FilterChip(selected = category == c, onClick = { category = if (category == c) null else c },
                                    label = { Text(c) })
                            }
                        }
                    }
                }
                when {
                    !Regex("\\d{5}").matches(plz) -> item {
                        EmptyState(Icons.Outlined.ShoppingCart, "PLZ fehlt", "Trag in den Einstellungen deine PLZ ein, damit Hamstr den Prospekt für deine Region lädt.")
                    }
                    isDone() && offers.isEmpty() && !loading -> item {
                        EmptyState(Icons.Outlined.ShoppingCart, "Kein Prospekt bei Marktguru",
                            "Für ${target.name} gibt es bei Marktguru in deiner Region (PLZ $plz) keinen Prospekt. " +
                                "Manche Händler veröffentlichen dort gar nicht oder nur in einigen Regionen – " +
                                "z. B. Norma oder EDEKA in Nordbayern. Hamstr kann deren Angebote deshalb nicht finden.",
                            RetailerApps.buttonText(ctx, target.chain)?.let { "Stattdessen: $it" },
                        ) { RetailerApps.open(ctx, target.chain) }
                    }
                    shown.isEmpty() && offers.isNotEmpty() && !loading -> item {
                        EmptyState(Icons.Filled.Search, "Nichts gefunden", "Anders suchen oder Filter zurücksetzen.")
                    }
                }
                items(shown, key = { "m|" + it.id + "|" + it.storeSlug }) { o ->
                    OfferCard(
                        image = o.imageUrl, title = o.displayName, description = o.description,
                        price = o.price, oldPrice = o.oldPrice, validFrom = o.validFrom, validTo = o.validTo,
                        onList = o.url in listIds,
                        onToggleList = { toggleList(o) },
                        onShare = { shareText(ctx, ShareText.offer(o), "Angebot teilen") },
                        onClick = {
                            if (!RetailerApps.open(ctx, o.store)) ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(o.url)))
                        },
                    )
                }
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        when {
                            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(10.dp))
                                Text(if (query.isNotBlank()) "Durchsuche alle Angebote…" else "Lade weitere Angebote…",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { scope.launch { error = null; loadPage() } }) { Text("Erneut versuchen") }
                            }
                            isDone() && offers.isNotEmpty() -> Text("Prospektdaten: Marktguru", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Kleine Leiste „Prospekte: REWE · Lidl · …“ über den Ergebnissen. */
@Composable
fun ProspectChips(targets: List<MarketTarget>, noProspect: (MarketTarget) -> Boolean = { false }, onOpen: (MarketTarget) -> Unit) {
    val ctx = LocalContext.current
    if (targets.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item { Text("Prospekte:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(targets) { t ->
            val external = noProspect(t) && RetailerApps.retailerFor(t.chain) != null
            AssistChip(
                onClick = { onOpen(t) },
                label = {
                    Text(if (!external) t.name
                    else if (RetailerApps.hasApp(ctx, t.chain)) "${t.name}-App ↗" else "${t.name}-Website ↗")
                },
            )
        }
    }
}
