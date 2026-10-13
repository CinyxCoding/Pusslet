package se.pusslet.core.shopping

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import se.pusslet.core.time.plusDays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StapleSuggesterTest {
    private val zone = TimeZone.of("Europe/Stockholm")
    private val today = LocalDate(2026, 10, 13)

    private fun bought(name: String, daysAgo: Int, hour: Int = 17) =
        PurchaseRecord(name, today.plusDays(-daysAgo).atTime(LocalTime(hour, 0)).toInstant(zone))

    private fun milkEveryFourDays(lastDaysAgo: Int) = listOf(12, 8, 4, 0).map { bought("Mjölk", it + lastDaysAgo) }

    @Test
    fun suggestsWhenTheUsualIntervalHasPassed() {
        val s = StapleSuggester.suggest(milkEveryFourDays(lastDaysAgo = 4), onList = emptyList(), today = today, zone = zone).single()
        assertEquals("mjölk", s.key)
        assertEquals("Mjölk", s.name)
        assertEquals(4, s.intervalDays)
        assertEquals(4, s.daysSinceLast)
    }

    @Test
    fun notYetDue() {
        assertTrue(StapleSuggester.suggest(milkEveryFourDays(lastDaysAgo = 1), emptyList(), today, zone).isEmpty())
    }

    @Test
    fun needsThreePurchasesOnDifferentDays() {
        // Two ticks the same evening count as one purchase.
        val history = listOf(bought("Bröd", 14), bought("Bröd", 7, hour = 9), bought("bröd", 7, hour = 18))
        assertTrue(StapleSuggester.suggest(history, emptyList(), today, zone).isEmpty())
    }

    @Test
    fun alreadyOnTheListIsNotSuggested() {
        assertTrue(StapleSuggester.suggest(milkEveryFourDays(4), onList = listOf("mjölk"), today = today, zone = zone).isEmpty())
    }

    @Test
    fun droppedItemsAreNotNaggedAbout() {
        // Bought every 4 days in the summer, nothing for 30 days.
        assertTrue(StapleSuggester.suggest(milkEveryFourDays(lastDaysAgo = 30), emptyList(), today, zone).isEmpty())
    }

    @Test
    fun dismissedUntilBoughtAgain() {
        val history = milkEveryFourDays(4)
        val dismissedNow = mapOf("mjölk" to today.atTime(LocalTime(8, 0)).toInstant(zone))
        assertTrue(StapleSuggester.suggest(history, emptyList(), today, zone, dismissedNow).isEmpty())
        val dismissedBefore = mapOf("mjölk" to today.plusDays(-10).atTime(LocalTime(8, 0)).toInstant(zone))
        assertEquals(1, StapleSuggester.suggest(history, emptyList(), today, zone, dismissedBefore).size)
    }

    @Test
    fun mostOverdueFirstAndPluralsMerged() {
        // Milk: due today. Bananas: weekly, now a week late.
        val history = milkEveryFourDays(4) +
            listOf(28, 21, 14).map { bought("Bananer", it) } +
            bought("banan", 35)
        val s = StapleSuggester.suggest(history, emptyList(), today, zone)
        assertEquals(listOf("banan", "mjölk"), s.map { it.key })
        assertEquals("Bananer", s.first().name)
    }
}
