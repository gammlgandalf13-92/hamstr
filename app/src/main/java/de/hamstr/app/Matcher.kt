package de.hamstr.app

import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ein beobachteter Eintrag: einzelnes Produkt, Marke (names leer) oder Kategorie (mehrere names). */
data class Product(
    val id: String,
    val names: List<String> = emptyList(),   // Schreibweisen – eine davon muss passen
    val brand: String = "",
    val size: String = "",
    val exact: Boolean = false,
    val queries: List<String> = emptyList(), // eigene Suchbegriffe (alle werden abgefragt)
    val exclude: List<String> = emptyList(), // Wörter, die ein Angebot aussortieren
    val maxPrice: Double? = null,
    val stores: List<String> = emptyList(),  // leer = Märkte aus den Einstellungen
    val image: String = "",                  // lokaler Dateipfad oder URL, optional
    val imageSource: String = "",            // "offer" (Prospekt), "catalog" (Open Food Facts), "" = unbekannt
) {
    val label: String
        get() = listOf(brand, names.joinToString(" | ")).filter { it.isNotBlank() }.joinToString(" ")

    val key: String
        get() = Matcher.norm("$brand ${names.joinToString(" ")} $size")

    fun searchQueries(): List<String> {
        if (queries.isNotEmpty()) return queries.distinct()
        val first = names.firstOrNull().orEmpty()
        // Mehrwortsuche liefert bei Marktguru oft wenig → Marke+Name, Marke, Name
        return listOf("$brand $first".trim(), brand, first).filter { it.isNotBlank() }.distinct()
    }
}

