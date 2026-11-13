package de.hamstr.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Marktguru bündelt die Prospekte der Händler regional per PLZ.
 * Die Schnittstelle ist dieselbe, die marktguru.de im Browser nutzt – nicht offiziell dokumentiert.
 */
object Marktguru {
    private const val HOME = "https://www.marktguru.de/"
    private const val SEARCH = "https://api.marktguru.de/api/v1/offers/search"
    private const val OFFER_URL = "https://www.marktguru.de/offers/"
    private const val USER_AGENT = "Hamstr/1.0 (Android; privat, 1x taeglich)"

    // Öffentliche Schlüssel des Webclients; werden zuerst live von der Startseite gelesen.
    private const val FALLBACK_API_KEY = "8Kk+pmbf7TgJ9nVj2cXeA7P5zBGv8iuutVVMRfOfvNE="
    private const val FALLBACK_CLIENT_KEY = "WU/RH+PMGDi+gkZer3WbMelt6zcYHSTytNB7VpTia90="

    @Volatile
    private var cachedKeys: Pair<String, String>? = null

    private fun get(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Accept", "application/json, text/html")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            val code = c.responseCode
            val stream = if (code < 400) c.inputStream else c.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return code to body
        } finally {
            c.disconnect()
        }
    }

    private fun keys(): Pair<String, String> {
        cachedKeys?.let { return it }
        val live = runCatching {
            val html = get(HOME).second
            val a = Regex("\"apiKey\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            val c = Regex("\"clientKey\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            if (a != null && c != null) a to c else null
        }.getOrNull()
        return (live ?: (FALLBACK_API_KEY to FALLBACK_CLIENT_KEY)).also { cachedKeys = it }
    }

    fun search(query: String, plz: String): List<Offer> = parse(fetch(query, plz, 0, 100))

    /** Eine Seite Suchergebnisse als JSON (mit Wiederholung bei Überlast). */
    private fun fetch(query: String, plz: String, offset: Int, limit: Int): String {
        val url = SEARCH + "?as=web&limit=$limit&offset=$offset&q=" + URLEncoder.encode(query, "UTF-8") +
            "&zipCode=" + URLEncoder.encode(plz, "UTF-8")
        val (api, client) = keys()
        repeat(4) { attempt ->
            val (code, body) = get(url, mapOf("x-apikey" to api, "x-clientkey" to client))
            when (code) {
                200 -> return body
                429, 500, 502, 503, 504 -> Thread.sleep(3_000L shl attempt)
                else -> {
                    if (code == 401 || code == 403) cachedKeys = null
                    throw IOException("Marktguru HTTP $code")
                }
            }
        }
        throw IOException("Marktguru antwortet nicht")
    }

    /** Eine Seite aller Angebote eines Marktes. */
    class Page(val offers: List<Offer>, val total: Int)

    /**
     * Alle Angebote eines Marktes: Die Suche nach dem Namen des Marktes liefert dessen kompletten Prospekt
     * (z. B. „REWE“ → alle REWE-Angebote). Nur Angebote genau dieses Marktes werden behalten.
     */
    fun marketPage(name: String, slug: String, plz: String, offset: Int, limit: Int = 100): Page {
        fun load(q: String): Page {
            val body = fetch(q, plz, offset, limit)
            val total = runCatching { JSONObject(body).optInt("totalResults", 0) }.getOrDefault(0)
            val chain = Geo.chainKey(name)
            val offers = parse(body).filter {
                (slug.isNotBlank() && it.storeSlug == slug) || Geo.chainKey(it.store) == chain
            }
            return Page(offers, total)
        }
        val first = load(name)
        // Manche Märkte heißen in der Suche anders als im Angebot (z. B. „E center“) → Kurzname probieren
        if (first.total == 0 && slug.isNotBlank() && Matcher.norm(slug.replace("-", " ")) != Matcher.norm(name)) {
            return load(slug.replace("-", " "))
        }
        return first
    }

    private fun str(o: JSONObject?, key: String): String =
        if (o == null || o.isNull(key)) "" else o.optString(key, "")

    private fun num(o: JSONObject, key: String): Double? {
        if (o.isNull(key)) return null
        val v = o.optDouble(key, Double.NaN)
        return if (v.isNaN() || v == 0.0) null else v
    }

    /** Bild-URL aus der Antwort (images.urls) – sonst das feste CDN-Muster über die Angebotsnummer. */
    private fun imageUrl(r: JSONObject, id: String): String {
        val urls = r.optJSONObject("images")?.optJSONObject("urls")
        val direct = listOf("medium", "small", "large").map { str(urls, it) }.firstOrNull { it.isNotBlank() }
        if (direct != null) {
            return when {
                direct.startsWith("//") -> "https:$direct"
                direct.startsWith("/") -> "https://cdn.marktguru.de$direct"
                else -> direct
            }
        }
        val count = r.optJSONObject("images")?.optInt("count", -1) ?: -1
        if (id.isBlank() || count == 0) return ""
        return "https://cdn.marktguru.de/api/v1/offers/$id/images/default/0/small.webp"
    }

    fun parse(json: String): List<Offer> {
        val results: JSONArray = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val offers = mutableListOf<Offer>()
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val prod = r.optJSONObject("product")
            val brandName = when (val b = prod?.opt("brand") ?: r.opt("brand")) {
                is JSONObject -> str(b, "name")
                is String -> b
                else -> ""
            }
            val validity = r.optJSONArray("validityDates")?.optJSONObject(0)
            val id = r.opt("id")?.toString().orEmpty()
            val image = imageUrl(r, id)
            val advertisers = r.optJSONArray("advertisers")
            val advList = if (advertisers != null && advertisers.length() > 0)
                (0 until advertisers.length()).mapNotNull { advertisers.optJSONObject(it) }
            else listOf(JSONObject())
            for (adv in advList) {
                val name = str(adv, "name").ifBlank { "?" }
                offers += Offer(
                    id = id,
                    store = name,
                    storeSlug = str(adv, "uniqueName").ifBlank { Matcher.norm(name).replace(" ", "-") },
                    productName = str(prod, "name").ifBlank { str(r, "name") },
                    brand = brandName,
                    description = str(r, "description"),
                    price = num(r, "price"),
                    oldPrice = num(r, "oldPrice"),
                    validFrom = str(validity, "from").take(10),
                    validTo = str(validity, "to").take(10),
                    url = OFFER_URL + id,
                    imageUrl = image,
                    category = str(r.optJSONArray("categories")?.optJSONObject(0), "name"),
                )
            }
        }
        return offers
    }
}
