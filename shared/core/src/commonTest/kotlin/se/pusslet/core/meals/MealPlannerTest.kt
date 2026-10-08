package se.pusslet.core.meals

import kotlinx.datetime.LocalDate
import se.pusslet.core.model.MainComponent
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealPlanWeek
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeTag
import se.pusslet.core.recipes.IngredientParser
import se.pusslet.core.time.isoWeek
import se.pusslet.core.units.MeasureUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MealPlannerTest {
    private val monday = LocalDate(2026, 10, 12)

    private fun recipe(
        id: String,
        title: String,
        vararg lines: String,
        main: MainComponent? = null,
        minutes: Int? = null,
        favorite: Boolean = false,
    ) = Recipe(id, title, 4, lines.map { IngredientParser.parse(it) }, mainComponent = main, totalMinutes = minutes, favorite = favorite)

    @Test
    fun scalingUsesReadableUnits() {
        val r = recipe("r", "Pannkakor", "3 ägg", "6 dl mjölk", "1 tsk salt", "1 gul lök")
        val six = RecipeScaler.scale(r, 6).associateBy { it.name }
        assertEquals(5.0, six.getValue("ägg").quantity, "4,5 eggs rounds up to whole eggs")
        assertEquals(9.0, six.getValue("mjölk").quantity)
        assertEquals(MeasureUnit.Dl, six.getValue("mjölk").unit)
        assertEquals(MeasureUnit.Tsk, six.getValue("salt").unit)
        assertEquals(1.5, six.getValue("salt").quantity)
        val twelve = RecipeScaler.scale(r, 12).associateBy { it.name }
        assertEquals(18.0, twelve.getValue("mjölk").quantity, "1,8 l is not a measure anyone uses; 18 dl is")
        assertEquals(MeasureUnit.Dl, twelve.getValue("mjölk").unit)
        assertEquals(3.0, twelve.getValue("gul lök").quantity)
        assertEquals(r.ingredients, RecipeScaler.scale(r, 4))
    }

    @Test
    fun copyWeekMovesEveryEntrySevenDays() {
        val week = MealPlanWeek(monday.isoWeek)
            .with(MealEntry(monday, title = "Tacos", cookDouble = true))
            .with(MealEntry(monday.plusDays(1), MealSlot.Lunch, title = "Tacos", leftoversFrom = monday))
        val next = MealPlanner.copyWeek(week, monday.isoWeek.next())
        assertEquals(setOf(monday.plusDays(7), monday.plusDays(8)), next.entries.values.map { it.date }.toSet())
        assertEquals(monday.plusDays(7), next.entry(monday.plusDays(8), MealSlot.Lunch)?.leftoversFrom)
    }

    @Test
    fun favoriteWeekRoundTrip() {
        val week = MealPlanWeek(monday.isoWeek)
            .with(MealEntry(monday, title = "Fiskpinnar"))
            .with(MealEntry(monday.plusDays(4), title = "Fredagstacos", cookDouble = true))
            .with(MealEntry(monday.plusDays(5), MealSlot.Lunch, title = "Fredagstacos", leftoversFrom = monday.plusDays(4)))
        val fav = MealPlanner.favoriteFrom(week, "f", "Vardagsklassiker")
        assertEquals(setOf(0, 4), fav.days.keys)
        val target = monday.isoWeek.plusWeeks(3)
        val applied = MealPlanner.applyFavorite(fav, target, MealPlanWeek(target))
        assertEquals("Fiskpinnar", applied.entry(target.monday)?.title)
        assertEquals("Fredagstacos", applied.entry(target.monday.plusDays(5))?.title, "cook double recreates leftovers")
        assertEquals(target.monday.plusDays(4), applied.entry(target.monday.plusDays(5))?.leftoversFrom)
    }

    @Test
    fun leftoversAreTomorrowsDinnerOrALunchBox() {
        val lasagne = MealEntry(monday, recipeId = "r", title = "Lasagne", servings = 4, cookDouble = true)
        val l = MealPlanner.leftoversFor(lasagne)
        assertEquals(monday.plusDays(1), l.date)
        assertEquals(MealSlot.Dinner, l.slot)
        assertEquals(monday, l.leftoversFrom)
        assertEquals("r", l.recipeId, "the recipe stays linked; leftovers are never shopped for")
        assertEquals(
            MealSlot.Lunch,
            MealPlanner.leftoversFor(lasagne, nextDinnerTaken = true).slot,
            "matlåda when tomorrow's dinner is planned",
        )
    }

    @Test
    fun suggestionsAreExplainable() {
        val fish = recipe("fish", "Ugnsbakad lax", "400 g laxfilé", main = MainComponent.Fish, minutes = 25, favorite = true)
        val stew = recipe("stew", "Kalops", "800 g högrev", main = MainComponent.Beef, minutes = 150)
        val pasta = recipe("pasta", "Pasta med pesto", "400 g pasta", main = MainComponent.Pasta, minutes = 15)
        val soup = recipe("soup", "Linssoppa", "2 dl röda linser", "2 morötter", main = MainComponent.Vegetarian, minutes = 30)
        val history = listOf(MealEntry(monday.plusDays(-3), recipeId = "pasta", title = "Pasta med pesto"))

        // Busy Tuesday, the children had fish for lunch, carrots at home.
        val s = MealSuggester.suggest(
            date = monday.plusDays(1),
            recipes = listOf(fish, stew, pasta, soup),
            history = history,
            schoolLunchDishes = listOf("Fiskgratäng med potatis"),
            busyEvening = true,
            pantryNames = setOf("Morötter"),
        )
        assertEquals("soup", s.first().recipe.id)
        assertTrue(SuggestionReason.UsesWhatYouHave in s.first().reasons)
        assertTrue(SuggestionReason.QuickForBusyEvening in s.first().reasons)
        val order = s.map { it.recipe.id }
        assertTrue(order.indexOf("fish") > order.indexOf("soup"), "fish again after a fish lunch is pushed down")
        assertTrue(order.indexOf("pasta") > order.indexOf("soup"), "cooked three days ago")
        assertTrue(order.last() == "stew" || order.indexOf("stew") > order.indexOf("soup"), "slow on a busy evening")
        assertTrue(RecipeTag.Quick !in stew.tags)
    }

    private fun LocalDate.plusDays(n: Int) = LocalDate.fromEpochDays(toEpochDays() + n)
}
