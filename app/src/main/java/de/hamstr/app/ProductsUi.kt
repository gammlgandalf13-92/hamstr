package de.hamstr.app

import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

// ------------------------------------------------------------------ Bild
@Composable
fun ProductImage(image: String, size: Dp) {
    val shape = RoundedCornerShape(10.dp)
    val model = Images.model(image)
    Box(
        Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text("🐹", fontSize = (size.value * 0.4f).sp)
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White),
            )
        }
    }
}

// ------------------------------------------------------------------ Produkte
@Composable
fun ProductsScreen(store: Store) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val products = remember { mutableStateListOf<Product>().apply { addAll(store.products()) } }
    var editing by remember { mutableStateOf<Product?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var tip by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Product?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var undoJob by remember { mutableStateOf<Job?>(null) }
    var refs by remember { mutableStateOf(store.refPrices()) }

    fun persist() = store.saveProducts(products.toList())

    /** Vergleichspreise im Hintergrund nachladen (nur veraltete, höchstens einmal pro Woche). */
    fun loadRefs(eans: List<String>, force: Boolean = false) {
        scope.launch {
            val changed = withContext(Dispatchers.IO) { runCatching { OpenPrices.refresh(store, eans, force) }.getOrDefault(false) }
            if (changed) refs = store.refPrices()
        }
    }
    LaunchedEffect(Unit) { loadRefs(products.map { it.ean }) }

    /** Entfernen mit 5 Sekunden Rückgängig. Das Bild wird erst danach gelöscht. */
    fun delete(p: Product) {
        val index = products.indexOfFirst { it.id == p.id }
        if (index < 0) return
        products.removeAt(index)
        persist()
        undoJob?.cancel()
        undoJob = scope.launch {
            var restored = false
            try {
                val name = p.label.ifBlank { "Produkt" }
                val result = withTimeoutOrNull(5_000) {
                    snackbar.showSnackbar("„$name“ entfernt", actionLabel = "Rückgängig", duration = SnackbarDuration.Indefinite)
                }
                if (result == SnackbarResult.ActionPerformed) {
                    products.add(index.coerceAtMost(products.size), p)
                    persist()
                    restored = true
                }
            } finally {
                // showSnackbar räumt bei Zeitablauf/Abbruch selbst auf
                if (!restored) Images.delete(ctx, p.id)
            }
        }
    }

    fun save(p: Product) {
        val i = products.indexOfFirst { it.id == p.id }
        if (i >= 0) products[i] = p else products.add(p)
        persist()
        if (p.ean.isNotBlank()) loadRefs(listOf(p.ean))
        Images.cleanup(ctx, p.id, keep = p.image)
        // Bild aus dem Angebot aufs Handy kopieren, damit es nicht mit dem Angebot verschwindet
        if (p.image.startsWith("http")) {
            scope.launch {
                val local = withContext(Dispatchers.IO) { Images.download(ctx, p.image, p.id) }
                if (local != null) {
                    val j = products.indexOfFirst { it.id == p.id }
                    if (j >= 0) {
                        products[j] = products[j].copy(image = local)
                        persist()
                    }
                }
            }
        }
    }

    if (searching) {
        SearchScreen(
            store = store,
            onClose = { searching = false },
            onManual = { searching = false; isNew = true; tip = null; editing = Product(id = UUID.randomUUID().toString()) },
            onPick = { offer ->
                searching = false
                isNew = true
                tip = null
                editing = productFromOffer(offer)
            },
            onPickCatalog = { item ->
                searching = false
                val existing = products.firstOrNull { it.ean.isNotBlank() && it.ean == item.code }
                if (existing != null) {
                    isNew = false
                    tip = "Dieses Produkt beobachtest du schon."
                    editing = existing
                } else {
                    isNew = true
                    tip = "Prospekte nennen oft keine Sorte (z. B. „versch. Sorten“). Kürze den Namen ggf. – " +
                        "je kürzer, desto mehr Angebote werden erkannt."
                    editing = productFromCatalog(item)
                }
            },
        )
    } else {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (products.isEmpty()) item {
                    EmptyState(
                        Icons.AutoMirrored.Filled.List, "Noch keine Produkte",
                        "Such ein Produkt per Name oder Barcode – Hamstr meldet sich, sobald es im Angebot ist.",
                        "Produkt hinzufügen",
                    ) { searching = true }
                }
                items(products, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth().clickable { isNew = false; tip = null; editing = p }) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProductImage(p.image, 56.dp)
                            Spacer(Modifier.size(12.dp))
                            Column {
                                Text(p.label.ifBlank { "(ohne Namen)" }, fontWeight = FontWeight.Bold)
                                val details = buildList {
                                    if (p.names.isEmpty()) add("alle Artikel der Marke")
                                    if (p.size.isNotBlank()) add(p.size + if (p.exact) " (exakt)" else "")
                                    p.maxPrice?.let { add("bis ${Matcher.euro(it)}") }
                                    if (p.stores.isNotEmpty()) add(p.stores.joinToString(", "))
                                    if (p.exclude.isNotEmpty()) add("ohne: " + p.exclude.joinToString(", "))
                                }
                                if (details.isNotEmpty()) {
                                    Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                }
                                refs[p.ean]?.ref?.let { RefPriceLine(it) }
                            }
                        }
                    }
                }
            }
            FloatingActionButton(
                onClick = { searching = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { Icon(Icons.Filled.Add, contentDescription = "Produkt hinzufügen") }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
        }
    }

    val current = editing
    if (current != null) {
        ProductDialog(
            initial = current,
            isNew = isNew,
            tip = tip,
            ref = refs[current.ean]?.ref,
            onDismiss = { editing = null },
            onSave = { save(it); editing = null },
            onDelete = if (isNew) null else {
                { confirmDelete = current }
            },
        )
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Produkt entfernen?") },
            text = { Text("„${p.label.ifBlank { "Produkt" }}“ wird nicht mehr beobachtet.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    editing = null
                    delete(p)
                }) { Text("Entfernen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen") } },
        )
    }
}

