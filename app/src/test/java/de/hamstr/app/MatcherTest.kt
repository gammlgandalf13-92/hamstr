package de.hamstr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import de.hamstr.app.UpdateNotifications.Notice
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
        val beeren = Product(
            id = "beeren",
            names = listOf("Beerenmisch", "Beerenmix", "Beeren Mix", "Waldbeeren", "Beeren Mischung"),
            queries = listOf("Beerenmischung", "Beerenmix", "Waldbeeren"),
            exclude = listOf("Joghurt", "Eis", "Smoothie", "Müsli", "Saft", "Konfitüre", "Kuchen", "Tee", "Quark"),
        )
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

        // eigenes Foto wird nie ersetzt
        val own = nutella.copy(id = "own", image = "/x/own.jpg", imageSource = "own")
        val ownHit = Matcher.match(own, o("https://cdn/img.webp"), stores, today)!!
        assertTrue(Checker.imageUpgrades(listOf(own), listOf(ownHit)).isEmpty())

        val up = Checker.imageUpgrades(products, hits)
        assertEquals(setOf("c", "n", "l", "bn"), up.keys)   // Prospektbild und Marke mit Bild bleiben
        assertEquals("https://cdn/img.webp", up["c"])

        // ohne Bild im Angebot oder bei unsicherem Treffer: nichts
        val noImage = products.map { p -> Matcher.match(p, o(""), stores, today)!! }
        assertTrue(Checker.imageUpgrades(products, noImage).isEmpty())
        val unsure = hits.map { it.copy(confidence = 0.5) }
        assertTrue(Checker.imageUpgrades(products, unsure).isEmpty())
    }

    @Test fun catalogPrefersOfferImage() {
        val item = CatalogItem("1", "Nutella", "Ferrero", "450 g", "https://off/foto.jpg")
        val nutellaOffer = Offer("9", "REWE", "rewe", "Nutella", "Ferrero", "je 450-g-Glas", 2.99, null,
            "2026-11-02", "2026-11-07", "", "https://cdn/prospekt.webp")
        val kinderOffer = nutellaOffer.copy(productName = "Kinder Country", description = "9er-Pckg.")
        val bigJar = nutellaOffer.copy(description = "je 1-kg-Glas")

        val up = withOfferImage(item, listOf(kinderOffer, nutellaOffer), today)
        assertEquals("https://cdn/prospekt.webp", up.image)
        assertTrue(up.imageFromOffer)
        assertEquals("offer", productFromCatalog(up).imageSource)

        // gleiche Marke, anderes Produkt bzw. andere Größe: Katalogfoto bleibt
        assertEquals(item, withOfferImage(item, listOf(kinderOffer), today))
        assertEquals(item, withOfferImage(item, listOf(bigJar), today))
        assertEquals("catalog", productFromCatalog(item).imageSource)
    }

    @Test fun marketList() {
        val found = listOf(
            NearbyStore("Globus Bad Kissingen", "globus", 0.0, 0.0, 3.2, "Am Markt 1"),
            NearbyStore("Lidl", "lidl", 0.0, 0.0, 1.1, ""),
            NearbyStore("Lidl", "lidl", 0.0, 0.0, 4.0, ""),
        )
        val old = mapOf("tegut" to ChainInfo("tegut", 9.9, 1, "alt"))
        val info = Markets.applyNearby(old, found)
        assertEquals("Globus", info["globus"]!!.label)
        assertEquals(2, info["lidl"]!!.count)
        assertEquals(1.1, info["lidl"]!!.distanceKm!!, 0.001)
        assertNull(info["tegut"]!!.distanceKm)            // alte Entfernung verworfen, Eintrag bleibt
        assertEquals("Lidl", Markets.label("lidl", info))

        val order = Markets.ordered(info, setOf("rewe", "tegut"))
        assertEquals(listOf("lidl", "globus"), order.take(2))  // Ketten im Umkreis zuerst, nach Entfernung
        assertEquals("rewe", order[2])                         // dann Standard-Ketten
        assertEquals("tegut", order.last())                    // eigene ganz unten
        assertEquals("Globus", Markets.labelFor("Globus Bad Kissingen", "globus"))
        assertEquals("Edeka", Markets.labelFor("Marktkauf", "edeka"))
    }

    @Test fun updateNotifications() {
        val day = 24L * 60 * 60 * 1000
        val n = UpdateNotifications
        // aktuell → nichts
        assertEquals(Notice.NONE, n.decide(19, 19, 0, 0, 0, 10 * day))
        // neue Version → erste Meldung
        assertEquals(Notice.FIRST, n.decide(20, 19, 19, 0, 0, 10 * day))
        // schon gemeldet, erst 1 Tag her → nichts
        assertEquals(Notice.NONE, n.decide(20, 19, 20, 10 * day, 0, 11 * day))
        // nach 3 Tagen → eine Erinnerung
        assertEquals(Notice.REMINDER, n.decide(20, 19, 20, 10 * day, 0, 13 * day))
        // Erinnerung schon verschickt → nichts mehr
        assertEquals(Notice.NONE, n.decide(20, 19, 20, 10 * day, 20, 30 * day))
        // noch neuere Version → wieder erste Meldung
        assertEquals(Notice.FIRST, n.decide(21, 19, 20, 10 * day, 20, 30 * day))
    }

    @Test fun barcodes() {
        assertEquals("4008400401621", Catalog.normalizeBarcode("4 008400 401621"))
        assertEquals("40084004", Catalog.normalizeBarcode("4008-4004"))
        assertEquals("036000291452", Catalog.normalizeBarcode("036000291452"))
        assertNull(Catalog.normalizeBarcode("Nutella"))
        assertNull(Catalog.normalizeBarcode("97539"))          // PLZ ist kein Barcode
        assertNull(Catalog.normalizeBarcode("400840040162a"))
        val item = CatalogItem("4008400401621", "Nutella", "Ferrero", "450 g", "")
        assertEquals("4008400401621", productFromCatalog(item).ean)
    }

    @Test fun scheduleNextRun() {
        val mon = java.time.LocalDateTime.of(2026, 11, 9, 10, 0)   // Montag, 10:00
        val daily = CheckSlot(Schedule.ALL_DAYS, 7, 15)
        val evening = CheckSlot(Schedule.ALL_DAYS, 18, 0)
        val saturday = CheckSlot(setOf(java.time.DayOfWeek.SATURDAY), 9, 0)
        // täglich 7:15 → morgen früh
        assertEquals(java.time.LocalDateTime.of(2026, 11, 10, 7, 15), Schedule.nextRun(listOf(daily), mon))
        // zweimal täglich → heute Abend
        assertEquals(java.time.LocalDateTime.of(2026, 11, 9, 18, 0), Schedule.nextRun(listOf(daily, evening), mon))
        // wöchentlich samstags
        assertEquals(java.time.LocalDateTime.of(2026, 11, 14, 9, 0), Schedule.nextRun(listOf(saturday), mon))
        // genau jetzt zählt nicht → nächste Woche
        val sat9 = java.time.LocalDateTime.of(2026, 11, 14, 9, 0)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 21, 9, 0), Schedule.nextRun(listOf(saturday), sat9))
        // kein Tag gewählt → nichts geplant
        assertNull(Schedule.nextRun(listOf(CheckSlot(emptySet(), 8, 0)), mon))
        // Texte
        assertEquals("werktags", Schedule.describeDays(Schedule.WEEKDAYS))
        assertEquals("täglich", Schedule.describeDays(Schedule.ALL_DAYS))
    }

    @Test fun dedupeAndMessage() {
        val a = hit(nutella, offer("1", "REWE", "rewe", "Ferrero", "Nutella", "je 450-g-Glas", 2.99, 4.49))!!
        val c = hit(nutella, offer("3", "REWE", "rewe", "Ferrero", "Nutella", "450 g", 2.79, 4.49))!!
        assertNotEquals(a.dedupeKey, c.dedupeKey)
        assertEquals(2.79, Matcher.bestHits(listOf(a, c)).single().offer.price!!, 0.001)
        assertEquals("REWE: Ferrero Nutella", a.title())
        assertEquals("Ferrero Nutella", a.itemTitle())
        val body = a.body("2026-09-22")
        assertTrue(body, body.contains("2,99 € (statt 4,49 €)"))
        assertTrue(body.contains("Gültig 02.11.–07.11."))
        assertTrue(body.contains("Zuletzt im Angebot: 22.09."))
    }

    // ---------------- Prospekt-Darstellung
    @Test fun discountAndValidity() {
        assertEquals(33, Matcher.discountPercent(2.99, 4.49))
        assertNull(Matcher.discountPercent(2.99, null))
        assertNull(Matcher.discountPercent(2.99, 2.99))
        // 02.11.2026 ist ein Montag
        assertEquals("bis Sa., 07.11.", Matcher.validityText("2026-11-02", "2026-11-07", today))
        assertEquals("ab Do., 05.11.", Matcher.validityText("2026-11-05", "2026-11-07", today))
        assertEquals("nur noch heute", Matcher.validityText("2026-10-28", "2026-11-02", today))
        assertEquals("bis morgen", Matcher.validityText("2026-10-28", "2026-11-03", today))
        assertEquals("", Matcher.validityText("", "", today))
    }

    // ---------------- Vergleichspreise (Open Prices)
    @Test fun refPricePick() {
        val prices = listOf(
            RefPrice(1.79, "2026-10-20", "Lidl", discounted = true, country = "DE"),       // Aktion → nicht als Normalpreis
            RefPrice(3.10, "2026-09-01", "Carrefour", country = "FR"),                     // Ausland
            RefPrice(2.49, "2026-06-15", "EDEKA Müller", "Hamburg", country = "DE"),
            RefPrice(2.29, "2025-01-10", "REWE", country = "DE"),                          // älter als 1 Jahr
        )
        val ref = OpenPrices.pick(prices, today)
        assertEquals(2.49, ref!!.price, 0.001)
        assertEquals("EDEKA Müller, Hamburg · 06/2026", ref.source())
        // Nur alte Preise → Rückfall auf den jüngsten bis 2 Jahre
        assertEquals(2.29, OpenPrices.pick(listOf(prices[3]), today)!!.price, 0.001)
        // Nur Aktionspreise oder sehr alte → nichts
        assertNull(OpenPrices.pick(listOf(prices[0], RefPrice(1.0, "2020-01-01")), today))
        assertEquals(20, OpenPrices.savingPercent(1.99, ref))
        assertNull(OpenPrices.savingPercent(2.79, ref))
        assertTrue(OpenPrices.needsRefresh(null))
        assertTrue(!OpenPrices.needsRefresh(RefEntry(null, 1_000L), now = 2_000L))
    }

    @Test fun bodyWithRefPrice() {
        val h = hit(nutella, offer("9", "REWE", "rewe", "Ferrero", "Nutella", "je 450-g-Glas", 1.99, 3.49))!!
        val body = h.body(ref = RefPrice(2.49, "2026-06-15", "EDEKA"))
        assertTrue(body, body.contains("Normalpreis ca. 2,49 € – 20 % gespart (Open Prices)"))
        assertTrue(h.hintText(), !h.hintText().contains("Unsicher"))
    }

    // ---------------- Wochen-Ansicht (heute = Mo. 02.11.2026)
    @Test fun weeks() {
        assertEquals(setOf(0), Matcher.weeksOf("2026-11-02", "2026-11-07", today))
        assertEquals(setOf(1), Matcher.weeksOf("2026-11-09", "2026-11-14", today))
        assertEquals(setOf(0, 1), Matcher.weeksOf("2026-11-05", "2026-11-11", today))
        assertEquals(setOf(2), Matcher.weeksOf("2026-11-16", "2026-11-21", today))
        assertEquals(emptySet<Int>(), Matcher.weeksOf("2026-10-26", "2026-10-31", today))
        assertEquals(setOf(0), Matcher.weeksOf("", "", today))
        // Sonntag: diese Woche ist nur noch heute, Montag beginnt „nächste Woche“
        val sunday = LocalDate.of(2026, 11, 8)
        assertEquals(setOf(1), Matcher.weeksOf("2026-11-09", "2026-11-14", sunday))
        assertEquals("09.11. – 15.11.", Matcher.weekRangeText(1, today))
        assertEquals("ab 16.11.", Matcher.weekRangeText(2, today))
        // „gilt erst ab“ steht nicht als Warnung an der Karte
        val h = hit(nutella, offer("7", "REWE", "rewe", "Ferrero", "Nutella", "je 450-g-Glas", 2.49, from = "2026-11-09", to = "2026-11-14"))!!
        assertTrue(h.hintText(), !h.hintText().contains("gilt erst ab"))
    }

    // ---------------- Preisverlauf
    private fun pt(store: String, price: Double?, from: String, title: String = "Ferrero Nutella") =
        PricePoint(store, store.lowercase(), price, from, from, title)

    @Test fun priceHistory() {
        val a = listOf(pt("Lidl", 2.29, "2026-09-07"), pt("REWE", 2.49, "2026-09-28"))
        val merged = PriceHistory.merge(a, listOf(pt("REWE", 2.49, "2026-09-28"), pt("Aldi", 1.99, "2026-10-19")), today)
        assertEquals(3, merged.size)                        // Dublette fällt raus
        assertEquals("2026-10-19", merged.last().validFrom) // nach Datum sortiert
        val st = PriceHistory.stats(merged)!!
        assertEquals(3, st.periods)
        assertEquals(1.99, st.lowest!!.price!!, 0.001)
        assertEquals(21, st.intervalDays)                   // 07.09. → 19.10. = 42 Tage / 2
        assertEquals("etwa alle 3 Wochen", PriceHistory.intervalText(21))
        assertEquals("etwa jede Woche", PriceHistory.intervalText(7))
        assertEquals("Tiefstpreis 1,99 € (Aldi) · 3× im Angebot · etwa alle 3 Wochen", PriceHistory.summary(st))
        assertTrue(PriceHistory.isBest(1.99, st))
        assertTrue(!PriceHistory.isBest(2.29, st))
        assertEquals("3× im Angebot · etwa alle 3 Wochen", PriceHistory.summary(st, 1.89))
        // Vergleich an der Angebotskarte
        assertEquals("Tiefstpreis bisher: 1,99 € bei Aldi (19.10.) · 0,50 € mehr", PriceHistory.compareText(2.49, st))
        assertNull(PriceHistory.compareText(1.99, st))
        assertEquals("3× im Angebot · etwa alle 3 Wochen", PriceHistory.frequencyText(st))
        // Gleiche Woche, zwei Märkte: schon vergleichbar, auch wenn erst ein Termin bekannt ist
        val sameWeek = PriceHistory.stats(listOf(pt("REWE", 2.49, "2026-11-02"), pt("Lidl", 1.99, "2026-11-02")))!!
        assertEquals("Tiefstpreis bisher: 1,99 € bei Lidl (02.11.) · 0,50 € mehr", PriceHistory.compareText(2.49, sameWeek))
        assertTrue(PriceHistory.isBest(1.99, sameWeek))
        assertNull(PriceHistory.frequencyText(sameWeek))
        // Ein einziges Angebot ist noch kein „Bestpreis“
        assertTrue(!PriceHistory.isBest(1.0, PriceHistory.stats(listOf(pt("Lidl", 1.0, "2026-11-02")))))
        // Älter als 2 Jahre fliegt raus
        assertEquals(0, PriceHistory.merge(emptyList(), listOf(pt("Lidl", 1.0, "2024-01-01")), today).size)
    }

    // ---------------- Einkaufsliste & Teilen
    @Test fun shoppingAndShare() {
        val h = SavedHit("Ferrero Nutella", "", "https://www.marktguru.de/offers/42", "2026-11-07",
            store = "REWE", storeSlug = "rewe", price = 1.99, oldPrice = 3.49, validFrom = "2026-11-02")
        assertEquals("REWE: Ferrero Nutella für 1,99 € (statt 3,49 €), bis Sa., 07.11.\nhttps://www.marktguru.de/offers/42",
            ShareText.offerDetails(h, today))
        assertEquals("REWE: Ferrero Nutella (Angebot)", ShareText.offer(h))
        val item = ShoppingItem.fromHit(h, now = 1L)
        assertEquals("https://www.marktguru.de/offers/42", item.id)
        assertTrue(!item.expired(today))
        assertTrue(item.expired(LocalDate.of(2026, 11, 8)))
        assertTrue(!ShoppingItem("x", "Milch").expired(today)) // eigene Einträge laufen nie ab
        val text = ShareText.list(listOf(item, ShoppingItem("y", "Milch"), ShoppingItem("z", "Brot", done = true)), today)
        assertTrue(text, text.contains("REWE\n• Ferrero Nutella – 1,99 € (bis Sa., 07.11.)"))
        assertTrue(text, text.contains("Sonstiges\n• Milch"))
        assertTrue(text, !text.contains("Brot"))
    }

    @Test fun importSkipsKnownProducts() {
        val mine = listOf(nutella, Product("2", names = listOf("Cola"), brand = "Coca-Cola", ean = "5000112546415"))
        val incoming = listOf(
            nutella.copy(id = "a"),                                                   // gleicher Name/Marke/Größe
            Product("b", names = listOf("Cola Zero"), brand = "Coca-Cola", ean = "5000112546415"), // gleicher Barcode
            Product("c", names = listOf("Hafermilch"), brand = "Oatly"),
            Product("d", names = listOf("Hafermilch"), brand = "Oatly"),              // doppelt in der Datei
        )
        assertEquals(listOf("c"), ProductTransfer.newOnes(mine, incoming).map { it.id })
    }

    // ---------------- Am Markt erinnern
    @Test fun atStoreMessage() {
        val nutellaRewe = SavedHit("Ferrero Nutella", "", "https://m/1", "2026-11-07", store = "REWE", storeSlug = "rewe",
            price = 1.99, validFrom = "2026-11-02")
        val colaRewe = SavedHit("Coca-Cola", "", "https://m/2", "2026-11-07", store = "REWE", storeSlug = "rewe",
            price = 0.99, validFrom = "2026-11-02")
        val nextWeek = SavedHit("Milka", "", "https://m/3", "2026-11-14", store = "REWE", storeSlug = "rewe",
            price = 0.89, validFrom = "2026-11-09")
        val lidl = SavedHit("Oatly", "", "https://m/4", "2026-11-07", store = "Lidl", storeSlug = "lidl", price = 1.49)
        val list = listOf(ShoppingItem.fromHit(nutellaRewe), ShoppingItem("x", "Milch"))
        val (title, body) = AtStore.message("rewe", "REWE", list, listOf(nutellaRewe, colaRewe, nextWeek, lidl), today)!!
        assertEquals("Du bist bei REWE", title)
        assertEquals("Auf deiner Liste: Ferrero Nutella 1,99 €\nAußerdem im Angebot: Coca-Cola 0,99 €", body)
        // Nichts für diesen Markt → keine Meldung
        assertNull(AtStore.message("penny", "Penny", list, listOf(nutellaRewe, lidl), today))
        // Überwacht werden nur eingeschaltete Ketten, die nächsten zuerst
        val shops = listOf(
            NearbyStore("REWE", "rewe", 0.0, 0.0, 2.0, ""), NearbyStore("Lidl", "lidl", 0.0, 0.0, 1.0, ""),
            NearbyStore("Globus", "globus", 0.0, 0.0, 0.5, ""),
        )
        assertEquals(listOf("lidl", "rewe"), AtStore.pick(shops, listOf("rewe", "lidl")).map { it.chain })
        assertEquals("Ferrero Nutella\nMilch", ShareText.names(list + ShoppingItem("y", "Brot", done = true)))
    }
}
