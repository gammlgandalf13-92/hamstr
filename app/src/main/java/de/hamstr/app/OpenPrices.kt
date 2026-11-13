package de.hamstr.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Ein von der Community gemeldeter Preis (Open Prices, Open Food Facts). */
data class RefPrice(
    val price: Double,
    val date: String,          // ISO, z. B. 2026-03-14
    val shop: String = "",     // z. B. "EDEKA Müller"
    val city: String = "",
    val discounted: Boolean = false,
    val country: String = "",  // ISO-Code, z. B. "DE"
) {
    /** "EDEKA Müller, Hamburg · 03/2026" */
    fun source(): String {
        val where = listOf(shop, city).filter { it.isNotBlank() }.distinct().joinToString(", ")
        val month = runCatching { LocalDate.parse(date).let { "%02d/%d".format(it.monthValue, it.year) } }.getOrDefault("")
        return listOf(where, month).filter { it.isNotBlank() }.joinToString(" · ")
    }
}

/** Zwischengespeicherter Vergleichspreis: ref == null heißt „nichts gefunden“. */
data class RefEntry(val ref: RefPrice?, val checkedAt: Long)

/**
 * Vergleichspreise aus Open Prices (prices.openfoodfacts.org, Daten unter ODbL).
 * Nur per Barcode – andere Produkte lassen sich dort nicht eindeutig zuordnen.
 * Lesen braucht kein Konto. Wir fragen sparsam: höchstens einmal pro Woche und Produkt.
 */
object OpenPrices {
    private const val USER_AGENT = "Hamstr/1.0 (Android; privat)"
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** Alle Euro-Preise zu einem Barcode, neueste zuerst. Blockierend. */
    fun fetch(ean: String): List<RefPrice> {
        val url = "https://prices.openfoodfacts.org/api/v1/prices?product_code=${URLEncoder.encode(ean, "UTF-8")}" +
            "&currency=EUR&duplicate_of__isnull=true&order_by=-date&size=50"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 25_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Accept", "application/json")
        try {
            val status = c.responseCode
            if (status != 200) throw IOException("Open Prices: HTTP $status")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).mapNotNull { i ->
                val o = items.optJSONObject(i) ?: return@mapNotNull null
                if (o.optString("currency", "EUR") != "EUR") return@mapNotNull null
                // Preise pro kg/Stück (Obst, Gemüse) passen nicht zu einem Barcode-Produkt
                val per = o.optString("price_per", "")
                if (per.isNotBlank() && per != "null" && per != "UNIT") return@mapNotNull null
                val price = o.optDouble("price", Double.NaN)
                val date = o.optString("date", "")
                if (price.isNaN() || price <= 0 || date.isBlank() || date == "null") return@mapNotNull null
                val loc = o.optJSONObject("location")
                fun s(key: String) = loc?.optString(key, "")?.takeIf { it != "null" }.orEmpty()
                RefPrice(
                    price = price,
                    date = date,
                    shop = s("osm_name").ifBlank { s("osm_brand") }.ifBlank { s("website_url").removePrefix("https://").removePrefix("www.") },
                    city = s("osm_address_city"),
                    discounted = o.optBoolean("price_is_discounted", false),
                    country = s("osm_address_country_code").uppercase(),
                )
            }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Der Preis, den wir als „Normalpreis“ zeigen: der neueste nicht reduzierte Preis
     * der letzten 12 Monate – deutsche Märkte bevorzugt. Ältere Preise (bis 2 Jahre) nur als Rückfall.
     */
    fun pick(prices: List<RefPrice>, today: LocalDate = LocalDate.now()): RefPrice? {
        fun ageDays(p: RefPrice): Long =
            runCatching { ChronoUnit.DAYS.between(LocalDate.parse(p.date), today) }.getOrDefault(Long.MAX_VALUE)
        val regular = prices.filter { !it.discounted && ageDays(it) in 0..730 }
        val sorted = regular.sortedWith(compareBy<RefPrice>({ ageDays(it) > 365 }, { it.country.isNotBlank() && it.country != "DE" }, { ageDays(it) }))
        return sorted.firstOrNull()
    }

    fun needsRefresh(entry: RefEntry?, now: Long = System.currentTimeMillis()): Boolean =
        entry == null || now - entry.checkedAt > MAX_AGE_MS

    /** Veraltete Vergleichspreise für diese Barcodes neu holen. Fehler werden still übergangen. */
    fun refresh(store: Store, eans: Collection<String>, force: Boolean = false): Boolean {
        val cache = store.refPrices()
        var changed = false
        eans.filter { it.isNotBlank() }.distinct().forEach { ean ->
            if (!force && !needsRefresh(cache[ean])) return@forEach
            try {
                store.saveRefPrice(ean, pick(fetch(ean)))
                changed = true
            } catch (e: Exception) {
                // Nicht erreichbar → beim nächsten Mal erneut versuchen
            }
            Thread.sleep(400)
        }
        return changed
    }

    /** Wie viel Prozent unter dem Vergleichspreis? null, wenn nicht günstiger. */
    fun savingPercent(price: Double?, ref: RefPrice?): Int? {
        if (price == null || ref == null || ref.price <= price) return null
        val pct = ((ref.price - price) / ref.price * 100).toInt()
        return pct.takeIf { it >= 1 }
    }
}
