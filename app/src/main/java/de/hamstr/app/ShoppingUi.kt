package de.hamstr.app

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Text teilen über das Android-Teilen-Menü (WhatsApp, Signal, Mail …). */
fun shareText(context: Context, text: String, title: String = "Teilen") {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, title))
}

// ------------------------------------------------------------------ Einkaufsliste
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(store: Store, onChanged: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val favorites = remember { store.favoriteChains }
    // Abgelaufene Angebote räumt die Liste beim Öffnen selbst weg
    val items = remember {
        val all = store.shoppingList()
        val valid = all.filterNot { it.expired() }
        if (valid.size != all.size) store.saveShoppingList(valid)
        mutableStateListOf<ShoppingItem>().apply { addAll(valid) }
    }
    var newEntry by remember { mutableStateOf("") }

    fun persist() {
        store.saveShoppingList(items.toList())
        onChanged()
    }

    fun toggle(item: ShoppingItem) {
        val i = items.indexOfFirst { it.id == item.id }
        if (i >= 0) {
            items[i] = item.copy(done = !item.done)
            persist()
        }
    }

    fun remove(item: ShoppingItem) {
        val i = items.indexOfFirst { it.id == item.id }
        if (i < 0) return
        items.removeAt(i)
        persist()
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = withTimeoutOrNull(5_000) {
                snackbar.showSnackbar("„${item.title}“ entfernt", actionLabel = "Rückgängig", duration = SnackbarDuration.Indefinite)
            }
            if (result == SnackbarResult.ActionPerformed) {
                items.add(i.coerceAtMost(items.size), item)
                persist()
            }
        }
    }

    fun addOwn() {
        val text = newEntry.trim()
        if (text.isEmpty()) return
        items.add(0, ShoppingItem(id = UUID.randomUUID().toString(), title = text, addedAt = System.currentTimeMillis()))
        newEntry = ""
        persist()
    }

    val open = items.filter { !it.done }
    val done = items.filter { it.done }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = newEntry,
                    onValueChange = { newEntry = it },
                    placeholder = { Text("Eigener Eintrag, z. B. Milch") },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    trailingIcon = {
                        IconButton(onClick = { addOwn() }, enabled = newEntry.isNotBlank()) {
                            Icon(Icons.Filled.Add, contentDescription = "Hinzufügen")
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { addOwn() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (items.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Filled.ShoppingCart, "Deine Einkaufsliste ist leer",
                        "Tippe bei einem Angebot auf +, um es hier zu sammeln – oder trag oben selbst etwas ein.",
                    )
                }
            }
            // Offen, nach Markt: Lieblingsmärkte zuerst
            val groups = open.groupBy { it.store.ifBlank { "Sonstiges" } }.toList().sortedWith(
                compareBy<Pair<String, List<ShoppingItem>>>(
                    { it.first == "Sonstiges" },
                    { Geo.chainKey(it.first) !in favorites },
                    { it.first.lowercase() },
                )
            )
            groups.forEach { (storeName, list) ->
                item(key = "h|$storeName") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text(storeName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        // Im Laden praktisch: direkt in die App des Marktes (Coupons, Kundenkarte)
                        RetailerApps.buttonText(ctx, storeName)?.let { label ->
                            TextButton(onClick = { RetailerApps.open(ctx, storeName) }) { Text(label) }
                        }
                    }
                }
                items(list, key = { "o|" + it.id }) { item ->
                    SwipeRow(onRemove = { remove(item) }) {
                        ShoppingRow(item, onToggle = { toggle(item) }) {
                            if (!RetailerApps.open(ctx, item.store) && item.url.isNotBlank()) {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                            }
                        }
                    }
                }
            }
            if (done.isNotEmpty()) {
                item(key = "done") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Erledigt (${done.size})", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            items.removeAll { it.done }
                            persist()
                        }) { Text("Erledigte entfernen") }
                    }
                }
                items(done, key = { "d|" + it.id }) { item ->
                    SwipeRow(onRemove = { remove(item) }) {
                        ShoppingRow(item, onToggle = { toggle(item) }) { toggle(item) }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }
}

/** Nach links wischen entfernt den Eintrag (mit Rückgängig). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(onRemove: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onRemove()
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Entfernen", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) { content() }
}

@Composable
private fun ShoppingRow(item: ShoppingItem, onToggle: () -> Unit, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp).alpha(if (item.done) 0.55f else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = item.done, onCheckedChange = { onToggle() })
            if (item.image.isNotBlank()) {
                ProductImage(item.image, 44.dp)
                Spacer(Modifier.size(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None,
                )
                val meta = listOfNotNull(
                    item.price?.let { Matcher.euro(it) },
                    Matcher.validityText(item.validFrom, item.validTo).takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
