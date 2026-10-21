package se.pusslet.core.shopping

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.PantryItem
import se.pusslet.core.model.PantryLocation
import se.pusslet.core.model.Recipe
import se.pusslet.core.quickadd.ParsedItem
import se.pusslet.core.recipes.IngredientParser
import se.pusslet.core.units.MeasureUnit
import se.pusslet.core.units.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class PantryTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun anOldPantryDocumentDecodesWithTheDefaults() {
        val old = json.decodeFromString(PantryItem.serializer(), """{"id":"p1","name":"Ris"}""")
        assertEquals(PantryLocation.Pantry, old.location)
        assertNull(old.bestBefore)
        assertTrue(old.inStock)
        assertNull(old.barcode)
        assertTrue(old.amountUnknown, "no amount = finns hemma, amount unknown")

        val withAmount = json.decodeFromString(PantryItem.serializer(), """{"id":"p2","name":"Mjölk","quantity":2.0,"unit":"l"}""")
        assertEquals(Quantity(2.0, MeasureUnit.L), withAmount.amount)
        assertFalse(withAmount.amountUnknown)
    }

    @Test
    fun theNewFieldsRoundTripWithStableNames() {
        val item = PantryItem("p1", "Ärtor", 1.0, MeasureUnit.Pase, PantryLocation.Freezer, LocalDate(2026, 12, 1), inStock = false)
        val text = json.encodeToString(PantryItem.serializer(), item)
        assertTrue("\"location\":\"freezer\"" in text, text)
        assertTrue("\"bestBefore\":\"2026-12-01\"" in text, text)
        assertTrue("\"inStock\":false" in text, text)
        assertEquals(item, json.decodeFromString(PantryItem.serializer(), text))
        assertNull(item.amount, "slut counts as nothing at home, whatever the old amount")
        assertFalse(item.amountUnknown)
        assertEquals(
            PantryLocation.Fridge,
            json.decodeFromString(PantryItem.serializer(), """{"id":"p","name":"Ost","location":"fridge"}""").location,
        )
    }

    @Test
    fun soonCoversTodayAndTheNextThreeDays() {
        val today = LocalDate(2026, 10, 21)

        fun status(day: Int, inStock: Boolean = true) =
            PantryRules.bestBefore(PantryItem("p", "Mjölk", bestBefore = LocalDate(2026, 10, day), inStock = inStock), today)
        assertEquals(BestBefore.Passed, status(20))
        assertEquals(BestBefore.Soon, status(21), "today")
        assertEquals(BestBefore.Soon, status(24), "in three days")
        assertEquals(BestBefore.Later, status(25), "in four days")
        assertNull(status(22, inStock = false), "slut: nothing to remind about")
        assertNull(PantryRules.bestBefore(PantryItem("p", "Ris"), today), "no date")
    }

    @Test
    fun todayComesFromTheInjectedClockAndZone() {
        // 22:30 UTC is 00:30 in Stockholm (summer time): already the next day there.
        val clock = object : Clock {
            override fun now() = Instant.parse("2026-10-20T22:30:00Z")
        }
        assertEquals(LocalDate(2026, 10, 20), PantryRules.today(clock, TimeZone.UTC))
        assertEquals(LocalDate(2026, 10, 21), PantryRules.today(clock, TimeZone.of("Europe/Stockholm")))
    }

    @Test
    fun groupedByPlaceWithSlutLast() {
        val groups = PantryRules.grouped(
            listOf(
                PantryItem("1", "Smör", location = PantryLocation.Fridge),
                PantryItem("2", "ris"),
                PantryItem("3", "Bönor", inStock = false),
                PantryItem("4", "Mjöl"),
            ),
        )
        assertEquals(listOf(PantryLocation.Pantry, PantryLocation.Fridge), groups.map { it.location }, "Frys is empty, so left out")
        assertEquals(listOf("Mjöl", "ris", "Bönor"), groups.first().items.map { it.name })
    }

    @Test
    fun addingSomethingAlreadyNotedUpdatesItAndItIsAtHomeAgain() {
        val date = LocalDate(2026, 11, 1)
        val existing = listOf(PantryItem("p1", "Mjölk", 1.0, MeasureUnit.L, PantryLocation.Fridge, date, inStock = false))
        val again = PantryRules.add(existing, ParsedItem("mjölk", Quantity(2.0, MeasureUnit.L)), { "new" })
        assertEquals(PantryItem("p1", "Mjölk", 2.0, MeasureUnit.L, PantryLocation.Fridge, date, inStock = true), again)

        val fresh = PantryRules.add(existing, ParsedItem("Ris"), { "new" })
        assertEquals("new", fresh.id)
        assertEquals(PantryLocation.Pantry, fresh.location)
        assertTrue(fresh.amountUnknown)
        assertEquals(
            PantryLocation.Fridge,
            PantryRules.add(emptyList(), ParsedItem("Grädde"), {
                "x"
            }).location,
            "dairy goes in the fridge",
        )
        assertEquals(
            PantryLocation.Freezer,
            PantryRules.add(emptyList(), ParsedItem("Ärtor"), { "x" }, location = PantryLocation.Freezer).location,
            "a scanned product's category wins",
        )
    }

    // ---- The pantry in "Handla till veckan" ----

    private val monday = LocalDate(2026, 10, 12)
    private val tacos = Recipe(
        "r1",
        "Tacos",
        4,
        listOf("500 g nötfärs", "2 gula lökar", "1 burk krossade tomater", "2 dl gräddfil", "tacokrydda").map {
            IngredientParser.parse(it)
        },
    )

    private fun generate(vararg pantry: PantryItem) = ShoppingListGenerator.generate(
        ShoppingListGenerator.demands(listOf(MealEntry(monday, recipeId = "r1", title = "Tacos")), mapOf("r1" to tacos), 4),
        pantry.toList(),
    )

    private fun Generation.line(name: String) = lines.single { it.key == ShoppingNames.canonical(name) }

    @Test
    fun knownAmountsInAConvertibleUnitAreDeducted() {
        val result = generate(PantryItem("p1", "Gul lök", 1.0), PantryItem("p2", "Nötfärs", 0.2, MeasureUnit.Kg))
        val onion = result.line("gul lök")
        assertEquals(Quantity(1.0, MeasureUnit.St), onion.primary, "an amount without unit means pieces")
        assertFalse(onion.askIfAtHome)
        assertEquals(listOf(Quantity(1.0, MeasureUnit.St)), onion.fromPantry)
        assertEquals(300.0, result.line("nötfärs").primary?.convertTo(MeasureUnit.G)?.value ?: 0.0, 0.001, "200 g of 500 g at home")
    }

    @Test
    fun aPantryThatCoversEverythingLeavesTheLineOutAndSaysSo() {
        val result = generate(PantryItem("p1", "Gräddfil", 3.0, MeasureUnit.Dl))
        assertTrue(result.lines.none { it.key == "gräddfil" })
        assertEquals(listOf("gräddfil"), result.coveredAtHome)
    }

    @Test
    fun atHomeWithoutAnAmountIsAskedNeverSkipped() {
        val result = generate(PantryItem("p1", "Krossade tomater"), PantryItem("p2", "Tacokrydda", 1.0, MeasureUnit.Pase))
        val tomatoes = result.line("krossade tomater")
        assertTrue(tomatoes.askIfAtHome, "\"Finns hemma?\"")
        assertEquals(Quantity(1.0, MeasureUnit.Burk), tomatoes.primary, "the full amount, the family decides")
        assertTrue(result.line("tacokrydda").askIfAtHome, "the recipe gives no amount to compare with")
        assertTrue(result.coveredAtHome.isEmpty())
    }

    @Test
    fun anAmountInAUnitThatDoesNotConvertIsAskedNotDeducted() {
        val result = generate(PantryItem("p1", "Krossade tomater", 400.0, MeasureUnit.G), PantryItem("p2", "Gräddfil", 1.0, MeasureUnit.St))
        assertEquals(Quantity(1.0, MeasureUnit.Burk), result.line("krossade tomater").primary)
        assertTrue(result.line("krossade tomater").askIfAtHome)
        assertEquals(2.0, result.line("gräddfil").primary?.convertTo(MeasureUnit.Dl)?.value ?: 0.0, 0.001)
        assertTrue(result.line("gräddfil").askIfAtHome)
    }

    @Test
    fun slutCountsAsNotAtHome() {
        val result =
            generate(PantryItem("p1", "Gul lök", 5.0, MeasureUnit.St, inStock = false), PantryItem("p2", "Nötfärs", inStock = false))
        assertEquals(Quantity(2.0, MeasureUnit.St), result.line("gul lök").primary)
        assertFalse(result.line("gul lök").askIfAtHome)
        assertFalse(result.line("nötfärs").askIfAtHome)
    }
}
