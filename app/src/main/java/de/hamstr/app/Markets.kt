package de.hamstr.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Eine Kette in der Märkteliste. Distanz/Anzahl stammen aus der letzten Umkreissuche. */
data class ChainInfo(
    val label: String,
    val distanceKm: Double? = null,
    val count: Int = 0,
    val address: String = "",
)

object Markets {
    /** Gängige Ketten – immer in der Liste. Schlüssel = Kurzname für den Märkte-Filter. */
    val DEFAULTS = linkedMapOf(
        "rewe" to "REWE",
        "edeka" to "EDEKA (alle Varianten, z. B. E center)",
        "lidl" to "Lidl",
        "aldi" to "Aldi (Süd + Nord)",
        "kaufland" to "Kaufland",
        "netto" to "Netto",
        "penny" to "Penny",
        "norma" to "Norma",
    )

    /** Ergebnis der Umkreissuche in die Ketten-Infos übernehmen (alte Entfernungen werden ersetzt). */
    fun applyNearby(old: Map<String, ChainInfo>, found: List<NearbyStore>): MutableMap<String, ChainInfo> {
        val result = old.mapValues { (_, v) -> v.copy(distanceKm = null, count = 0, address = "") }.toMutableMap()
        found.filter { it.chain.isNotBlank() }.groupBy { it.chain }.forEach { (chain, shops) ->
            val nearest = shops.minBy { it.distanceKm }
            val label = DEFAULTS[chain] ?: old[chain]?.label ?: labelFor(nearest.name, chain)
            result[chain] = ChainInfo(label, nearest.distanceKm, shops.size, nearest.address)
        }
        return result
    }

    /** "Globus Bad Kissingen" → "Globus": der Teil des Namens, der zum Kurznamen passt. */
    fun labelFor(name: String, key: String): String {
        val first = name.trim().split(" ").firstOrNull().orEmpty()
        return if (Matcher.norm(first) == key) first else key.replaceFirstChar { it.titlecase(Locale.GERMAN) }
    }

    /** Reihenfolge: Ketten im Umkreis (nach Entfernung), dann Standard-Ketten, dann eigene. */
    fun ordered(info: Map<String, ChainInfo>, enabled: Set<String>): List<String> {
        val keys = (DEFAULTS.keys + info.keys + enabled).distinct()
        val defaultOrder = DEFAULTS.keys.toList()
        return keys.sortedWith(
            compareBy<String>(
                { info[it]?.distanceKm == null },
                { info[it]?.distanceKm ?: 0.0 },
                { if (it in DEFAULTS) defaultOrder.indexOf(it) else Int.MAX_VALUE },
                { it },
            )
        )
    }

    fun label(key: String, info: Map<String, ChainInfo>): String =
        DEFAULTS[key] ?: info[key]?.label ?: key.replaceFirstChar { it.titlecase(Locale.GERMAN) }
}

/** Liste "Meine Märkte" mit Schaltern. Änderungen werden sofort gespeichert. */
@Composable
fun MarketList(store: Store, refreshKey: Int) {
    var enabled by remember(refreshKey) { mutableStateOf(store.stores.toSet()) }
    var favorites by remember { mutableStateOf(store.favoriteChains) }
    var info by remember(refreshKey) { mutableStateOf<Map<String, ChainInfo>>(store.chains()) }
    var newChain by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    val searched = info.values.any { it.distanceKm != null }

    fun saveEnabled(next: Set<String>) {
        enabled = next
        store.stores = Markets.ordered(info, next).filter { it in next }
    }

    fun toggle(key: String, on: Boolean) {
        if (!on && enabled.size <= 1 && key in enabled) {
            hint = "Mindestens ein Markt muss eingeschaltet bleiben."
            return
        }
        hint = null
        saveEnabled(if (on) enabled + key else enabled - key)
    }

    fun remove(key: String) {
        if (enabled.size <= 1 && key in enabled) {
            hint = "Mindestens ein Markt muss eingeschaltet bleiben."
            return
        }
        info = info - key
        store.saveChains(info)
        saveEnabled(enabled - key)
    }

    fun add() {
        val name = newChain.trim()
        val key = Geo.chainKey(name)
        if (name.isEmpty() || key.isEmpty()) return
        if (key !in Markets.DEFAULTS && key !in info) {
            info = info + (key to ChainInfo(name))
            store.saveChains(info)
        }
        saveEnabled(enabled + key)
        newChain = ""
        hint = "„${Markets.label(key, info)}“ hinzugefügt und eingeschaltet."
    }

    Text(
        (if (searched) "Ketten im Umkreis stehen oben. Nur eingeschaltete Märkte werden gemeldet."
        else "Nur eingeschaltete Märkte werden gemeldet. Tipp: „Supermärkte im Umkreis finden“ zeigt, was in deiner Nähe ist.") +
            " Mit ★ markierst du Lieblingsmärkte – ihre Angebote stehen in den Ergebnissen oben.",
        style = MaterialTheme.typography.bodySmall,
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            val keys = Markets.ordered(info, enabled)
            keys.forEachIndexed { i, key ->
                val c = info[key]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(Markets.label(key, info), fontWeight = if (key in enabled) FontWeight.Medium else FontWeight.Normal)
                        val km = c?.distanceKm
                        val sub = when {
                            c != null && km != null -> {
                                val count = if (c.count == 1) "1 Filiale" else "${c.count} Filialen"
                                "${"%.1f".format(Locale.GERMANY, km)} km · $count" +
                                    (if (c.address.isNotBlank()) " · ${c.address}" else "")
                            }
                            searched -> "nicht im Umkreis"
                            else -> null
                        }.let { s ->
                            if (store.hasNoProspect(key)) listOfNotNull(s, "kein Prospekt bei Marktguru in deiner Region").joinToString(" · ")
                            else s
                        }
                        sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (key !in Markets.DEFAULTS && c?.distanceKm == null) {
                        IconButton(onClick = { remove(key) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Entfernen")
                        }
                    }
                    IconButton(onClick = { favorites = store.toggleFavorite(key) }) {
                        Icon(
                            if (key in favorites) Icons.Filled.Star else Icons.Outlined.Star,
                            contentDescription = if (key in favorites) "Kein Lieblingsmarkt mehr" else "Als Lieblingsmarkt markieren",
                            tint = if (key in favorites) androidx.compose.ui.graphics.Color(0xFFF2A900)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = key in enabled, onCheckedChange = { toggle(key, it) })
                }
                if (i < keys.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 14.dp))
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = newChain,
            onValueChange = { newChain = it },
            placeholder = { Text("Weitere Kette, z. B. Globus") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { add() }, enabled = newChain.isNotBlank()) { Text("Hinzufügen") }
    }
    hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    Spacer(Modifier.size(2.dp))
}
