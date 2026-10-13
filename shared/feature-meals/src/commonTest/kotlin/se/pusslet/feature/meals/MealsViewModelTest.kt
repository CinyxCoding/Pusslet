package se.pusslet.feature.meals

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.meals.SuggestionReason
import se.pusslet.core.model.Child
import se.pusslet.core.model.Event
import se.pusslet.core.model.LunchProviderType
import se.pusslet.core.model.LunchSource
import se.pusslet.core.model.MainComponent
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Member
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeTag
import se.pusslet.core.model.Role
import se.pusslet.core.time.plusDays
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MealsViewModelTest {
    /** Tuesday 13 October 2026 (week 42). */
    private val tuesday = LocalDate(2026, 10, 13)
    private val monday = LocalDate(2026, 10, 12)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(world: TestWorld): MealsViewModel {
        val vm = MealsViewModel(
            world.meals,
            world.recipes,
            world.family,
            world.lunches,
            world.calendar,
            world.shopping,
            world.sessions,
            world.ids,
            world.clock,
            world.zone,
        )
        val eager = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        eager.launch { vm.state.collect {} }
        eager.launch { vm.dayEditor.collect {} }
        return vm
    }

    private suspend fun TestWorld.seed() {
        family.saveMember(Member("m-sara", "Sara"))
        family.saveChild(Child("c-ella", "Ella", lunch = LunchSource(LunchProviderType.Manual)))
        recipes.save(Recipe("r-kb", "Köttbullar och makaroner", 4, mainComponent = MainComponent.MincedMeat, totalMinutes = 25))
        recipes.save(Recipe("r-lax", "Ugnsbakad lax", 4, mainComponent = MainComponent.Fish, totalMinutes = 40))
        recipes.save(Recipe("r-pasta", "Snabb pasta", 4, mainComponent = MainComponent.Pasta, tags = setOf(RecipeTag.Quick)))
    }

    @Test
    fun weekStartsOnMondayWithTodayMarked() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)
        val state = vm.state.value
        assertEquals(42, state.week?.week)
        assertEquals(monday, state.days.first().date)
        assertEquals(7, state.days.size)
        assertEquals(tuesday, state.days.single { it.isToday }.date)
        assertTrue(state.days.last().redDay, "Sunday is a red day")

        vm.nextWeek()
        assertEquals(43, vm.state.value.week?.week)
        assertTrue(vm.state.value.days.none { it.isToday })
        vm.thisWeek()
        assertEquals(42, vm.state.value.week?.week)
    }

    @Test
    fun planFreeTextAndRecipeThenClear() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = viewModel(world)
        vm.plan(monday, MealSlot.Dinner, "tacos", recipeId = null, cookDouble = false, servings = null, note = "")
        vm.plan(tuesday, MealSlot.Dinner, "Ugnsbakad lax", recipeId = "r-lax", cookDouble = false, servings = null, note = "med potatis")
        val days = vm.state.value.days
        assertEquals("tacos", days[0].dinner?.title)
        assertEquals("r-lax", days[1].dinner?.recipeId)
        assertEquals("med potatis", days[1].dinner?.note)
        assertEquals(2, vm.state.value.plannedCount)

        vm.clear(monday, MealSlot.Dinner)
        assertNull(vm.state.value.days[0].dinner)
    }

    @Test
    fun schoolLunchClashIsFlagged() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = viewModel(world)
        vm.setLunch("c-ella", tuesday, "Köttbullar med potatis\nVegobullar")
        vm.plan(tuesday, MealSlot.Dinner, "Köttbullar och makaroner", "r-kb", cookDouble = false, servings = null, note = "")
        val day = vm.state.value.days[1]
        val lunch = day.lunches.single()
        assertEquals("Ella", lunch.child.firstName)
        assertEquals(setOf("köttbullar"), lunch.clash)
        assertTrue(day.hasClash)

        vm.plan(tuesday, MealSlot.Dinner, "Ugnsbakad lax", "r-lax", cookDouble = false, servings = null, note = "")
        assertFalse(vm.state.value.days[1].hasClash)
    }

    @Test
    fun busyEveningPutsQuickDinnersFirst() = runTest {
        val world = TestWorld().apply { seed() }
        val start = LocalDateTime(tuesday, LocalTime(17, 30))
        world.calendar.saveEvent(Event(id = "e1", title = "Fotboll", start = start, end = LocalDateTime(tuesday, LocalTime(18, 30))))
        val vm = viewModel(world)
        assertEquals("Fotboll", vm.state.value.days[1].busy.single().event.title)

        vm.openDay(tuesday)
        val editor = assertNotNull(vm.dayEditor.value)
        assertEquals(tuesday, editor.date)
        val first = editor.suggestions.first()
        assertTrue(first.recipe.isQuick)
        assertTrue(SuggestionReason.QuickForBusyEvening in first.reasons)

        vm.closeDay()
        assertNull(vm.dayEditor.value)
    }

    @Test
    fun suggestionsAvoidTheSchoolLunch() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = viewModel(world)
        vm.setLunch("c-ella", tuesday, "Köttbullar med potatis")
        vm.openDay(tuesday)
        val suggestions = assertNotNull(vm.dayEditor.value).suggestions
        assertEquals("r-kb", suggestions.last().recipe.id, "the same food as lunch goes last")
    }

    @Test
    fun cookDoubleAddsLeftoversNextDay() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = viewModel(world)
        vm.plan(monday, MealSlot.Dinner, "Köttbullar och makaroner", "r-kb", cookDouble = true, servings = null, note = "")
        val tue = vm.state.value.days[1].dinner
        assertEquals(monday, tue?.leftoversFrom)
        assertEquals("Köttbullar och makaroner", tue?.title)
    }

    @Test
    fun moveSwapsDays() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = viewModel(world)
        vm.plan(monday, MealSlot.Dinner, "tacos", null, cookDouble = false, servings = null, note = "")
        vm.plan(monday.plusDays(2), MealSlot.Dinner, "soppa", null, cookDouble = false, servings = null, note = "")
        vm.move(monday, monday.plusDays(2))
        val days = vm.state.value.days
        assertEquals("soppa", days[0].dinner?.title)
        assertEquals("tacos", days[2].dinner?.title)
    }

    @Test
    fun copyLastWeekAndFavoriteWeeks() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = viewModel(world)
        vm.previousWeek()
        assertFalse(vm.state.value.previousWeekPlanned)
        vm.plan(monday.plusDays(-7), MealSlot.Dinner, "fisk", null, cookDouble = false, servings = null, note = "")
        vm.plan(monday.plusDays(-5), MealSlot.Dinner, "Snabb pasta", "r-pasta", cookDouble = false, servings = null, note = "")
        vm.saveFavorite("Vanlig vecka")
        assertEquals(listOf("Vanlig vecka"), vm.state.value.favorites.map { it.name })

        vm.nextWeek()
        assertTrue(vm.state.value.previousWeekPlanned)
        vm.copyPreviousWeek()
        assertEquals("fisk", vm.state.value.days[0].dinner?.title)
        assertEquals("r-pasta", vm.state.value.days[2].dinner?.recipeId)

        vm.nextWeek()
        vm.applyFavorite(vm.state.value.favorites.single())
        assertEquals(2, vm.state.value.plannedCount)
        vm.deleteFavorite(vm.state.value.favorites.single())
        assertTrue(vm.state.value.favorites.isEmpty())
    }

    @Test
    fun helpersCanLookButNotPlan() = runTest {
        val world = TestWorld(role = Role.Caregiver).apply { seed() }
        val vm = viewModel(world)
        assertFalse(vm.state.value.canEdit)
    }
}
