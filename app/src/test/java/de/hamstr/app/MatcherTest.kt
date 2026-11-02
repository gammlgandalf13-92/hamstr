package de.hamstr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MatcherTest {
    private val today = LocalDate.of(2026, 11, 2)
    private val stores = listOf("rewe", "edeka", "lidl", "aldi")

    private fun offer(
        id: String, store: String, slug: String, brand: String, name: String, desc: String,
        price: Double?, old: Double? = null, from: String = "2026-11-02", to: String = "2026-11-07",
    ) = Offer(id, store, slug, name, brand, desc, price, old, from, to, "https://www.marktguru.de/offers/$id")

    private fun hit(p: Product, o: Offer) = Matcher.match(p, o, stores, today)

    private val nutella = Product("1", names = listOf("Nutella"), brand = "Ferrero", size = "450 g")

    @Test fun sizes() {
        assertEquals(500.0, Matcher.parseSizes("0,5 kg")[0].total, 0.001)
        assertEquals(400.0, Matcher.parseSizes("je 400-g-Glas")[0].total, 0.001)
        val s = Matcher.parseSizes("20 x 0,5-l-Fl.")[0]
        assertEquals(20, s.count); assertEquals(10000.0, s.total, 0.001)
        assertTrue(Matcher.parseSizes("500 lose Ware").isEmpty())
    }

    @Test fun norm() {
        assertEquals("aldi sued", Matcher.norm("ALDI SÜD"))
        assertEquals("konfituere muesli", Matcher.norm("Konfitüre Müsli"))
    }

    @Test fun exactSizeInKg() {
        val h = hit(nutella, offer("1", "REWE", "rewe", "Ferrero", "Nutella", "je 0,45-kg-Glas", 2.99, 4.49))
        assertNotNull(h); assertTrue(h!!.confidence >= 0.9)
    }

    @Test fun storesFiltered() {
        assertNull(hit(nutella, offer("2", "Kaufland", "kaufland", "Ferrero", "Nutella", "450 g", 2.99)))
        assertNotNull(hit(nutella, offer("3", "ALDI SÜD", "aldi-sued", "Ferrero", "Nutella", "450 g", 2.99)))
    }

    @Test fun sizeTolerance() {
        assertNull(hit(nutella, offer("4", "Lidl", "lidl", "Ferrero", "Nutella", "je 1-kg-Glas", 5.99)))
        val o = offer("5", "EDEKA", "edeka", "Ferrero", "Nutella", "je 400-g-Glas", 2.79)
        assertTrue(hit(nutella, o)!!.notes.any { it.startsWith("Größe abweichend") })
        assertNull(hit(nutella.copy(exact = true), o))
    }

    @Test fun multipackAndQuantity() {
        val pils = Product("p", names = listOf("Pils"), brand = "Krombacher", size = "20 x 0,5 l", exact = true)
        assertNotNull(hit(pils, offer("7", "REWE", "rewe", "Krombacher", "Pils", "20 x 0,5-l-Fl.", 10.99)))
        assertNull(hit(pils, offer("8", "REWE", "rewe", "Krombacher", "Pils", "6 x 0,33-l-Fl.", 4.99)))
        val butter = Product("b", names = listOf("Butter"), brand = "Kerrygold", size = "250 g")
        val h = hit(butter, offer("9", "EDEKA", "edeka", "Kerrygold", "Butter", "2 für 4,00 € je 250-g-Pckg.", 2.0))
        assertTrue(h!!.notes.contains("Mengen-/Ab-Preis prüfen"))
    }

    @Test fun expiredAndFuture() {
        assertNull(hit(nutella, offer("12", "REWE", "rewe", "Ferrero", "Nutella", "450 g", 2.99,
            from = "2026-10-20", to = "2026-10-25")))
        val h = hit(nutella, offer("13", "REWE", "rewe", "Ferrero", "Nutella", "450 g", 2.99,
            from = "2026-11-09", to = "2026-11-14"))
        assertTrue(h!!.notes.contains("gilt erst ab 09.11."))
    }

    @Test fun brandMode() {
        val p = Product("pl", brand = "Planted")
        assertEquals(listOf("Planted"), p.searchQueries())
        val a = hit(p, offer("1", "REWE", "rewe", "Planted", "planted.chicken Nature", "280 g", 3.49))!!
        val b = hit(p, offer("2", "REWE", "rewe", "Planted", "planted.kebab", "200 g", 3.49))!!
        assertNull(hit(p, offer("3", "REWE", "rewe", "Like Meat", "Like Chicken", "180 g", 2.49)))
        assertNotEquals(a.dedupeKey, b.dedupeKey)
        assertEquals(2, Matcher.bestHits(listOf(a, b)).size)
        assertEquals("REWE: Planted planted.chicken Nature", a.title())
    }

    @Test fun categoryWithExclusions() {
        val beeren = Store.EXAMPLES[1]
        for (d in listOf("Beerenmischung tiefgefroren 750 g", "TK-Beeren-Mix 1 kg", "Waldbeeren 500 g")) {
            assertNotNull(d, hit(beeren, offer("1", "Lidl", "lidl", "Freshona", "", d, 2.99)))
        }
        for (d in listOf("Joghurt Beerenmix 4 x 125 g", "Beeren-Mix Eis 900 ml", "Müsli Beerenmix 500 g")) {
            assertNull(d, hit(beeren, offer("2", "REWE", "rewe", "", "", d, 1.99)))
        }
        assertNotNull(hit(beeren, offer("3", "ALDI SÜD", "aldi-sued", "", "", "Beerenmischung 1 kg, Aktionspreis", 3.49)))
    }

    @Test fun chainKeys() {
        assertEquals("aldi", Geo.chainKey("ALDI SÜD"))
        assertEquals("aldi", Geo.chainKey("Aldi Nord"))
        assertEquals("edeka", Geo.chainKey("E center"))
        assertEquals("edeka", Geo.chainKey("EDEKA Müller"))
        assertEquals("netto", Geo.chainKey("Netto Marken-Discount"))
        assertEquals("rewe", Geo.chainKey("REWE City"))
        assertEquals("norma", Geo.chainKey("Norma"))
        // Bad Kissingen → Schweinfurt ≈ 20 km
        assertEquals(20.0, Geo.distanceKm(50.2, 10.07, 50.05, 10.23), 2.0)
        // Ketten-Kurznamen passen zum Märkte-Filter
        assertTrue(Matcher.storeAllowed(
            Offer("1", "ALDI SÜD", "aldi-sued", "", "", "", 1.0, null, "", "", ""), listOf(Geo.chainKey("ALDI SÜD"))))
    }

    @Test fun catalogHelpers() {
        assertEquals("301/762/042/2003", Catalog.folder("3017620422003"))
        assertEquals("12345678", Catalog.folder("12345678"))
        assertEquals("500 ml", sizeText("500 ml"))
        assertEquals("1,5 l", sizeText("1500 ml"))
        assertEquals("6 x 330 ml", sizeText("6 x 0,33 l"))
        assertEquals("450 g", sizeText("450g"))
        assertEquals("", sizeText("eine Packung"))
        assertEquals("Energy Ultra", nameWithoutBrand("Monster Energy Ultra", "Monster"))
        assertEquals("Nutella", nameWithoutBrand("Ferrero Nutella", "Ferrero"))
        assertEquals("Nutella", nameWithoutBrand("Nutella", "Ferrero"))
    }

    @Test fun imageUpgrades() {
        fun o(img: String) = Offer("1", "REWE", "rewe", "Ferrero", "Nutella", "450 g", 2.99, null, "", "", "", img)
        val catalog = nutella.copy(id = "c", image = "/x/c.img", imageSource = "catalog")
        val offerImg = nutella.copy(id = "o", image = "/x/o.img", imageSource = "offer")
        val noImg = nutella.copy(id = "n")
        val legacy = nutella.copy(id = "l", image = "/x/l.img")
        val brandWithImg = Product("b", brand = "Ferrero", image = "/x/b.img", imageSource = "catalog")
        val brandNoImg = Product("bn", brand = "Ferrero")
        val products = listOf(catalog, offerImg, noImg, legacy, brandWithImg, brandNoImg)
        val hits = products.map { p -> Matcher.match(p, o("https://cdn/img.webp"), stores, today)!! }

        val up = Checker.imageUpgrades(products, hits)
        assertEquals(setOf("c", "n", "l", "bn"), up.keys)   // Prospektbild und Marke mit Bild bleiben
        assertEquals("https://cdn/img.webp", up["c"])

        // ohne Bild im Angebot oder bei unsicherem Treffer: nichts
        val noImage = products.map { p -> Matcher.match(p, o(""), stores, today)!! }
        assertTrue(Checker.imageUpgrades(products, noImage).isEmpty())
        val unsure = hits.map { it.copy(confidence = 0.5) }
        assertTrue(Checker.imageUpgrades(products, unsure).isEmpty())
    }

    @Test fun dedupeAndMessage() {
        val a = hit(nutella, offer("1", "REWE", "rewe", "Ferrero", "Nutella", "je 450-g-Glas", 2.99, 4.49))!!
        val c = hit(nutella, offer("3", "REWE", "rewe", "Ferrero", "Nutella", "450 g", 2.79, 4.49))!!
        assertNotEquals(a.dedupeKey, c.dedupeKey)
        assertEquals(2.79, Matcher.bestHits(listOf(a, c)).single().offer.price!!, 0.001)
        assertEquals("REWE: Ferrero Nutella", a.title())
        val body = a.body("2026-09-22")
        assertTrue(body, body.contains("2,99 € (statt 4,49 €)"))
        assertTrue(body.contains("Gültig 02.11.–07.11."))
        assertTrue(body.contains("Zuletzt im Angebot: 22.09."))
    }
}
