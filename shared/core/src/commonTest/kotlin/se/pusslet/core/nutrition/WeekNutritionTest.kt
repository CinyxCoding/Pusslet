package se.pusslet.core.nutrition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeekNutritionTest {
    private fun summary(kcal: Double, protein: Double?, matched: Int = 2, total: Int = 2) =
        NutritionSummary(NutrientValues(), NutrientValues(energyKcal = kcal, protein = protein), matched, total)

    @Test
    fun averagesThePortionsThatCouldBeEstimated() {
        val week = WeekNutritionCalculator.average(listOf(summary(600.0, 30.0), summary(400.0, null), null))
        assertEquals(3, week.meals)
        assertEquals(2, week.estimated)
        assertEquals(500.0, week.averagePerPortion.energyKcal)
        assertEquals(30.0, week.averagePerPortion.protein, "a missing value counts as unknown, not as zero")
        assertFalse(week.complete)
    }

    @Test
    fun completeOnlyWhenEverythingMatched() {
        assertTrue(WeekNutritionCalculator.average(listOf(summary(500.0, 20.0))).complete)
        assertFalse(WeekNutritionCalculator.average(listOf(summary(500.0, 20.0, matched = 1))).complete)
    }

    @Test
    fun emptyWeek() {
        val week = WeekNutritionCalculator.average(emptyList())
        assertEquals(0, week.estimated)
        assertNull(week.averagePerPortion.energyKcal)
    }
}