/** Größe aus einem Text als lesbare Angabe, z. B. "450 g", "1,5 l", "6 x 0,33 l". */
internal fun sizeText(text: String): String = Matcher.parseSizes(text).firstOrNull()?.let { s ->
    val big = s.amount >= 1000 && s.unit != "stk"
    val amount = if (big) s.amount / 1000 else s.amount
    val unit = when {
        big && s.unit == "g" -> "kg"
        big && s.unit == "ml" -> "l"
        else -> s.unit
    }
    val a = if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toString().replace(".", ",")
    (if (s.count > 1) "${s.count} x " else "") + "$a $unit"
}.orEmpty()

/** Marke nicht doppelt im Namen ("Ferrero Nutella" → Name "Nutella"). */
internal fun nameWithoutBrand(name: String, brand: String): String =
    if (brand.isNotBlank() && name.startsWith(brand, ignoreCase = true)) name.substring(brand.length).trim(' ', '-', ',')
    else name

/** Vorschlag aus einem Angebot: Name, Marke, Größe und Bild übernehmen. */
private fun productFromOffer(o: Offer): Product = Product(
    id = UUID.randomUUID().toString(),
    names = listOf(nameWithoutBrand(o.productName, o.brand).ifBlank { o.description.take(40) }).filter { it.isNotBlank() },
    brand = o.brand,
    size = sizeText(o.rawText),
    image = o.imageUrl,
    imageSource = if (o.imageUrl.isNotBlank()) "offer" else "",
)

/** Vorschlag aus dem Katalog (Open Food Facts). */
/**
 * Ist dasselbe Produkt gerade im Prospekt? Dann dessen Bild verwenden statt des Katalogfotos.
 * Marke, Name und Größe müssen passen – ein beliebiges Produkt derselben Marke reicht nicht.
 */
internal fun withOfferImage(c: CatalogItem, offers: List<Offer>, today: LocalDate = LocalDate.now()): CatalogItem {
    if (offers.isEmpty()) return c
    val probe = productFromCatalog(c)
    if (probe.names.isEmpty()) return c
    val best = offers.filter { it.imageUrl.isNotBlank() }
        .mapNotNull { Matcher.match(probe, it, emptyList(), today) }
        .filter { it.confidence >= 0.7 && it.notes.none { n -> n.startsWith("Größe abweichend") } }
        .maxByOrNull { it.confidence } ?: return c
    return c.copy(image = best.offer.imageUrl, imageFromOffer = true)
}

