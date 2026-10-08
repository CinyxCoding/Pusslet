package se.pusslet.core.nutrition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FoodMatcherTest {
    private val foods = listOf(
        Food(1, "Grädde 40% fett"),
        Food(2, "Matlagningsgrädde 15% fett"),
        Food(3, "Gräddfil 12% fett"),
        Food(4, "Gräddtårta hemlagad"),
        Food(5, "Lök gul rå"),
        Food(6, "Purjolök rå"),
    )

    @Test
    fun plainFoodWinsButCompoundsAreAlternatives() {
        val best = FoodMatcher.best("grädde", foods, limit = 8)
        assertEquals(1, best.first().number)
        assertTrue(best.any { it.number == 2 }, "matlagningsgrädde is a kind of grädde")
        assertTrue(best.none { it.number == 4 }, "a cake is not cream")
    }

    @Test
    fun theExactCompoundIsPreferredWhenNamed() {
        assertEquals(2, FoodMatcher.best("matlagningsgrädde", foods).first().number)
    }

    @Test
    fun wordsInAnyOrderMatch() {
        assertEquals(5, FoodMatcher.best("gul lök", foods).first().number)
    }
}
