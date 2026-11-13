package de.hamstr.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Angebotskarte wie in Prospekt-Apps: Bild mit Rabatt-Plakette, Preis groß, Streichpreis,
 * Gültigkeit und – falls bekannt – der Normalpreis aus Open Prices.
 */
@Composable
fun OfferCard(
    image: String,
    title: String,
    description: String,
    price: Double?,
    oldPrice: Double?,
    validFrom: String,
    validTo: String,
    store: String = "",
    hint: String = "",
    ref: RefPrice? = null,
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp)) {
            Box {
                ProductImage(image, 80.dp)
                Matcher.discountPercent(price, oldPrice)?.let { pct ->
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        shape = RoundedCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                        modifier = Modifier.align(Alignment.TopStart),
                    ) {
                        Text("−$pct %", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (description.isNotBlank()) {
                    Text(description, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        price?.let { Matcher.euro(it) } ?: "Preis s. Prospekt",
                        style = if (price != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (oldPrice != null && price != null && oldPrice > price) {
                        Spacer(Modifier.size(8.dp))
                        Text(Matcher.euro(oldPrice), style = MaterialTheme.typography.bodyMedium,
                            textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp))
                    }
                }
                val validity = Matcher.validityText(validFrom, validTo)
                val meta = listOf(store, validity).filter { it.isNotBlank() }.joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(meta, style = MaterialTheme.typography.labelMedium,
                        color = if (validity == "nur noch heute") MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ref?.let { RefPriceLine(it, price) }
                if (hint.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Filled.Warning, contentDescription = null, Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.size(4.dp))
                        Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** „Normalpreis ca. 2,49 € · 20 % gespart“ + Herkunft in klein. */
@Composable
fun RefPriceLine(ref: RefPrice, price: Double? = null) {
    val pct = OpenPrices.savingPercent(price, ref)
    Column(Modifier.padding(top = 4.dp)) {
        Text(
            "Normalpreis ca. ${Matcher.euro(ref.price)}" + (pct?.let { " · $it % gespart" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (pct != null) FontWeight.Medium else FontWeight.Normal,
            color = if (pct != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Open Prices · " + ref.source(), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Leerer Zustand: Symbol, kurze Erklärung, eine klare Aktion. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    text: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null) {
            Spacer(Modifier.height(4.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Hinweisbanner (z. B. „Liste evtl. unvollständig“). */
@Composable
fun InfoBanner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, contentDescription = null, Modifier.size(20.dp))
            Spacer(Modifier.size(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Abschnittsüberschrift wie in den Android-Einstellungen. */
@Composable
fun SettingsHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** Eine Zeile der Einstellungen: Titel, Erklärung, rechts Schalter o. Ä. Ganze Zeile antippbar. */
@Composable
fun SettingsRow(
    title: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.size(12.dp))
            trailing()
        }
    }
}
