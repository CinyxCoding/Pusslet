package se.pusslet.feature.meals

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.meals.SuggestionReason
import se.pusslet.core.model.Allergen
import se.pusslet.core.model.DietProfile
import se.pusslet.core.model.Event
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeTag
import se.pusslet.core.model.Role
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.recipes.IngredientParser
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import se.pusslet.testing.TestGraph
import se.pusslet.testing.TestGraph.Companion.ELLA
import se.pusslet.testing.collectInBackground
import se.pusslet.testing.recordMessages
import se.pusslet.testing.viewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MealsViewModelTest {
    private val monday = LocalDate(2026, 10, 5)
    private val tuesday = monday.plusDays(1)

    private fun recipe(id: String, title: String, minutes: Int? = null, vararg lines: String, tags: Set<RecipeTag> = emptySet()) =
        Recipe(id, title, 4, lines.map { IngredientParser.parse(it) }, totalMinutes = minutes, tags = tags)

    private fun vm(g: TestGraph) = MealsViewModel(
        g.meals,
        g.recipes,
        g.lunches,
        g.family,
        g.calendar,
        g.shopping,
        g.mealShopping,
        g.nutrition,
        g.sessions,
        g.ids,
        g.clock,
        g.zone,
    )

    @Test
    fun weekShowsDinnersLunchClashesAndBusyEvenings() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.recipes.save(recipe("r-bullar", "Köttbullar med potatismos", 40))
        g.meals.setEntry(MealEntry(monday, recipeId = "r-bullar", title = "Köttbullar med potatismos"))
        g.lunches.setManual(ELLA, monday, "Köttbullar med potatismos\nVegetariska bullar")
        g.calendar.saveEvent(
            Event("e-fotboll", "Fotboll", LocalDateTime(tuesday, LocalTime(17, 30)), LocalDateTime(tuesday, LocalTime(18, 30))),
        )

        val vm = vm(g)
        collectInBackground(vm.state)
        val s = vm.state.value
        assertEquals(monday.isoWeek, s.week)
        assertEquals(7, s.days.size)
        val mon = s.days.first()
        assertEquals("Köttbullar med potatismos", mon.meals.single().entry?.title)
        assertEquals("Ella", mon.meals.single().clashes.single().child.name)
        assertEquals("Köttbullar med potatismos", mon.lunches.first { it.child.id == ELLA }.dishes.first())
        assertEquals("Fotboll", s.days[1].busy.single().event.title)
        assertTrue(s.days[2].busy.isEmpty())
        assertTrue(s.canEdit)
    }

    @Test
    fun busyEveningSuggestsSomethingQuickAndPlanningWorks() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.recipes.save(recipe("r-gryta", "Långkok på högrev", 180))
        g.recipes.save(recipe("r-pasta", "Pasta med pesto", 15, tags = setOf(RecipeTag.Quick)))
        g.calendar.saveEvent(
            Event("e-fotboll", "Fotboll", LocalDateTime(tuesday, LocalTime(17, 30)), LocalDateTime(tuesday, LocalTime(18, 30))),
        )
        val vm = vm(g)
        collectInBackground(vm.state)

        vm.openDay(tuesday)
        val sheet = assertNotNull(vm.state.value.sheet)
        assertEquals(tuesday, sheet.date)
        val first = sheet.suggestions.first()
        assertEquals("r-pasta", first.recipe.id)
        assertTrue(SuggestionReason.QuickForBusyEvening in first.reasons)

        vm.planRecipe(tuesday, MealSlot.Dinner, first.recipe)
        assertEquals("Pasta med pesto", vm.state.value.days[1].meals.single().entry?.title)
        vm.planText(monday, MealSlot.Dinner, "tacos")
        assertEquals("Tacos", vm.state.value.days[0].meals.single().entry?.title)
        vm.closeDay()
        assertNull(vm.state.value.sheet)
    }

    @Test
    fun cookDoubleAddsLeftoversAndTurningItOffRemovesThem() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        val vm = vm(g)
        collectInBackground(vm.state)
        vm.planText(monday, MealSlot.Dinner, "Kycklinggryta")
        vm.setCookDouble(monday, MealSlot.Dinner, true)
        val leftovers = vm.state.value.days[1].meals.single().entry
        assertEquals(monday, leftovers?.leftoversFrom)
        vm.setCookDouble(monday, MealSlot.Dinner, false)
        assertNull(vm.state.value.days[1].meals.single().entry)
        assertFalse(vm.state.value.days[0].meals.single().entry?.cookDouble ?: true)
    }

    @Test
    fun copyLastWeekAndFavouriteWeeks() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.meals.setEntry(MealEntry(monday.plusDays(-7), title = "Fiskpinnar"))
        val vm = vm(g)
        collectInBackground(vm.state)
        val messages = recordMessages(vm.messages)

        vm.copyLastWeek()
        assertEquals("Fiskpinnar", vm.state.value.days[0].meals.single().entry?.title)
        vm.saveFavorite("Vardagsveckan")
        assertEquals("Vardagsveckan", vm.state.value.favorites.single().name)

        vm.nextWeek()
        assertNull(vm.state.value.days[0].meals.single().entry)
        vm.applyFavorite(vm.state.value.favorites.single())
        assertEquals("Fiskpinnar", vm.state.value.days[0].meals.single().entry?.title)
        assertEquals(monday.plusDays(7), vm.state.value.days[0].date)
        assertEquals(
            listOf(MealsMessage.Copied, MealsMessage.FavoriteSaved, MealsMessage.FavoriteApplied("Vardagsveckan")),
            messages,
        )
    }

    @Test
    fun moveSwapsDays() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        val vm = vm(g)
        collectInBackground(vm.state)
        vm.planText(monday, MealSlot.Dinner, "Tacos")
        vm.planText(tuesday, MealSlot.Dinner, "Soppa")
        vm.move(monday, tuesday, MealSlot.Dinner)
        assertEquals("Soppa", vm.state.value.days[0].meals.single().entry?.title)
        assertEquals("Tacos", vm.state.value.days[1].meals.single().entry?.title)
    }

    @Test
    fun shopForTheWeekPreviewsThenWritesTheList() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.shopping.saveList(ShoppingList("l-vecka", "Veckohandling"))
        g.recipes.save(recipe("r-tacos", "Tacos", 30, "500 g nötfärs", "1 gurka", "8 tortillabröd"))
        g.meals.setEntry(MealEntry(monday.plusDays(4), recipeId = "r-tacos", title = "Tacos"))
        val vm = vm(g)
        collectInBackground(vm.state)
        val messages = recordMessages(vm.messages)

        vm.previewShopping()
        val preview = assertNotNull(vm.state.value.shopping)
        assertTrue(preview.hasRecipes)
        assertEquals(3, preview.plan.add.size)
        assertTrue(g.shopping.getItems("l-vecka").isEmpty(), "nothing written before confirming")

        vm.confirmShopping()
        assertNull(vm.state.value.shopping)
        assertEquals(setOf("nötfärs", "gurka", "tortillabröd"), g.shopping.getItems("l-vecka").map { it.name }.toSet())
        assertEquals(MealsMessage.ShoppingDone("Veckohandling"), messages.single())
    }

    @Test
    fun withoutAListTheUserIsToldToCreateOne() = viewModelTest {
        val g = TestGraph()
        val vm = vm(g)
        collectInBackground(vm.state)
        val messages = recordMessages(vm.messages)
        vm.previewShopping()
        assertNull(vm.state.value.shopping)
        assertEquals(listOf<MealsMessage>(MealsMessage.NoShoppingList), messages)
    }

    @Test
    fun dietConflictsAreShownOnThePlannedDinner() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.family.saveDiet(DietProfile(ELLA, allergies = setOf(Allergen.Fish)))
        g.recipes.save(recipe("r-lax", "Ugnslax", 30, "600 g laxfilé"))
        g.meals.setEntry(MealEntry(monday, recipeId = "r-lax", title = "Ugnslax"))
        val vm = vm(g)
        collectInBackground(vm.state)
        val note = vm.state.value.days[0].meals.single().conflicts.single()
        assertEquals("Ella", note.person.name)
        assertEquals(Allergen.Fish, note.allergen)

        vm.openDay(monday)
        assertTrue(vm.state.value.sheet?.suggestions.orEmpty().none { it.recipe.id == "r-lax" }, "never suggested")
    }

    @Test
    fun helpersSeeTheMenuButCannotChangeIt() = viewModelTest {
        val g = TestGraph(role = Role.Caregiver, memberId = TestGraph.BENGT)
        val vm = vm(g)
        collectInBackground(vm.state)
        assertFalse(vm.state.value.canEdit)
    }

    @Test
    fun weekNutritionIsAnAverageOfDinnersWithRecipes() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.foods.foods += se.pusslet.core.nutrition.Food(1, "Pasta torr")
        g.foods.values[1] = se.pusslet.core.nutrition.NutrientValues(energyKcal = 350.0, protein = 12.0)
        g.recipes.save(recipe("r-pasta", "Pasta", 15, "400 g pasta"))
        g.meals.setEntry(MealEntry(monday, recipeId = "r-pasta", title = "Pasta"))
        g.meals.setEntry(MealEntry(tuesday, title = "Pizza"))
        val vm = vm(g)
        collectInBackground(vm.state)
        vm.loadNutrition()
        val n = assertNotNull(vm.state.value.nutrition)
        assertEquals(1, n.withRecipe)
        assertEquals(2, n.dinners)
        // 400 g pasta à 350 kcal/100 g over 4 portions.
        assertEquals(350.0, n.perPortion.energyKcal)
    }
}
