package de.hamstr.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Gespeicherter Treffer für die Anzeige in der App. */
data class SavedHit(
    val title: String,               // Artikelname ohne Markt
    val body: String,
    val url: String,
    val validTo: String,
    val image: String = "",
    val store: String = "",          // z. B. "ALDI SÜD"
    val storeSlug: String = "",      // z. B. "aldi-sued" – für die Marktseite bei Marktguru
    val productId: String = "",
    val price: Double? = null,
    val oldPrice: Double? = null,
    val validFrom: String = "",
    val description: String = "",
    val notes: String = "",          // Hinweise wie „Größe abweichend“, „Unsicherer Treffer“
) {
    /** Alle aktuellen Angebote dieses Marktes bei Marktguru. */
    val storeUrl: String
        get() = if (storeSlug.isNotBlank()) "https://www.marktguru.de/r/$storeSlug" else ""
}

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

    /** Prüfzeiten. Ohne Eintrag: wie bisher täglich zur alten Uhrzeit (Viertel nach). */
    var schedule: List<CheckSlot>
        get() {
            val raw = prefs.getString("schedule", null) ?: return listOf(CheckSlot(Schedule.ALL_DAYS, hour, 15))
            val arr = JSONArray(raw)
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(CheckSlot::fromJson) }
                .ifEmpty { listOf(CheckSlot(Schedule.ALL_DAYS, hour, 15)) }
        }
        set(v) = prefs.edit().putString("schedule", JSONArray(v.map { it.toJson() }).toString()).apply()

    /** Version, für die schon eine Update-Benachrichtigung kam. */
    var updateNotified: Int
        get() = prefs.getInt("updateNotified", 0)
        set(v) = prefs.edit().putInt("updateNotified", v).apply()

    var updateNotifiedAt: Long
        get() = prefs.getLong("updateNotifiedAt", 0L)
        set(v) = prefs.edit().putLong("updateNotifiedAt", v).apply()

    /** Version, für die schon die Erinnerung kam. */
    var updateReminded: Int
        get() = prefs.getInt("updateReminded", 0)
        set(v) = prefs.edit().putInt("updateReminded", v).apply()

    /** Ketten der Märkteliste (eigene + aus der Umkreissuche) mit Entfernung. */
    fun chains(): Map<String, ChainInfo> {
        val raw = prefs.getString("chains", null) ?: return emptyMap()
        val o = JSONObject(raw)
        return o.keys().asSequence().associateWith { k ->
            val c = o.getJSONObject(k)
            ChainInfo(
                label = c.optString("label"),
                distanceKm = if (c.has("km")) c.optDouble("km") else null,
                count = c.optInt("count"),
                address = c.optString("address"),
            )
        }
    }

    fun saveChains(map: Map<String, ChainInfo>) {
        val o = JSONObject()
        map.forEach { (k, c) ->
            o.put(k, JSONObject().put("label", c.label).put("count", c.count).put("address", c.address)
                .apply { c.distanceKm?.let { put("km", it) } })
        }
        prefs.edit().putString("chains", o.toString()).apply()
    }

    /** Stammt der Mittelpunkt vom Standort (true) oder von der PLZ (false)? */
    var centerIsLocation: Boolean
        get() = prefs.getBoolean("centerIsLocation", false)
        set(v) = prefs.edit().putBoolean("centerIsLocation", v).apply()

    var radiusKm: Int
        get() = prefs.getInt("radiusKm", 5)
        set(v) = prefs.edit().putInt("radiusKm", v.coerceIn(1, 25)).apply()

    /** Mittelpunkt für Karte und Umkreis (aus Standort oder PLZ) – samt der PLZ, zu der er gehört. */
    var center: Triple<Double, Double, String>?
        get() {
            val lat = prefs.getString("centerLat", null)?.toDoubleOrNull() ?: return null
            val lon = prefs.getString("centerLon", null)?.toDoubleOrNull() ?: return null
            return Triple(lat, lon, prefs.getString("centerPlz", "").orEmpty())
        }
        set(v) {
            val e = prefs.edit()
            if (v == null) e.remove("centerLat").remove("centerLon").remove("centerPlz")
            else e.putString("centerLat", v.first.toString()).putString("centerLon", v.second.toString())
                .putString("centerPlz", v.third)
            e.apply()
        }

    // ---------------- Produkte ----------------
    fun products(): List<Product> {
        // Neue Installation: leere Liste – jeder legt seine eigenen Produkte an
        val raw = prefs.getString("products", null) ?: return emptyList()
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
                var title = it.optString("title")
                var store = it.optString("store")
                // Ältere Einträge: Markt steckt noch im Titel ("REWE: Nutella")
                if (store.isBlank() && title.contains(": ")) {
                    store = title.substringBefore(": ")
                    title = title.substringAfter(": ")
                }
                SavedHit(
                    title, it.optString("body"), it.optString("url"),
                    it.optString("validTo"), it.optString("image"), store, it.optString("storeSlug"),
                    productId = it.optString("productId"),
                    price = if (it.has("price")) it.optDouble("price") else null,
                    oldPrice = if (it.has("oldPrice")) it.optDouble("oldPrice") else null,
                    validFrom = it.optString("validFrom"),
                    description = it.optString("description"),
                    notes = it.optString("notes"),
                )
            }
        }
    }

    fun saveHits(hits: List<Hit>) {
        val arr = JSONArray()
        hits.forEach {
            arr.put(JSONObject().put("title", it.itemTitle()).put("body", it.body())
                .put("store", it.offer.store).put("storeSlug", it.offer.storeSlug)
                .put("url", it.offer.url).put("validTo", it.offer.validTo)
                .put("image", it.offer.imageUrl.ifBlank { it.product.image })
                .put("productId", it.product.id).put("validFrom", it.offer.validFrom)
                .put("description", it.offer.description.take(140)).put("notes", it.hintText())
                .apply {
                    it.offer.price?.let { v -> put("price", v) }
                    it.offer.oldPrice?.let { v -> put("oldPrice", v) }
                })
        }
        prefs.edit().putString("hits", arr.toString()).apply()
    }

    // ---------------- Am Markt erinnern (Test) ----------------
    var atStoreEnabled: Boolean
        get() = prefs.getBoolean("atStoreEnabled", false)
        set(v) = prefs.edit().putBoolean("atStoreEnabled", v).apply()

    var atStoreStatus: String
        get() = prefs.getString("atStoreStatus", "").orEmpty()
        set(v) = prefs.edit().putString("atStoreStatus", v).apply()

    /** Filialen aus der letzten Umkreissuche (für die Markt-Erinnerung). */
    fun nearbyStores(): List<NearbyStore> {
        val arr = JSONArray(prefs.getString("nearbyStores", "[]"))
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                NearbyStore(it.optString("name"), it.optString("chain"), it.optDouble("lat"), it.optDouble("lon"),
                    it.optDouble("km"), it.optString("address"))
            }
        }
    }

    fun saveNearbyStores(list: List<NearbyStore>) {
        val arr = JSONArray()
        list.take(300).forEach {
            arr.put(JSONObject().put("name", it.name).put("chain", it.chain).put("lat", it.lat).put("lon", it.lon)
                .put("km", it.distanceKm).put("address", it.address))
        }
        prefs.edit().putString("nearbyStores", arr.toString()).apply()
    }

    /** Zu welcher Suche (Ort + Umkreis) gehören die gespeicherten Filialen, und wann wurde gesucht? */
    var nearbyQuery: String
        get() = prefs.getString("nearbyQuery", "").orEmpty()
        set(v) = prefs.edit().putString("nearbyQuery", v).putLong("nearbyQueryAt", System.currentTimeMillis()).apply()

    val nearbyQueryAt: Long
        get() = prefs.getLong("nearbyQueryAt", 0L)

    /** Wann zuletzt je Kette erinnert (gegen Mehrfach-Meldungen). */
    fun atStoreLast(): Map<String, Long> = mapFrom(prefs.getString("atStoreLast", null)) { (it as Number).toLong() }

    fun saveAtStoreLast(map: Map<String, Long>) =
        prefs.edit().putString("atStoreLast", JSONObject(map).toString()).apply()

    // ---------------- Preisverlauf (eigene Beobachtungen) ----------------
    fun priceLog(): Map<String, List<PricePoint>> {
        val raw = prefs.getString("priceLog", null) ?: return emptyMap()
        val o = JSONObject(raw)
        return o.keys().asSequence().associateWith { k ->
            val a = o.getJSONArray(k)
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let {
                    PricePoint(
                        store = it.optString("s"), storeSlug = it.optString("ss"),
                        price = if (it.has("p")) it.optDouble("p") else null,
                        validFrom = it.optString("f"), validTo = it.optString("t"), title = it.optString("n"),
                    )
                }
            }
        }
    }

    /** Gefundene Angebote je Produkt merken. */
    @Synchronized
    fun recordPrices(hits: List<Hit>) {
        if (hits.isEmpty()) return
        val log = priceLog().toMutableMap()
        hits.groupBy { it.product.id }.forEach { (id, list) ->
            val points = list.map {
                PricePoint(it.offer.store, it.offer.storeSlug, it.offer.price, it.offer.validFrom, it.offer.validTo, it.itemTitle())
            }
            log[id] = PriceHistory.merge(log[id].orEmpty(), points)
        }
        val o = JSONObject()
        log.forEach { (id, points) ->
            o.put(id, JSONArray(points.map { pt ->
                JSONObject().put("s", pt.store).put("ss", pt.storeSlug).put("f", pt.validFrom).put("t", pt.validTo)
                    .put("n", pt.title).apply { pt.price?.let { put("p", it) } }
            }))
        }
        prefs.edit().putString("priceLog", o.toString()).apply()
    }

    // ---------------- Einkaufsliste ----------------
    fun shoppingList(): List<ShoppingItem> {
        val arr = JSONArray(prefs.getString("shopping", "[]"))
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                ShoppingItem(
                    id = it.optString("id"), title = it.optString("title"), store = it.optString("store"),
                    storeSlug = it.optString("storeSlug"),
                    price = if (it.has("price")) it.optDouble("price") else null,
                    validFrom = it.optString("validFrom"), validTo = it.optString("validTo"),
                    url = it.optString("url"), image = it.optString("image"),
                    done = it.optBoolean("done"), addedAt = it.optLong("addedAt"),
                )
            }
        }
    }

    fun saveShoppingList(list: List<ShoppingItem>) {
        val arr = JSONArray()
        list.forEach { i ->
            arr.put(JSONObject().put("id", i.id).put("title", i.title).put("store", i.store).put("storeSlug", i.storeSlug)
                .put("validFrom", i.validFrom).put("validTo", i.validTo).put("url", i.url).put("image", i.image)
                .put("done", i.done).put("addedAt", i.addedAt).apply { i.price?.let { put("price", it) } })
        }
        prefs.edit().putString("shopping", arr.toString()).apply()
    }

    // ---------------- Vergleichspreise (Open Prices) ----------------
    fun refPrices(): Map<String, RefEntry> {
        val raw = prefs.getString("refPrices", null) ?: return emptyMap()
        val o = JSONObject(raw)
        return o.keys().asSequence().associateWith { k ->
            val e = o.getJSONObject(k)
            val ref = if (e.has("p")) RefPrice(
                price = e.optDouble("p"), date = e.optString("d"), shop = e.optString("s"),
                city = e.optString("c"), country = e.optString("cc"),
            ) else null
            RefEntry(ref, e.optLong("t"))
        }
    }

    @Synchronized
    fun saveRefPrice(ean: String, ref: RefPrice?, now: Long = System.currentTimeMillis()) {
        val o = JSONObject(prefs.getString("refPrices", null) ?: "{}")
        val e = JSONObject().put("t", now)
        if (ref != null) e.put("p", ref.price).put("d", ref.date).put("s", ref.shop).put("c", ref.city).put("cc", ref.country)
        o.put(ean, e)
        prefs.edit().putString("refPrices", o.toString()).apply()
    }

    var log: String
        get() = prefs.getString("log", "").orEmpty()
        set(v) = prefs.edit().putString("log", v).apply()

    /** Ergebnis der letzten Prüfung für die Anzeige: ok, no_plz, no_products, failed, partial */
    var lastStatus: String
        get() = prefs.getString("lastStatus", "").orEmpty()
        set(v) = prefs.edit().putString("lastStatus", v).apply()

    /** Lieblingsmärkte (Kurznamen wie "rewe", "aldi") – stehen in den Ergebnissen oben. */
    var favoriteChains: Set<String>
        get() = prefs.getStringSet("favoriteChains", emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet("favoriteChains", v.toSet()).apply()

    fun toggleFavorite(chain: String): Set<String> {
        val next = if (chain in favoriteChains) favoriteChains - chain else favoriteChains + chain
        favoriteChains = next
        return next
    }

    /** Prüfprotokoll auf der Seite „Ergebnisse“ zeigen (nur für die Fehlersuche). */
    var showLog: Boolean
        get() = prefs.getBoolean("showLog", false)
        set(v) = prefs.edit().putBoolean("showLog", v).apply()

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

    internal fun productTo(p: Product): JSONObject = JSONObject()
        .put("id", p.id)
        .put("names", JSONArray(p.names))
        .put("brand", p.brand)
        .put("size", p.size)
        .put("exact", p.exact)
        .put("queries", JSONArray(p.queries))
        .put("exclude", JSONArray(p.exclude))
        .put("stores", JSONArray(p.stores))
        .put("image", p.image)
        .put("imageSource", p.imageSource)
        .put("ean", p.ean)
        .apply { p.maxPrice?.let { put("maxPrice", it) } }

    internal fun productFrom(o: JSONObject): Product {
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
            imageSource = o.optString("imageSource"),
            ean = o.optString("ean"),
        )
    }

    companion object {
        val DEFAULT_STORES = listOf("rewe", "edeka", "lidl", "aldi")

        fun splitList(text: String, sep: String): List<String> =
            text.split(sep).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
