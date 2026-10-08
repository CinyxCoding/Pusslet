package se.pusslet.core.shopping

import se.pusslet.core.units.MeasureUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ShoppingInputTest {
    @Test
    fun commaAndOchSeparateItems() {
        val items = ShoppingInput.parse("mjölk, 2 gurka och pasta")
        assertEquals(listOf("Mjölk", "Gurka", "Pasta"), items.map { it.name })
        assertEquals(2.0, items[1].quantity?.value)
        assertNull(items[0].quantity)
    }

    @Test
    fun decimalCommaIsKept() {
        val items = ShoppingInput.parse("1,5 l mjölk, bröd")
        assertEquals(2, items.size)
        assertEquals(1.5, items[0].quantity?.value)
        assertEquals(MeasureUnit.L, items[0].quantity?.unit)
        assertEquals("Mjölk", items[0].name)
    }

    @Test
    fun linesAndPrefixAreHandled() {
        val items = ShoppingInput.parse("köp: ägg\n500 g nötfärs\n\nbananer.")
        assertEquals(listOf("Ägg", "Nötfärs", "Bananer"), items.map { it.name })
        assertEquals(MeasureUnit.G, items[1].quantity?.unit)
    }

    @Test
    fun aBareNumberIsAName() {
        // "7up" or "4" alone should not become an empty item with a quantity.
        val items = ShoppingInput.parse("4")
        assertEquals("4", items.single().name)
        assertNull(items.single().quantity)
    }

    @Test
    fun emptyInputGivesNothing() {
        assertTrue(ShoppingInput.parse("  ,  och ").isEmpty())
    }
}

class StapleSuggesterTest {
    private val start = Instant.parse("2026-09-01T16:00:00Z")

    private fun every(name: String, days: Int, times: Int, from: Instant = start) =
        (0 until times).map { Purchase(name, from + (it * days).days) }

    @Test
    fun milkEveryFourDaysIsSuggestedWhenDue() {
        val history = every("Mjölk", 4, 5) // last purchase on day 16
        val now = start + 20.days
        val s = StapleSuggester.suggest(history, now).single()
        assertEquals("Mjölk", s.name)
        assertEquals(4.days, s.typicalInterval)
        assertEquals(5, s.timesBought)
    }

    @Test
    fun notSuggestedRightAfterBuying() {
        val history = every("Mjölk", 4, 5)
        assertTrue(StapleSuggester.suggest(history, start + 17.days).isEmpty())
    }

    @Test
    fun twoPurchasesAreNotAHabit() {
        assertTrue(StapleSuggester.suggest(every("Saffran", 3, 2), start + 30.days).isEmpty())
    }

    @Test
    fun checkingTwoPacksOnOneTripCountsOnce() {
        val trip = listOf(Purchase("Mjölk", start), Purchase("mjölk", start + 2.minutes))
        val history = trip + every("Mjölk", 7, 2, start + 7.days)
        // Trips on day 0, 7 and 14 -> weekly. Without merging, the median would be wrong.
        val s = StapleSuggester.suggest(history, start + 21.days).single()
        assertEquals(7.days, s.typicalInterval)
        assertEquals(3, s.timesBought)
    }

    @Test
    fun itemsOnTheListOrDismissedAreSkipped() {
        val history = every("Bananer", 5, 4) + every("Bröd", 5, 4)
        val now = start + 21.days
        assertEquals(2, StapleSuggester.suggest(history, now).size)
        assertEquals(listOf("Bröd"), StapleSuggester.suggest(history, now, onList = setOf("banan")).map { it.name })
        assertTrue(StapleSuggester.suggest(history, now, onList = setOf("bananer"), dismissed = setOf("bröd")).isEmpty())
    }

    @Test
    fun rareOrAbandonedItemsAreNotSuggested() {
        // Bought monthly-ish: beyond four weeks, not a basvara.
        assertTrue(StapleSuggester.suggest(every("Tandkräm", 40, 4), start + 170.days).isEmpty())
        // Bought every 3 days, but not for a month: the family stopped.
        assertTrue(StapleSuggester.suggest(every("Yoghurt", 3, 6), start + 45.days).isEmpty())
    }

    @Test
    fun mostOverdueComesFirstAndNamesAreMerged() {
        val history = every("Mjölk", 4, 4) + every("Gula lökar", 10, 3) + listOf(Purchase("gul lök", start + 20.days + 1.hours))
        val now = start + 40.days
        val names = StapleSuggester.suggest(history, now).map { it.key }
        // Milk: last day 12, interval 4 -> 7x overdue -> stale, dropped. Onions: trips 0,10,20 -> due 2.0.
        assertEquals(listOf("gul lök"), names)
    }
}
