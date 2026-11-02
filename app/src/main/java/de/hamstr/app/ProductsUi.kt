package de.hamstr.app

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    fun persist() = store.saveProducts(products.toList())

    fun save(p: Product) {
        val i = products.indexOfFirst { it.id == p.id }
        if (i >= 0) products[i] = p else products.add(p)
        persist()
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
            onManual = { searching = false; isNew = true; editing = Product(id = UUID.randomUUID().toString()) },
            onPick = { offer ->
                searching = false
                isNew = true
                editing = productFromOffer(offer)
            },
        )
    } else {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (products.isEmpty()) item { Text("Noch keine Produkte. Tippe auf +, um eins zu suchen.") }
                items(products, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth().clickable { isNew = false; editing = p }) {
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
                            }
                        }
                    }
                }
            }
            FloatingActionButton(
                onClick = { searching = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { Text("+", fontSize = 26.sp) }
        }
    }

    val current = editing
    if (current != null) {
        ProductDialog(
            initial = current,
            isNew = isNew,
            onDismiss = { editing = null },
            onSave = { save(it); editing = null },
            onDelete = if (isNew) null else {
                {
                    products.removeAll { it.id == current.id }
                    Images.delete(ctx, current.id)
                    persist()
                    editing = null
                }
            },
        )
    }
}

/** Vorschlag aus einem Angebot: Name, Marke, Größe und Bild übernehmen. */
private fun productFromOffer(o: Offer): Product {
    val size = Matcher.parseSizes(o.rawText).firstOrNull()?.let { s ->
        val amount = if (s.amount >= 1000 && s.unit != "stk") s.amount / 1000 else s.amount
        val unit = when {
            s.amount >= 1000 && s.unit == "g" -> "kg"
            s.amount >= 1000 && s.unit == "ml" -> "l"
            else -> s.unit
        }
        val a = if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toString().replace(".", ",")
        (if (s.count > 1) "${s.count} x " else "") + "$a $unit"
    }.orEmpty()
    // Marke nicht doppelt im Namen ("Ferrero Nutella" → Name "Nutella")
    val name = o.productName.let { n ->
        if (o.brand.isNotBlank() && n.startsWith(o.brand, ignoreCase = true)) n.substring(o.brand.length).trim() else n
    }.ifBlank { o.description.take(40) }
    return Product(
        id = UUID.randomUUID().toString(),
        names = listOf(name).filter { it.isNotBlank() },
        brand = o.brand,
        size = size,
        image = o.imageUrl,
    )
}

// ------------------------------------------------------------------ Suche
@Composable
fun SearchScreen(store: Store, onClose: () -> Unit, onManual: () -> Unit, onPick: (Offer) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<Offer>?>(null) }
    var onlyMine by remember { mutableStateOf(true) }
    val plz = store.plz
    val myStores = store.stores

    BackHandler(onBack = onClose)

    fun search() {
        if (query.isBlank()) return
        if (!Regex("\\d{5}").matches(plz)) {
            error = "Bitte zuerst in den Einstellungen deine PLZ eintragen."
            return
        }
        loading = true
        error = null
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { Marktguru.search(query.trim(), plz) }
                results = found.distinctBy { it.id to it.storeSlug }
            } catch (e: Exception) {
                error = "Suche fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    val shown = results?.let { list -> if (onlyMine) list.filter { Matcher.storeAllowed(it, myStores) } else list }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("← Zurück") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onManual) { Text("Ohne Suche anlegen") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Artikel suchen, z. B. Nutella") },
                singleLine = true,
                trailingIcon = {
                    if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = { search() }, enabled = query.isNotBlank()) { Text("🔍", fontSize = 18.sp) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text("Nur meine Märkte", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(6.dp))
                Switch(checked = onlyMine, onCheckedChange = { onlyMine = it })
            }
        }
        error?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.error) } }
        if (shown != null && shown.isEmpty() && !loading) {
            item {
                Text(
                    if (onlyMine && results.orEmpty().isNotEmpty())
                        "Nur Angebote bei anderen Märkten – Schalter „Nur meine Märkte“ ausschalten, um sie zu sehen."
                    else "Gerade nichts im Prospekt. Gesucht wird nur in aktuellen Angeboten – " +
                        "über „Ohne Suche anlegen“ kannst du den Artikel trotzdem beobachten.",
                )
            }
        }
        items(shown.orEmpty(), key = { it.id + "|" + it.storeSlug }) { o ->
            Card(Modifier.fillMaxWidth().clickable { onPick(o) }) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProductImage(o.imageUrl, 64.dp)
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(o.displayName, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (o.description.isNotBlank()) {
                            Text(o.description, style = MaterialTheme.typography.bodySmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        val price = o.price?.let { Matcher.euro(it) } ?: ""
                        Text(
                            "${o.store}  $price  ${Matcher.shortDate(o.validFrom)}–${Matcher.shortDate(o.validTo)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Bearbeiten
@Composable
fun ProductDialog(
    initial: Product,
    isNew: Boolean,
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
    // Weitere Optionen nur aufklappen, wenn dort schon etwas eingetragen ist
    var advanced by remember {
        mutableStateOf(initial.maxPrice != null || initial.exclude.isNotEmpty() ||
            initial.queries.isNotEmpty() || initial.stores.isNotEmpty())
    }
    val valid = names.isNotBlank() || brand.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Neues Produkt" else "Produkt bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (image.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProductImage(image, 64.dp)
                        Spacer(Modifier.size(8.dp))
                        TextButton(onClick = { image = "" }) { Text("Bild entfernen") }
                    }
                }
                Field("Name", names, { names = it }, "z. B. Nutella")
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
                    Text(if (advanced) "▲ Weniger Optionen" else "▼ Weitere Optionen")
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
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
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
                    )
                )
            }) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
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
