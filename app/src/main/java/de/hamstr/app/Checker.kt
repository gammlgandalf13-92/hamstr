package de.hamstr.app

import android.content.Context
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** Eine komplette Prüfung: Angebote holen, abgleichen, neue melden, Protokoll schreiben. */
object Checker {
    private const val MAX_NOTIFICATIONS = 8

    data class Summary(val products: Int, val hits: Int, val fresh: Int, val errors: Int)

    fun run(context: Context): Summary {
        val st = Store(context)
        val log = StringBuilder()
        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date())
        log.append("Prüfung vom $stamp\n")

        val plz = st.plz
        if (!Regex("\\d{5}").matches(plz)) {
            log.append("Keine gültige PLZ eingetragen – bitte in den Einstellungen ergänzen.\n")
            st.log = log.toString()
            st.lastStatus = "no_plz"
            st.saveHits(emptyList())
            st.lastRun = System.currentTimeMillis()
            return Summary(0, 0, 0, 1)
        }
        val products = st.products()
        if (products.isEmpty()) {
            log.append("Keine Produkte eingetragen.\n")
            st.log = log.toString()
            st.lastStatus = "no_products"
            st.saveHits(emptyList())
            st.lastRun = System.currentTimeMillis()
            return Summary(0, 0, 0, 0)
        }

        val today = LocalDate.now()
        val globalStores = st.stores
        val all = mutableListOf<Hit>()
        var errors = 0

        for (p in products) {
            val allowed = p.stores.ifEmpty { globalStores }
            try {
                val offers = mutableListOf<Offer>()
                val used = mutableListOf<String>()
                var hits = emptyList<Hit>()
                for (q in p.searchQueries()) {
                    val found = Marktguru.search(q, plz)
                    used += "$q (${found.size})"
                    val known = offers.map { it.id to it.storeSlug }.toSet()
                    offers += found.filter { (it.id to it.storeSlug) !in known }
                    hits = offers.mapNotNull { Matcher.match(p, it, globalStores, today) }
                    if (hits.isNotEmpty() && p.queries.isEmpty()) break // Fallback-Suche: erster Treffer genügt
                    Thread.sleep(800)
                }
                all += hits
                val inStores = offers.filter { Matcher.storeAllowed(it, allowed) }
                log.append("\n[${p.label}] Suche: ${used.joinToString()}\n")
                log.append("  ${offers.size} Angebote, ${inStores.size} bei deinen Märkten, ${hits.size} Treffer\n")
                inStores.take(8).forEach { o ->
                    val mark = if (hits.any { it.offer === o }) "✓" else "✗"
                    val price = o.price?.let { Matcher.euro(it) } ?: "-"
                    log.append("  $mark ${o.store}  $price  ${o.rawText.take(60)}\n")
                }
                if (offers.isNotEmpty() && inStores.isEmpty()) {
                    log.append("  andere Märkte: ${offers.map { it.store }.distinct().sorted().joinToString().take(200)}\n")
                }
            } catch (e: Exception) {
                errors++
                log.append("\n[${p.label}] Fehler: ${e.message ?: e.javaClass.simpleName}\n")
            }
            Thread.sleep(1000)
        }

        val best = Matcher.bestHits(all).sortedWith(compareBy<Hit>({ -it.confidence }, { it.offer.store }))
        st.saveHits(best)
        upgradeImages(context, st, products, best, log)

        val seen = st.seen()
        val history = st.history()
        val fresh = best.filter { it.dedupeKey !in seen }
        val canNotify = Notifier.canNotify(context)

        fresh.forEachIndexed { i, h ->
            val histKey = "${h.itemKey}|${h.offer.storeSlug}"
            val prior = history[histKey].orEmpty().filter { it < h.offer.validFrom }.maxOrNull()
            if (!canNotify) return@forEachIndexed // nicht merken → nach Freigabe erneut melden
            if (i < MAX_NOTIFICATIONS) Notifier.hit(context, h, prior)
            seen[h.dedupeKey] = h.offer.validTo
            history[histKey] = (history[histKey].orEmpty() + h.offer.validFrom).distinct().sorted().takeLast(20)
        }
        if (canNotify && fresh.size > MAX_NOTIFICATIONS) {
            Notifier.info(context, "Noch ${fresh.size - MAX_NOTIFICATIONS} weitere Treffer", "In Hamstr unter „Ergebnisse“.")
        }
        if (errors == products.size) {
            Notifier.info(context, "Hamstr: Abruf fehlgeschlagen",
                "Keine Angebote abrufbar – evtl. hat Marktguru die Schnittstelle geändert.")
        }

        // Alte Einträge aufräumen
        val cutoff = today.minusDays(45).toString()
        st.saveSeen(seen.filterValues { it.isEmpty() || it >= cutoff })
        st.saveHistory(history)

        log.append("\n${best.size} Treffer gesamt, ${fresh.size} neu")
        if (!canNotify && fresh.isNotEmpty()) log.append(" (Benachrichtigungen sind ausgeschaltet!)")
        if (errors > 0) log.append(", $errors Fehler")
        log.append(".\n")
        st.log = log.toString()
        st.lastStatus = when {
            errors == products.size -> "failed"
            errors > 0 -> "partial"
            else -> "ok"
        }
        st.lastRun = System.currentTimeMillis()
        checkForUpdate(context, st)
        return Summary(products.size, best.size, fresh.size, errors)
    }

    /**
     * Welche Produkte bekommen ein Prospektbild? Ersetzt werden fehlende Bilder und Katalogfotos
     * (und einmalig Bilder unbekannter Herkunft) – nie Prospektbilder. Nur bei sicheren Treffern.
     * Marken-Einträge (ohne Namen) bekommen nur ein Bild, wenn sie noch keins haben.
     */
    internal fun imageUpgrades(products: List<Product>, hits: List<Hit>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (p in products) {
            val upgradable = when {
                p.image.isBlank() -> true
                p.imageSource == "own" -> false      // eigenes Foto nie ersetzen
                p.names.isEmpty() -> false
                else -> p.imageSource != "offer"
            }
            if (!upgradable) continue
            val hit = hits.filter { it.product.id == p.id && it.offer.imageUrl.isNotBlank() && it.confidence >= 0.7 }
                .maxByOrNull { it.confidence } ?: continue
            result[p.id] = hit.offer.imageUrl
        }
        return result
    }

    private fun upgradeImages(context: Context, st: Store, products: List<Product>, hits: List<Hit>, log: StringBuilder) {
        val upgrades = imageUpgrades(products, hits)
        if (upgrades.isEmpty()) return
        val updated = products.map { p ->
            val url = upgrades[p.id] ?: return@map p
            val local = Images.download(context, url, p.id) ?: return@map p
            log.append("\nBild aus dem Prospekt übernommen: ${p.label}")
            p.copy(image = local, imageSource = "offer")
        }
        // Frisch laden, damit gleichzeitige Änderungen in der App nicht verloren gehen
        val current = st.products().associateBy { it.id }
        val byId = updated.associateBy { it.id }
        st.saveProducts(current.values.map { c ->
            val u = byId[c.id]
            if (u != null && u.imageSource == "offer" && c.imageSource != "offer") c.copy(image = u.image, imageSource = "offer") else c
        })
    }

    /** Neue App-Version? Einmal pro Version melden. Fehler hier stören den Angebots-Check nicht. */
    @Suppress("UNUSED_PARAMETER")
    private fun checkForUpdate(context: Context, st: Store) = UpdateNotifications.check(context)
}