data class Offer(
    val id: String,
    val store: String,
    val storeSlug: String,
    val productName: String,
    val brand: String,
    val description: String,
    val price: Double?,
    val oldPrice: Double?,
    val validFrom: String,
    val validTo: String,
    val url: String,
    val imageUrl: String = "",
) {
    val displayName: String
        get() = listOf(brand, productName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { description.take(50) }

    val rawText: String
        get() = listOf(brand, productName, description).filter { it.isNotBlank() }.joinToString(" ")
}

data class Hit(val product: Product, val offer: Offer, val confidence: Double, val notes: List<String>) {

    /** Einzelprodukt: dessen Schlüssel. Marken-Modus: jeder Artikel für sich. */
    val itemKey: String
        get() {
            if (product.names.isNotEmpty()) return product.key
            val article = Matcher.norm(offer.productName)
                .ifBlank { Matcher.norm(offer.description).take(40) }
                .ifBlank { offer.id }
            return "${product.key}|$article"
        }

    val dedupeKey: String
        get() = "${offer.storeSlug}|$itemKey|${offer.validFrom}|${offer.price}"

    fun title(): String {
        val names = product.names
        val item = when {
            names.size == 1 -> names[0]
            offer.productName.isNotBlank() -> offer.productName
            names.isNotEmpty() -> names[0]
            else -> offer.description.take(40)
        }
        return "${offer.store}: " + listOf(product.brand, item).filter { it.isNotBlank() }.joinToString(" ")
    }

    fun body(lastSeen: String? = null): String {
        val lines = mutableListOf<String>()
        val price = offer.price?.let { Matcher.euro(it) } ?: "Preis s. Prospekt"
        lines += price + (offer.oldPrice?.let { " (statt ${Matcher.euro(it)})" } ?: "")
        if (offer.description.isNotBlank()) lines += offer.description.take(140)
        if (offer.validFrom.isNotBlank() || offer.validTo.isNotBlank()) {
            lines += "Gültig ${Matcher.shortDate(offer.validFrom)}–${Matcher.shortDate(offer.validTo)}"
        }
        if (notes.isNotEmpty()) lines += "⚠ " + notes.joinToString("; ")
        if (confidence < 0.6) lines += "Unsicherer Treffer (${(confidence * 100).toInt()} %)"
        if (lastSeen != null) lines += "Zuletzt im Angebot: ${Matcher.shortDate(lastSeen)}"
        return lines.joinToString("\n")
    }
}

data class Size(val count: Int, val amount: Double, val unit: String) {
    val total: Double get() = count * amount
    override fun toString(): String {
        val a = if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toString()
        return if (count > 1) "$count×$a $unit" else "$a $unit"
    }
}

object Matcher {
    private val UNITS = mapOf(
        "g" to ("g" to 1.0), "gr" to ("g" to 1.0), "gramm" to ("g" to 1.0), "kg" to ("g" to 1000.0),
        "ml" to ("ml" to 1.0), "cl" to ("ml" to 10.0), "l" to ("ml" to 1000.0),
        "liter" to ("ml" to 1000.0), "ltr" to ("ml" to 1000.0),
        "st" to ("stk" to 1.0), "stk" to ("stk" to 1.0), "stueck" to ("stk" to 1.0), "stück" to ("stk" to 1.0),
    )
    private val SIZE_RE = Regex(
        """(?:(\d+)\s*[x×]\s*)?(\d+(?:[.,]\d+)?)\s*-?\s*(kg|gramm|gr|g|ml|cl|liter|ltr|l|stueck|stück|stk|st)(?![a-zäöü])""",
        RegexOption.IGNORE_CASE,
    )
    private val QUANTITY_RE = Regex(
        """\b\d+\s*(?:fuer|für|zum preis von)(?![a-zäöü])|\bab\s+\d+\b|\bje\b.*\bab\b""",
        RegexOption.IGNORE_CASE,
    )
    private val MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^a-z0-9,.]+")
    private val SPACES = Regex("\\s+")

    fun norm(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var t = text.lowercase(Locale.GERMAN)
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        t = MARKS.replace(Normalizer.normalize(t, Normalizer.Form.NFD), "")
        t = NON_WORD.replace(t, " ")
        return SPACES.replace(t, " ").trim()
    }

    fun parseSizes(text: String?): List<Size> =
        SIZE_RE.findAll(text.orEmpty()).map { m ->
            val count = m.groupValues[1].ifEmpty { "1" }.toInt()
            val value = m.groupValues[2].replace(",", ".").toDouble()
            val (unit, factor) = UNITS.getValue(m.groupValues[3].lowercase(Locale.GERMAN))
            Size(count, value * factor, unit)
        }.toList()

    private fun approx(a: Double, b: Double, tol: Double) = b > 0 && kotlin.math.abs(a - b) / b <= tol

    fun storeAllowed(offer: Offer, allowed: List<String>): Boolean {
        if (allowed.isEmpty()) return true
        val slug = norm(offer.storeSlug + " " + offer.store)
        return allowed.any { norm(it).isNotEmpty() && norm(it) in slug }
    }

    fun match(product: Product, offer: Offer, globalStores: List<String>, today: LocalDate): Hit? {
        if (!storeAllowed(offer, product.stores.ifEmpty { globalStores })) return null
        val todayIso = today.toString()
        if (offer.validTo.isNotEmpty() && offer.validTo < todayIso) return null // abgelaufen

        val text = norm(offer.rawText)
        val notes = mutableListOf<String>()
        var conf = 0.5

        // Name: eine der Schreibweisen muss vollständig vorkommen
        if (product.names.isNotEmpty() && product.names.none { alt ->
                norm(alt).split(" ").filter { it.length > 1 }.all { it in text }
            }) return null

        // Ausschlusswörter (nur am Wortanfang, damit "eis" nicht in "Preis" greift)
        if (product.exclude.any { x ->
                val n = norm(x)
                n.isNotEmpty() && Regex("\\b" + Regex.escape(n)).containsMatchIn(text)
            }) return null

        // Marke (nicht angegeben = nichts zu prüfen)
        if (product.brand.isNotBlank() && norm(product.brand) !in text) return null
        conf += 0.2

        // Größe
        if (product.size.isBlank()) {
            conf += 0.2
        } else {
            val wanted = parseSizes(product.size).firstOrNull()
            if (wanted != null) {
                val sameUnit = parseSizes(offer.rawText).filter { it.unit == wanted.unit }
                val exact = sameUnit.filter {
                    approx(it.total, wanted.total, 0.01) || approx(it.amount, wanted.total, 0.01)
                }
                when {
                    exact.isNotEmpty() -> {
                        conf += 0.2
                        if (exact.any { it.count > 1 && approx(it.amount, wanted.total, 0.01) }) notes += "Mehrfachpack"
                    }
                    product.exact -> return null
                    sameUnit.any { approx(it.amount, wanted.total, 0.15) } ->
                        notes += "Größe abweichend (${sameUnit.joinToString()})"
                    sameUnit.isNotEmpty() -> return null // klar andere Größe
                    else -> {
                        conf -= 0.2
                        notes += "Größe nicht im Prospekttext"
                    }
                }
            }
        }

        // Preis-Obergrenze
        val price = offer.price
        if (product.maxPrice != null && price != null && price > product.maxPrice) return null

        if (offer.oldPrice != null && price != null && offer.oldPrice > price) conf += 0.1
        if (QUANTITY_RE.containsMatchIn(offer.description)) notes += "Mengen-/Ab-Preis prüfen"
        if (offer.validFrom.isNotEmpty() && offer.validFrom > todayIso) notes += "gilt erst ab ${shortDate(offer.validFrom)}"

        return Hit(product, offer, (conf.coerceIn(0.0, 1.0) * 100).toInt() / 100.0, notes)
    }

    /** Pro (Artikel, Markt, Woche) nur der sicherste bzw. günstigste Treffer. */
    fun bestHits(hits: List<Hit>): List<Hit> =
        hits.groupBy { Triple(it.itemKey, it.offer.storeSlug, it.offer.validFrom) }
            .values.map { group ->
                group.maxWith(compareBy<Hit> { it.confidence }.thenBy { -(it.offer.price ?: 9e9) })
            }

    fun euro(v: Double): String = String.format(Locale.GERMANY, "%.2f €", v)

    fun shortDate(iso: String): String = try {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("dd.MM."))
    } catch (e: Exception) {
        iso
    }
}
