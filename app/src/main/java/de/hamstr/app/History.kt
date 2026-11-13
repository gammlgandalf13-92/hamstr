package de.hamstr.app

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Ein Angebot, das Hamstr zu einem Produkt gesehen hat – die Grundlage für den Preisverlauf. */
data class PricePoint(
    val store: String,
    val storeSlug: String,
    val price: Double?,
    val validFrom: String,
    val validTo: String,
    val title: String = "",
) {
    val key: String get() = "$storeSlug|$validFrom|$price|${Matcher.norm(title)}"
}

/** Zusammenfassung des Preisverlaufs eines Produkts. */
data class PriceStats(
    val periods: Int,            // wie oft im Angebot (verschiedene Starttage)
    val lowest: PricePoint?,     // günstigstes Angebot bisher
    val last: PricePoint?,       // jüngstes Angebot
    val intervalDays: Int?,      // ungefährer Abstand zwischen Angeboten
    val since: String,           // ältester Eintrag (ISO)
)

object PriceHistory {
    const val MAX_POINTS = 100

    /** Neue Angebote anhängen (ohne Dubletten), nach Datum sortiert, höchstens [MAX_POINTS], nichts älter als 2 Jahre. */
    fun merge(existing: List<PricePoint>, new: List<PricePoint>, today: LocalDate = LocalDate.now()): List<PricePoint> {
        val cutoff = today.minusYears(2).toString()
        return (existing + new)
            .filter { it.validFrom.isBlank() || it.validFrom >= cutoff }
            .distinctBy { it.key }
            .sortedBy { it.validFrom }
            .takeLast(MAX_POINTS)
    }

    fun stats(points: List<PricePoint>): PriceStats? {
        val dated = points.filter { it.validFrom.isNotBlank() }.sortedBy { it.validFrom }
        if (dated.isEmpty()) return null
        val starts = dated.map { it.validFrom }.distinct()
        val interval = if (starts.size >= 3) {
            runCatching {
                val days = ChronoUnit.DAYS.between(LocalDate.parse(starts.first()), LocalDate.parse(starts.last()))
                (days.toDouble() / (starts.size - 1)).roundToInt()
            }.getOrNull()?.takeIf { it > 0 }
        } else null
        return PriceStats(
            periods = starts.size,
            lowest = dated.filter { it.price != null }.minByOrNull { it.price!! },
            last = dated.last(),
            intervalDays = interval,
            since = starts.first(),
        )
    }

    /** Ist dieser Preis der beste, den Hamstr bisher gesehen hat? Erst ab zwei verschiedenen Angeboten sinnvoll. */
    fun isBest(price: Double?, stats: PriceStats?): Boolean {
        val low = stats?.lowest?.price ?: return false
        return price != null && stats.periods >= 2 && price <= low + 0.001
    }

    /** „etwa alle 3 Wochen“, „etwa alle 10 Tage“ */
    fun intervalText(days: Int): String = when {
        days >= 12 -> {
            val weeks = (days / 7.0).roundToInt()
            if (weeks == 1) "etwa jede Woche" else "etwa alle $weeks Wochen"
        }
        days >= 6 -> "etwa jede Woche"
        else -> "etwa alle $days Tage"
    }

    /** Eine Zeile für Produktliste und Angebotskarte. */
    fun summary(stats: PriceStats, price: Double? = null): String = buildList {
        // Ist der aktuelle Preis der beste, zeigt die Karte das als Plakette – hier nicht doppelt
        if (!isBest(price, stats)) stats.lowest?.price?.let { add("Tiefstpreis ${Matcher.euro(it)} (${stats.lowest.store})") }
        add(if (stats.periods == 1) "1× im Angebot" else "${stats.periods}× im Angebot")
        stats.intervalDays?.let { add(intervalText(it)) }
    }.joinToString(" · ")
}

/** Eintrag der Einkaufsliste: aus einem Angebot oder frei eingetippt. */
data class ShoppingItem(
    val id: String,
    val title: String,
    val store: String = "",
    val storeSlug: String = "",
    val price: Double? = null,
    val validFrom: String = "",
    val validTo: String = "",
    val url: String = "",
    val image: String = "",
    val done: Boolean = false,
    val addedAt: Long = 0L,
) {
    /** Abgelaufene Angebote fliegen von selbst raus; eigene Einträge (ohne Datum) bleiben. */
    fun expired(today: LocalDate = LocalDate.now()): Boolean =
        validTo.isNotBlank() && validTo < today.toString()

    companion object {
        fun fromHit(h: SavedHit, now: Long = System.currentTimeMillis()) = ShoppingItem(
            id = h.url.ifBlank { "${h.storeSlug}|${h.title}|${h.validFrom}" },
            title = h.title, store = h.store, storeSlug = h.storeSlug, price = h.price,
            validFrom = h.validFrom, validTo = h.validTo, url = h.url, image = h.image, addedAt = now,
        )
    }
}

object ShareText {
    /** „REWE: Ferrero Nutella für 1,99 € (statt 3,49 €), bis Sa., 15.11.“ + Link */
    fun offer(h: SavedHit, today: LocalDate = LocalDate.now()): String {
        val price = h.price?.let { " für ${Matcher.euro(it)}" }.orEmpty() +
            (if (h.oldPrice != null && h.price != null && h.oldPrice > h.price) " (statt ${Matcher.euro(h.oldPrice)})" else "")
        val validity = Matcher.validityText(h.validFrom, h.validTo, today).let { if (it.isNotBlank()) ", $it" else "" }
        val store = if (h.store.isNotBlank()) "${h.store}: " else ""
        return "$store${h.title}$price$validity" + (if (h.url.isNotBlank()) "\n${h.url}" else "")
    }

    /** Einkaufsliste als Text, nach Markt gruppiert. Erledigtes bleibt weg. */
    fun list(items: List<ShoppingItem>, today: LocalDate = LocalDate.now()): String {
        val open = items.filter { !it.done }
        val sb = StringBuilder("🛒 Einkaufsliste")
        open.groupBy { it.store.ifBlank { "Sonstiges" } }.toSortedMap(compareBy { it.lowercase() }).forEach { (store, list) ->
            sb.append("\n\n").append(store)
            list.forEach { i ->
                sb.append("\n• ").append(i.title)
                i.price?.let { sb.append(" – ").append(Matcher.euro(it)) }
                val v = Matcher.validityText(i.validFrom, i.validTo, today)
                if (v.isNotBlank()) sb.append(" (").append(v).append(")")
            }
        }
        return sb.toString()
    }
}

object ProductTransfer {
    /** Gleiche Produkte erkennen: Barcode, sonst Marke+Name+Größe. */
    fun sameProduct(a: Product, b: Product): Boolean =
        (a.ean.isNotBlank() && a.ean == b.ean) || a.key == b.key

    /** Welche importierten Produkte sind neu? Bekommen neue IDs, damit nichts überschrieben wird. */
    fun newOnes(existing: List<Product>, incoming: List<Product>): List<Product> {
        val result = mutableListOf<Product>()
        incoming.forEach { p ->
            if (existing.none { sameProduct(it, p) } && result.none { sameProduct(it, p) }) result += p
        }
        return result
    }
}