internal fun productFromCatalog(c: CatalogItem): Product = Product(
    id = UUID.randomUUID().toString(),
    names = listOf(nameWithoutBrand(c.name, c.brand)).filter { it.isNotBlank() },
    brand = c.brand,
    size = sizeText(c.quantity),
    image = c.image,
    ean = c.code,
    imageSource = when {
        c.image.isBlank() -> ""
        c.imageFromOffer -> "offer"
        else -> "catalog"
    },
)

// ------------------------------------------------------------------ Suche
@Composable
fun SearchScreen(
    store: Store,
    onClose: () -> Unit,
    onManual: () -> Unit,
    onPick: (Offer) -> Unit,
    onPickCatalog: (CatalogItem) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var loadingOffers by remember { mutableStateOf(false) }
    var loadingCatalog by remember { mutableStateOf(false) }
    var offerError by remember { mutableStateOf<String?>(null) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var offers by remember { mutableStateOf<List<Offer>?>(null) }
    var catalog by remember { mutableStateOf<List<CatalogItem>?>(null) }
    var onlyMine by remember { mutableStateOf(true) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val plz = store.plz
    val myStores = store.stores
    val loading = loadingOffers || loadingCatalog

    BackHandler(onBack = onClose)

    /** Barcode nachschlagen und direkt zum Anlegen öffnen (mit Prospektbild, falls gerade im Angebot). */
    fun lookupBarcode(code: String) {
        if (loading) return
        query = code
        offers = null
        catalog = null
        offerError = null
        catalogError = null
        loadingCatalog = true
        scope.launch {
            try {
                val item = withContext(Dispatchers.IO) { Catalog.byBarcode(code) }
                if (item == null) {
                    catalog = emptyList()
                    catalogError = "Zum Barcode $code ist kein Produkt bekannt. Versuch es mit dem Namen " +
                        "oder über „Ohne Suche anlegen“."
                } else {
                    val withImage = if (Regex("\\d{5}").matches(plz)) {
                        val found = withContext(Dispatchers.IO) {
                            runCatching { Marktguru.search(item.displayName, plz) }.getOrDefault(emptyList())
                        }
                        withOfferImage(item, found)
                    } else item
                    onPickCatalog(withImage)
                }
            } catch (e: Exception) {
                catalog = emptyList()
                catalogError = "Produktkatalog nicht erreichbar – später noch einmal versuchen."
            } finally {
                loadingCatalog = false
            }
        }
    }

    fun search() {
        val q = query.trim()
        if (q.isBlank() || loading) return
        Catalog.normalizeBarcode(q)?.let { lookupBarcode(it); return }
        offerError = null
        catalogError = null
        // Angebote (Marktguru) – braucht die PLZ
        if (Regex("\\d{5}").matches(plz)) {
            loadingOffers = true
            scope.launch {
                try {
                    offers = withContext(Dispatchers.IO) { Marktguru.search(q, plz) }.distinctBy { it.id to it.storeSlug }
                } catch (e: Exception) {
                    offers = emptyList()
                    offerError = "Angebote nicht abrufbar: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    loadingOffers = false
                }
            }
        } else {
            offers = emptyList()
            offerError = "Für Angebote bitte in den Einstellungen die PLZ eintragen."
        }
        // Katalog (Open Food Facts) – unabhängig von Angeboten
        loadingCatalog = true
        scope.launch {
            try {
                catalog = withContext(Dispatchers.IO) { Catalog.search(q) }
            } catch (e: Exception) {
                catalog = emptyList()
                catalogError = "Produktkatalog nicht erreichbar – später noch einmal versuchen."
            } finally {
                loadingCatalog = false
            }
        }
    }

    // Katalog-Produkte, die gerade im Prospekt sind, bekommen dessen Bild
    val catalogShown = remember(catalog, offers) { catalog.orEmpty().map { withOfferImage(it, offers.orEmpty()) } }
    val shownOffers = offers?.let { list -> if (onlyMine) list.filter { Matcher.storeAllowed(it, myStores) } else list }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            // Suchfeld wie bei Google/Play Store: links zurück, rechts suchen bzw. leeren
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Name oder Barcode, z. B. Nutella") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                leadingIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                },
                trailingIcon = {
                    when {
                        loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        query.isNotBlank() -> Row {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Leeren") }
                            IconButton(onClick = { search() }) { Icon(Icons.Filled.Search, contentDescription = "Suchen") }
                        }
                        else -> Icon(Icons.Filled.Search, contentDescription = null)
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(8.dp))
            OutlinedButton(
                onClick = {
                    scanError = null
                    BarcodeScan.start(ctx,
                        onCode = { lookupBarcode(it) },
                        onError = { scanError = it })
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(painterResource(R.drawable.ic_barcode), contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Barcode scannen")
            }
            scanError?.let { Hint(it) }
            TextButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("Ohne Suche von Hand anlegen") }
        }

        // ---------------- Aktuell im Angebot
        if (offers != null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Aktuell im Angebot" + (shownOffers?.let { " (${it.size})" } ?: ""))
                    Spacer(Modifier.weight(1f))
                    Text("nur meine Märkte", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.size(6.dp))
                    Switch(checked = onlyMine, onCheckedChange = { onlyMine = it })
                }
            }
            offerError?.let { msg -> item { Hint(msg) } }
            if (!loadingOffers && offerError == null && shownOffers.isNullOrEmpty()) {
                item {
                    Hint(
                        if (onlyMine && offers.orEmpty().isNotEmpty()) "Nur bei anderen Märkten im Angebot."
                        else "Gerade in keinem Prospekt – unten im Katalog findest du es trotzdem."
                    )
                }
            }
            items(shownOffers.orEmpty(), key = { "o|" + it.id + "|" + it.storeSlug }) { o ->
                OfferCard(
                    image = o.imageUrl, title = o.displayName, description = o.description,
                    price = o.price, oldPrice = o.oldPrice, validFrom = o.validFrom, validTo = o.validTo,
                    store = o.store, onClick = { onPick(o) },
                )
            }
        }

        // ---------------- Katalog
        if (catalog != null || loadingCatalog) {
            item { SectionTitle("Alle Produkte" + (catalog?.let { " (${it.size})" } ?: "")) }
            catalogError?.let { msg -> item { Hint(msg) } }
            if (!loadingCatalog && catalogError == null && catalog.isNullOrEmpty()) {
                item { Hint("Nichts gefunden – anders schreiben oder „Ohne Suche anlegen“.") }
            }
            items(catalogShown, key = { "c|" + it.code + "|" + it.displayName }) { c ->
                ResultCard(
                    image = c.image,
                    title = c.displayName,
                    subtitle = c.quantity,
                    footer = if (c.imageFromOffer) "Gerade im Prospekt · Beobachten" else "Beobachten",
                    onClick = { onPickCatalog(c) },
                )
            }
            item {
                Text("Produktdaten: Open Food Facts", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ResultCard(image: String, title: String, subtitle: String, footer: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductImage(image, 64.dp)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(footer, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ------------------------------------------------------------------ Bearbeiten
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDialog(
    initial: Product,
    isNew: Boolean,
    tip: String? = null,
    ref: RefPrice? = null,
    onDismiss: () -> Unit,
    onSave: (Product) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var names by remember { mutableStateOf(initial.names.joinToString(" | ")) }
    var brand by remember { mutableStateOf(initial.brand) }
    var size by remember { mutableStateOf(initial.size) }
    var exact by remember { mutableStateOf(initial.exact) }
    var queries by remember { mutableStateOf(initial.queries.joinToString(" | ")) }
    var exclude by remember { mutableStateOf(initial.exclude.joinToString(", ")) }
    var maxPrice by remember { mutableStateOf(initial.maxPrice?.let { "%.2f".format(Locale.GERMANY, it) }.orEmpty()) }
    var stores by remember { mutableStateOf(initial.stores.joinToString(", ")) }
    var image by remember { mutableStateOf(initial.image) }
    var imageSource by remember { mutableStateOf(initial.imageSource) }
    var imageBusy by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Android-Fotoauswahl – braucht keine Berechtigung
    fun takeOver(uri: android.net.Uri, temp: java.io.File? = null) {
        imageBusy = true
        imageError = null
        scope.launch {
            val local = withContext(Dispatchers.IO) {
                Images.importFrom(ctx, uri, initial.id).also { temp?.delete() }
            }
            if (local != null) {
                image = local
                imageSource = "own"
            } else {
                imageError = "Das Bild konnte nicht geladen werden."
            }
            imageBusy = false
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) takeOver(uri)
    }
    // Kamera: Foto in eine temporäre Datei, danach verkleinert übernehmen (keine Kamera-Berechtigung nötig)
    var photoFile by remember { mutableStateOf<java.io.File?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = photoFile
        if (ok && file != null && file.length() > 0) {
            takeOver(androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file), file)
        } else {
            file?.delete()
        }
    }
    fun startCamera() {
        try {
            val file = java.io.File(java.io.File(ctx.cacheDir, "photos").apply { mkdirs() }, "${initial.id}.jpg")
            photoFile = file
            takePhoto.launch(androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file))
        } catch (e: Exception) {
            imageError = "Keine Kamera-App gefunden."
        }
    }
    // Weitere Optionen nur aufklappen, wenn dort schon etwas eingetragen ist
    var advanced by remember {
        mutableStateOf(initial.maxPrice != null || initial.exclude.isNotEmpty() ||
            initial.queries.isNotEmpty() || initial.stores.isNotEmpty())
    }
    val valid = names.isNotBlank() || brand.isNotBlank()

    // Vollbild-Formular wie in Kontakte/Kalender: ✕ links, „Speichern“ rechts oben
    fun doSave() {
        onSave(
                    initial.copy(
                        names = Store.splitList(names, "|"),
                        brand = brand.trim(),
                        size = size.trim(),
                        exact = exact,
                        queries = Store.splitList(queries, "|"),
                        exclude = Store.splitList(exclude, ","),
                        maxPrice = maxPrice.replace(",", ".").trim().toDoubleOrNull(),
                        stores = Store.splitList(stores, ",").map { it.lowercase(Locale.GERMAN) },
                        image = image,
                        imageSource = if (image.isBlank()) "" else imageSource,
                    )
                )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(if (isNew) "Neues Produkt" else "Produkt bearbeiten") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Abbrechen") }
                    },
                    actions = {
                        TextButton(enabled = valid, onClick = { doSave() }) {
                            Text("Speichern", fontWeight = FontWeight.Bold)
                        }
                    },
                )
            },
        ) { inner ->
            Column(
                Modifier.padding(inner).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProductImage(image, 64.dp)
                    Spacer(Modifier.size(8.dp))
                    Column {
                        TextButton(
                            onClick = {
                                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            enabled = !imageBusy,
                        ) { Text(if (image.isBlank()) "Bild wählen" else "Bild ändern") }
                        TextButton(onClick = { startCamera() }, enabled = !imageBusy) { Text("Foto aufnehmen") }
                        if (image.isNotBlank()) {
                            TextButton(onClick = { image = ""; imageSource = "" }, enabled = !imageBusy) {
                                Text("Bild entfernen")
                            }
                        }
                    }
                    if (imageBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                imageError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (initial.ean.isNotBlank()) {
                    Text("EAN ${initial.ean}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ref?.let { RefPriceLine(it) }
                Field("Name", names, { names = it }, "z. B. Nutella")
                tip?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Field("Marke", brand, { brand = it }, "optional, z. B. Ferrero")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Field("Größe", size, { size = it }, "optional, z. B. 450 g") }
                    Spacer(Modifier.size(8.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Switch(checked = exact, onCheckedChange = { exact = it }, enabled = size.isNotBlank())
                        Text("exakt", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (names.isBlank() && brand.isNotBlank()) {
                    Text("Ohne Name: alle Artikel dieser Marke werden gemeldet.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }

                TextButton(onClick = { advanced = !advanced }) {
                    Icon(if (advanced) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text(if (advanced) "Weniger Optionen" else "Weitere Optionen")
                }
                if (advanced) {
                    Field("Max. Preis (€)", maxPrice, { maxPrice = it }, "z. B. 2,49", KeyboardType.Decimal)
                    Field("Ohne diese Wörter", exclude, { exclude = it }, "z. B. Joghurt, Eis")
                    Field("Nur diese Märkte", stores, { stores = it }, "leer = alle, z. B. rewe, lidl")
                    Field("Eigene Suchbegriffe", queries, { queries = it }, "z. B. Beerenmix | Waldbeeren")
                    Text(
                        "Tipp: Im Namen mehrere Schreibweisen mit | trennen – eine davon muss passen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (onDelete != null) {
                    Spacer(Modifier.size(16.dp))
                    OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.size(8.dp))
                        Text("Produkt entfernen", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun Field(
    label: String, value: String, onChange: (String) -> Unit, placeholder: String,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}
