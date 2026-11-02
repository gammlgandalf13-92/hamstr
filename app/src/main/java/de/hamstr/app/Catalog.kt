package de.hamstr.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Ein Produkt aus dem Katalog (Open Food Facts) – unabhängig davon, ob es gerade im Angebot ist. */
data class CatalogItem(
    val code: String,
    val name: String,
    val brand: String,
    val quantity: String,
    val image: String,
    val imageFromOffer: Boolean = false,   // Bild stammt aus einem aktuellen Prospekt
) {
    val displayName: String
        get() = if (brand.isNotBlank() && !name.startsWith(brand, ignoreCase = true)) "$brand $name" else name
}

/**
 * Open Food Facts: freie Produktdatenbank (Name, Marke, Größe, Bild).
 * Grenzen laut Doku: ca. 10 Suchen pro Minute – deshalb nur auf Knopfdruck suchen, nie beim Tippen.
 */
object Catalog {
    private const val USER_AGENT = "Hamstr/1.0 (Android; privat)"
    private const val FIELDS = "code,product_name,product_name_de,brands,quantity,image_front_small_url,selected_images,images,obsolete"

    fun search(query: String): List<CatalogItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        // 1. neuer Suchdienst, 2. ältere Suche als Rückfall
        val items = runCatching { searchNew(q) }.getOrNull().orEmpty()
            .ifEmpty { runCatching { searchLegacy(q) }.getOrElse { throw IOException("Katalog nicht erreichbar") } }
        // Nur Treffer, deren Name/Marke alle Suchwörter enthält (der Dienst sucht sehr breit)
        val words = Matcher.norm(q).split(" ").filter { it.length > 1 }
        val strict = items.filter { item -> words.all { it in Matcher.norm(item.brand + " " + item.name) } }
        return strict.ifEmpty { items }.distinctBy { Matcher.norm(it.displayName + it.quantity) }.take(25)
    }

    private fun searchNew(q: String): List<CatalogItem> {
        val url = "https://search.openfoodfacts.org/search?page_size=40&fields=$FIELDS&q=" + enc(q)
        val hits = JSONObject(get(url)).optJSONArray("hits") ?: return emptyList()
        return parse(hits)
    }

    private fun searchLegacy(q: String): List<CatalogItem> {
        val url = "https://world.openfoodfacts.org/cgi/search.pl?action=process&json=1&search_simple=1" +
            "&page_size=40&fields=$FIELDS&search_terms=" + enc(q)
        val products = JSONObject(get(url)).optJSONArray("products") ?: return emptyList()
        return parse(products)
    }

    private fun parse(arr: JSONArray): List<CatalogItem> = (0 until arr.length()).mapNotNull { i ->
        val p = arr.optJSONObject(i) ?: return@mapNotNull null
        if (p.optString("obsolete") in setOf("true", "on", "1")) return@mapNotNull null
        val name = str(p, "product_name_de").ifBlank { str(p, "product_name") }.trim()
        if (name.isBlank()) return@mapNotNull null
        val brand = when (val b = p.opt("brands")) {
            is JSONArray -> if (b.length() > 0) b.optString(0) else ""
            is String -> b.split(",").firstOrNull().orEmpty()
            else -> ""
        }.trim()
        val code = str(p, "code")
        CatalogItem(code, name, brand, str(p, "quantity").trim(), imageOf(p, code))
    }

    /** Bild: direktes Feld, sonst ausgewähltes Frontbild, sonst aus der Bild-Revision zusammengesetzt. */
    private fun imageOf(p: JSONObject, code: String): String {
        str(p, "image_front_small_url").takeIf { it.startsWith("http") }?.let { return it }
        p.optJSONObject("selected_images")?.optJSONObject("front")?.optJSONObject("small")?.let { small ->
            for (lang in listOf("de", "en")) str(small, lang).takeIf { it.startsWith("http") }?.let { return it }
            small.keys().asSequence().map { str(small, it) }.firstOrNull { it.startsWith("http") }?.let { return it }
        }
        val images = p.optJSONObject("images") ?: return ""
        val key = listOf("front_de", "front_en", "front").firstOrNull { images.has(it) }
            ?: images.keys().asSequence().firstOrNull { it.startsWith("front_") } ?: return ""
        val rev = images.optJSONObject(key)?.opt("rev")?.toString()?.takeIf { it.isNotBlank() } ?: return ""
        return "https://images.openfoodfacts.org/images/products/${folder(code)}/$key.$rev.200.jpg"
    }

    /** Ordnerstruktur von Open Food Facts: 3017620422003 → 301/762/042/2003 */
    fun folder(code: String): String =
        if (code.length <= 8) code
        else code.padStart(13, '0').let { "${it.substring(0, 3)}/${it.substring(3, 6)}/${it.substring(6, 9)}/${it.substring(9)}" }

    private fun str(o: JSONObject?, key: String): String =
        if (o == null || o.isNull(key)) "" else o.optString(key, "")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 25_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
