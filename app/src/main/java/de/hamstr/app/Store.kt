package de.hamstr.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Gespeicherter Treffer für die Anzeige in der App. */
data class SavedHit(
    val title: String,
    val body: String,
    val url: String,
    val validTo: String,
    val image: String = "",
)

/** Alles, was Hamstr sich merkt – lokal auf dem Handy (SharedPreferences als JSON). */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hamstr", Context.MODE_PRIVATE)

    // ---------------- Einstellungen ----------------
    var plz: String
        get() = prefs.getString("plz", "").orEmpty()
        set(v) = prefs.edit().putString("plz", v.trim()).apply()

    var stores: List<String>
        get() = prefs.getString("stores", null)?.let { splitList(it, ",") } ?: DEFAULT_STORES
        set(v) = prefs.edit().putString("stores", v.joinToString(",")).apply()

    var hour: Int
        get() = prefs.getInt("hour", 7)
        set(v) = prefs.edit().putInt("hour", v.coerceIn(0, 23)).apply()

    // ---------------- Produkte ----------------
    fun products(): List<Product> {
        val raw = prefs.getString("products", null)
        if (raw == null) {
            saveProducts(EXAMPLES)
            return EXAMPLES
        }
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::productFrom) }
    }

    fun saveProducts(list: List<Product>) {
        val arr = JSONArray()
        list.forEach { arr.put(productTo(it)) }
        prefs.edit().putString("products", arr.toString()).apply()
    }

    // ---------------- Zustand ----------------
    fun seen(): MutableMap<String, String> = mapFrom(prefs.getString("seen", null)) { it as String }

    fun saveSeen(map: Map<String, String>) =
        prefs.edit().putString("seen", JSONObject(map).toString()).apply()

    fun history(): MutableMap<String, List<String>> = mapFrom(prefs.getString("history", null)) { v ->
        val a = v as JSONArray
        (0 until a.length()).map { a.getString(it) }
    }

    fun saveHistory(map: Map<String, List<String>>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        prefs.edit().putString("history", o.toString()).apply()
    }

    fun resetSeen() = prefs.edit().remove("seen").apply()

    fun hits(): List<SavedHit> {
        val arr = JSONArray(prefs.getString("hits", "[]"))
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                SavedHit(it.optString("title"), it.optString("body"), it.optString("url"),
                    it.optString("validTo"), it.optString("image"))
            }
        }
    }

    fun saveHits(hits: List<Hit>) {
        val arr = JSONArray()
        hits.forEach {
            arr.put(JSONObject().put("title", it.title()).put("body", it.body())
                .put("url", it.offer.url).put("validTo", it.offer.validTo)
                .put("image", it.offer.imageUrl.ifBlank { it.product.image }))
        }
        prefs.edit().putString("hits", arr.toString()).apply()
    }

    var log: String
        get() = prefs.getString("log", "").orEmpty()
        set(v) = prefs.edit().putString("log", v).apply()

    var lastRun: Long
        get() = prefs.getLong("lastRun", 0L)
        set(v) = prefs.edit().putLong("lastRun", v).apply()

    // ---------------- JSON-Helfer ----------------
    private fun <T> mapFrom(raw: String?, conv: (Any) -> T): MutableMap<String, T> {
        val out = mutableMapOf<String, T>()
        if (raw.isNullOrBlank()) return out
        val o = JSONObject(raw)
        o.keys().forEach { k -> out[k] = conv(o.get(k)) }
        return out
    }

    private fun productTo(p: Product): JSONObject = JSONObject()
        .put("id", p.id)
        .put("names", JSONArray(p.names))
        .put("brand", p.brand)
        .put("size", p.size)
        .put("exact", p.exact)
        .put("queries", JSONArray(p.queries))
        .put("exclude", JSONArray(p.exclude))
        .put("stores", JSONArray(p.stores))
        .put("image", p.image)
        .apply { p.maxPrice?.let { put("maxPrice", it) } }

    private fun productFrom(o: JSONObject): Product {
        fun list(key: String): List<String> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }
        }
        return Product(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            names = list("names"),
            brand = o.optString("brand"),
            size = o.optString("size"),
            exact = o.optBoolean("exact", false),
            queries = list("queries"),
            exclude = list("exclude"),
            maxPrice = if (o.has("maxPrice")) o.optDouble("maxPrice") else null,
            stores = list("stores"),
            image = o.optString("image"),
        )
    }

    companion object {
        val DEFAULT_STORES = listOf("rewe", "edeka", "lidl", "aldi")

        fun splitList(text: String, sep: String): List<String> =
            text.split(sep).map { it.trim() }.filter { it.isNotEmpty() }

        val EXAMPLES = listOf(
            Product(id = UUID.randomUUID().toString(), brand = "Planted"),
            Product(
                id = UUID.randomUUID().toString(),
                names = listOf("Beerenmisch", "Beerenmix", "Beeren Mix", "Waldbeeren", "Beeren Mischung"),
                queries = listOf("Beerenmischung", "Beerenmix", "Waldbeeren"),
                exclude = listOf("Joghurt", "Eis", "Smoothie", "Müsli", "Saft", "Konfitüre", "Kuchen", "Tee", "Quark"),
            ),
        )
    }
}
