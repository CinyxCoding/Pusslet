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
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.LunchProviderType
import se.pusslet.core.model.LunchSource
import se.pusslet.core.model.MainComponent
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealKind
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Member
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeTag
import se.pusslet.core.model.Role
import se.pusslet.core.recipes.IngredientParser
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
            world.nutrition,
            world.ids,
            world.clock,
            world.zone,
        )
        val eager = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        eager.launch { vm.state.collect {} }
        eager.launch { vm.dayEditor.collect {} }
        eager.launch { vm.dayView.collect {} }
        return vm
    }

    /** Receipts as the screen would get them. */
    private fun TestScope.receipts(vm: MealsViewModel): List<MealsMessage> {
        val messages = mutableListOf<MealsMessage>()
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch {
            vm.messages.collect { messages += it }
        }
        return messages
    }

    private fun MealsViewModel.dinner(date: LocalDate) = state.value.days.firstOrNull { it.date == date }?.dinner

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
    fun plusOpensTheFirstDayStillToPlan() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = viewModel(world)
        vm.openNextToPlan()
        assertEquals(tuesday, vm.dayEditor.value?.date, "today when tonight is not planned")
        assertEquals(MealSlot.Dinner, vm.dayEditor.value?.slot)
        vm.closeDay()

        vm.plan(tuesday, MealSlot.Dinner, "tacos", null, cookDouble = false, servings = null, note = "")
        vm.openNextToPlan()
        assertEquals(tuesday.plusDays(1), vm.dayEditor.value?.date, "Monday is past, so Wednesday")
        vm.closeDay()

        (2..5).forEach { vm.plan(tuesday.plusDays(it), MealSlot.Dinner, "soppa", null, cookDouble = false, servings = null, note = "") }
        vm.plan(tuesday.plusDays(1), MealSlot.Dinner, "fisk", null, cookDouble = false, servings = null, note = "")
        vm.openNextToPlan()
        assertEquals(tuesday, vm.dayEditor.value?.date, "everything from today is planned: today")
        vm.closeDay()

        vm.nextWeek()
        vm.openNextToPlan()
        assertEquals(monday.plusDays(7), vm.dayEditor.value?.date, "another week starts on Monday")
    }

    @Test
    fun nextDayToPlanFollowsTheRules() {
        fun day(offset: Int, planned: Boolean, today: Boolean = false) = DayPlan(
            date = monday.plusDays(offset),
            entries = if (planned) mapOf(MealSlot.Dinner to MealEntry(monday.plusDays(offset), title = "mat")) else emptyMap(),
            lunches = emptyList(),
            busy = emptyList(),
            holidays = emptyList(),
            redDay = false,
            isToday = today,
        )
        val otherWeek = listOf(day(0, planned = true), day(1, planned = false), day(2, planned = false))
        assertEquals(monday.plusDays(1), nextDayToPlan(otherWeek))
        val thisWeek = listOf(day(0, planned = false), day(1, planned = true, today = true), day(2, planned = true))
        assertEquals(monday.plusDays(1), nextDayToPlan(thisWeek), "all planned from today: today")
        assertEquals(monday, nextDayToPlan(listOf(day(0, planned = true), day(1, planned = true))), "all planned: Monday")
        assertNull(nextDayToPlan(emptyList()))
    }

    @Test
    fun noSchoolOnWeekendsHolidaysBreaksAndStudyDays() = runTest {
        val world = TestWorld().apply { seed() }
        world.family.saveChild(Child("c-simon", "Simon", lunch = LunchSource(LunchProviderType.Manual)))
        val wednesday = tuesday.plusDays(1)
        val thursday = tuesday.plusDays(2)
        world.calendar.saveEvent(
            Event(
                id = "e-studiedag",
                title = "Studiedag",
                start = LocalDateTime(wednesday, LocalTime(0, 0)),
                end = LocalDateTime(thursday, LocalTime(0, 0)),
                allDay = true,
                participantIds = listOf("c-ella"),
                tags = listOf(EventTags.STUDY_DAY),
            ),
        )
        world.calendar.saveEvent(
            Event(
                id = "e-lov",
                title = "Höstlov",
                start = LocalDateTime(thursday, LocalTime(0, 0)),
                end = LocalDateTime(thursday.plusDays(1), LocalTime(0, 0)),
                allDay = true,
                tags = listOf("Lov"),
            ),
        )
        val vm = viewModel(world)
        val days = vm.state.value.days
        assertEquals(emptySet(), days[1].noSchool, "an ordinary Tuesday")
        assertEquals(setOf("c-ella"), days[2].noSchool, "Ella's study day")
        assertEquals(setOf("c-ella", "c-simon"), days[3].noSchool, "a break without participants is for everyone")
        assertEquals(setOf("c-ella", "c-simon"), days[5].noSchool, "Saturday")
        assertEquals(setOf("c-ella", "c-simon"), days[6].noSchool, "Sunday")

        val children = listOf(Child("c-ella", "Ella"))
        assertEquals(setOf("c-ella"), childrenWithoutSchool(LocalDate(2026, 5, 14), children, emptyList()), "Kristi himmelsfärd")
    }

    @Test
    fun childrenWithTheSameLunchShareOneRow() = runTest {
        val world = TestWorld().apply { seed() }
        world.family.saveChild(Child("c-simon", "Simon", lunch = LunchSource(LunchProviderType.Manual)))
        world.family.saveChild(Child("c-ida", "Ida", lunch = LunchSource(LunchProviderType.Manual)))
        val vm = viewModel(world)
        vm.setLunch("c-ella", tuesday, "Köttbullar med potatis\nVegobullar")
        vm.setLunch("c-simon", tuesday, "köttbullar med potatis")
        vm.setLunch("c-ida", tuesday, "Fiskgratäng")
        val lunches = vm.state.value.days[1].lunches

        val rows = groupLunches(lunches)
        assertEquals(listOf(listOf("Ella", "Simon"), listOf("Ida")), rows.map { row -> row.children.map { it.firstName } })
        assertEquals("Köttbullar med potatis", rows.first().dishes.first())

        val sheetRows = groupLunches(lunches, wholeMenu = true)
        assertEquals(3, sheetRows.size, "the sheet lists every dish, and Simon's menu has only one")
    }

    @Test
    fun helpersCanLookButNotPlan() = runTest {
        val world = TestWorld(role = Role.Caregiver).apply { seed() }
        val vm = viewModel(world)
        assertFalse(vm.state.value.canEdit)
    }

    @Test
    fun weekNutritionAveragesPlannedRecipes() = runTest {
        val world = TestWorld().apply { seed() }
        world.recipes.save(Recipe("r-fars", "Färsbiffar", 4, ingredients = listOf(IngredientParser.parse("500 g nötfärs"))))
        val vm = viewModel(world)
        vm.calculateWeekNutrition()
        assertEquals(WeekNutritionState.NoRecipes, vm.weekNutrition.value)

        vm.plan(monday, MealSlot.Dinner, "Färsbiffar", "r-fars", cookDouble = true, servings = null, note = "")
        vm.plan(tuesday.plusDays(1), MealSlot.Dinner, "pizza", null, cookDouble = false, servings = null, note = "")
        vm.calculateWeekNutrition()
        val ready = assertIs<WeekNutritionState.Ready>(vm.weekNutrition.value)
        assertEquals(1, ready.week.meals, "leftovers and free text do not count")
        assertEquals(250.0, ready.week.averagePerPortion.energyKcal ?: 0.0, 0.5)

        vm.nextWeek()
        assertEquals(WeekNutritionState.Idle, vm.weekNutrition.value)
    }

    @Test
    fun planningCanBeUndone() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = viewModel(world)
        val messages = receipts(vm)
        vm.plan(monday, MealSlot.Dinner, "tacos", null, cookDouble = false, servings = null, note = "")
        val planned = assertIs<MealsMessage.Planned>(messages.single())
        assertEquals(monday, planned.date)
        assertEquals("tacos", planned.title)

        vm.plan(monday, MealSlot.Dinner, "Ugnsbakad lax", "r-lax", cookDouble = false, servings = null, note = "")
        assertEquals("Ugnsbakad lax", assertIs<MealsMessage.Planned>(messages.last()).title)
        vm.undo(messages.last().undo)
        assertEquals("tacos", vm.dinner(monday)?.title, "Ångra brings back what the day had")
        vm.undo(messages.first().undo)
        assertNull(vm.dinner(monday))

        vm.plan(monday, MealSlot.Dinner, "tacos", null, cookDouble = false, servings = null, note = "")
        val before = messages.size
        vm.plan(monday, MealSlot.Dinner, "tacos", null, cookDouble = false, servings = null, note = "")
        assertEquals(before, messages.size, "nothing changed, so no receipt")
    }

    @Test
    fun undoingCookDoubleTakesTheLeftoversToo() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = viewModel(world)
        val messages = receipts(vm)
        vm.plan(monday, MealSlot.Dinner, "Köttbullar och makaroner", "r-kb", cookDouble = true, servings = null, note = "")
        assertEquals(monday, vm.dinner(tuesday)?.leftoversFrom)
        vm.undo(messages.single().undo)
        assertNull(vm.dinner(monday))
        assertNull(vm.dinner(tuesday), "the leftovers go with it")

        // Sunday's double batch puts leftovers on next Monday, in the next week.
        val sunday = monday.plusDays(6)
        vm.plan(sunday, MealSlot.Dinner, "Köttbullar och makaroner", "r-kb", cookDouble = true, servings = null, note = "")
        assertNotNull(world.meals.getWeek(sunday.plusDays(1).isoWeek).entry(sunday.plusDays(1)))
        vm.undo(messages.last().undo)
        assertNull(world.meals.getWeek(sunday.plusDays(1).isoWeek).entry(sunday.plusDays(1)))
    }

    @Test
    fun clearCanBeUndoneWithoutPlanningLeftoversAgain() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = viewModel(world)
        val messages = receipts(vm)
        vm.plan(monday, MealSlot.Dinner, "Köttbullar och makaroner", "r-kb", cookDouble = true, servings = null, note = "")
        vm.clear(tuesday, MealSlot.Dinner)
        assertEquals("Köttbullar och makaroner", assertIs<MealsMessage.Removed>(messages.last()).title)
        vm.clear(monday, MealSlot.Dinner)
        assertNull(vm.dinner(monday))

        vm.undo(messages.last().undo)
        assertTrue(vm.dinner(monday)?.cookDouble == true, "Monday is back as it was")
        assertNull(vm.dinner(tuesday), "the leftovers were removed before, and stay removed")
    }

    @Test
    fun moveCanBeUndone() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = viewModel(world)
        val messages = receipts(vm)
        val wednesday = monday.plusDays(2)
        vm.plan(monday, MealSlot.Dinner, "tacos", null, cookDouble = false, servings = null, note = "")
        vm.plan(wednesday, MealSlot.Dinner, "soppa", null, cookDouble = false, servings = null, note = "")
        vm.move(monday, wednesday)
        val moved = assertIs<MealsMessage.Planned>(messages.last())
        assertEquals(wednesday, moved.date)
        assertEquals("tacos", moved.title)

        vm.undo(moved.undo)
        assertEquals("tacos", vm.dinner(monday)?.title)
        assertEquals("soppa", vm.dinner(wednesday)?.title)
    }

    @Test
    fun favoriteWeekAndCopyingCanBeUndone() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = viewModel(world)
        val messages = receipts(vm)
        vm.plan(monday, MealSlot.Dinner, "fisk", null, cookDouble = false, servings = null, note = "")
        vm.plan(monday.plusDays(2), MealSlot.Dinner, "Snabb pasta", "r-pasta", cookDouble = false, servings = null, note = "")
        vm.saveFavorite("Vanlig vecka")

        vm.nextWeek()
        val nextMonday = monday.plusDays(7)
        assertTrue(vm.state.value.offerCopyPrevious, "an empty week with last week planned")
        vm.copyPreviousWeek()
        val copied = assertIs<MealsMessage.CopiedPreviousWeek>(messages.last())
        assertEquals("fisk", vm.dinner(nextMonday)?.title)
        assertFalse(vm.state.value.offerCopyPrevious)
        vm.undo(copied.undo)
        assertTrue(vm.state.value.weekIsEmpty)

        vm.plan(nextMonday, MealSlot.Dinner, "pizza", null, cookDouble = false, servings = null, note = "")
        vm.applyFavorite(vm.state.value.favorites.single())
        val applied = assertIs<MealsMessage.FavoriteApplied>(messages.last())
        assertEquals("Vanlig vecka", applied.name)
        assertEquals("fisk", vm.dinner(nextMonday)?.title, "the favourite replaces the day")
        vm.undo(applied.undo)
        assertEquals("pizza", vm.dinner(nextMonday)?.title)
        assertEquals(1, vm.state.value.plannedCount)
    }

    @Test
    fun anEmptyWeekOffersPlanningAndCopyingOnlyWithHistory() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = viewModel(world)
        with(vm.state.value) {
            assertTrue(weekIsEmpty)
            assertTrue(offerPlanWeek, "\"Planera veckan\"")
            assertFalse(offerCopyPrevious, "nothing planned before: no \"Kopiera förra veckan\"")
        }

        // "Planera veckan" is the round +: tonight's sheet.
        vm.openNextToPlan()
        assertEquals(tuesday, vm.dayEditor.value?.date)
        vm.closeDay()

        vm.plan(monday.plusDays(-7), MealSlot.Dinner, "fisk", null, cookDouble = false, servings = null, note = "")
        with(vm.state.value) {
            assertTrue(weekIsEmpty)
            assertTrue(offerCopyPrevious, "last week has something to copy")
        }
        // Two weeks ahead, the week before is empty again.
        vm.nextWeek()
        vm.nextWeek()
        assertTrue(vm.state.value.offerPlanWeek)
        assertFalse(vm.state.value.offerCopyPrevious)

        vm.thisWeek()
        vm.plan(monday, MealSlot.Dinner, "tacos", null, cookDouble = false, servings = null, note = "")
        with(vm.state.value) {
            assertFalse(weekIsEmpty)
            assertFalse(offerPlanWeek, "a planned week has no empty state")
            assertFalse(offerCopyPrevious)
        }

        // Helpers see the sentence only.
        val helper = TestWorld(role = Role.Caregiver)
        helper.meals.setEntry(MealEntry(monday.plusDays(-7), title = "fisk"))
        val helperVm = viewModel(helper)
        with(helperVm.state.value) {
            assertTrue(weekIsEmpty)
            assertFalse(offerPlanWeek)
            assertFalse(offerCopyPrevious)
        }
    }

    @Test
    fun copyingLastWeekNeverOverwritesAndUndoRestores() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = viewModel(world)
        val messages = receipts(vm)
        val lastMonday = monday.plusDays(-7)
        vm.plan(lastMonday, MealSlot.Dinner, "fisk", null, cookDouble = false, servings = null, note = "")
        vm.plan(lastMonday.plusDays(2), MealSlot.Dinner, "Snabb pasta", "r-pasta", cookDouble = false, servings = null, note = "")
        vm.plan(lastMonday.plusDays(3), MealSlot.Dinner, "soppa", null, cookDouble = false, servings = null, note = "")
        val wednesday = monday.plusDays(2)
        val thursday = monday.plusDays(3)
        vm.plan(wednesday, MealSlot.Dinner, "tacos", null, cookDouble = false, servings = null, note = "")

        vm.copyPreviousWeek()
        val copied = assertIs<MealsMessage.CopiedPreviousWeek>(messages.last())
        assertEquals("fisk", vm.dinner(monday)?.title, "the same weekday")
        assertEquals("tacos", vm.dinner(wednesday)?.title, "a planned day keeps its meal")
        assertEquals("soppa", vm.dinner(thursday)?.title)
        assertEquals(3, vm.state.value.plannedCount)
        assertEquals(setOf(monday, thursday), copied.undo.meals.map { it.date }.toSet(), "only the copied days change")

        vm.undo(copied.undo)
        assertNull(vm.dinner(monday))
        assertNull(vm.dinner(thursday))
        assertEquals("tacos", vm.dinner(wednesday)?.title, "Ångra leaves the day that was planned before")
        assertEquals("fisk", world.meals.getWeek(lastMonday.isoWeek).entry(lastMonday, MealSlot.Dinner)?.title, "last week untouched")

        // Copying again and changing a copied day: Ångra takes back only what is still as copied.
        vm.copyPreviousWeek()
        val again = assertIs<MealsMessage.CopiedPreviousWeek>(messages.last())
        vm.plan(thursday, MealSlot.Dinner, "pizza", null, cookDouble = false, servings = null, note = "")
        vm.undo(again.undo)
        assertNull(vm.dinner(monday))
        assertEquals("pizza", vm.dinner(thursday)?.title)
        assertEquals("tacos", vm.dinner(wednesday)?.title)
    }

    @Test
    fun undoLeavesWhatWasChangedAgainSince() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = viewModel(world)
        val messages = receipts(vm)
        vm.plan(monday, MealSlot.Dinner, "tacos", null, cookDouble = false, servings = null, note = "")
        vm.plan(monday, MealSlot.Dinner, "soppa", null, cookDouble = false, servings = null, note = "")
        vm.undo(messages.first().undo)
        assertEquals("soppa", vm.dinner(monday)?.title)
    }

    @Test
    fun aTapOnASuggestionPlansItAndClosesTheSheet() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = viewModel(world)
        val messages = receipts(vm)
        vm.openNextToPlan()
        val editor = assertNotNull(vm.dayEditor.value)
        assertEquals(tuesday, editor.date)
        assertEquals(tuesday, editor.today)
        val suggestion = editor.suggestions.first()

        vm.pickSuggestion(suggestion.recipe)
        assertNull(vm.dayEditor.value, "the sheet closes")
        assertEquals(suggestion.recipe.id, vm.dinner(tuesday)?.recipeId)
        val receipt = assertIs<MealsMessage.Planned>(messages.single())
        assertEquals(tuesday, receipt.date)
        assertEquals(suggestion.recipe.title, receipt.title)
    }

    @Test
    fun theSheetKeepsPortionsOnlyForTheSameRecipe() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = viewModel(world)
        vm.plan(tuesday, MealSlot.Dinner, "Ugnsbakad lax", "r-lax", cookDouble = false, servings = 6, note = "")
        vm.openDay(tuesday)
        vm.saveDay("Ugnsbakad lax", "r-lax", cookDouble = false, note = "med potatis")
        assertEquals(6, vm.dinner(tuesday)?.servings)
        assertEquals("med potatis", vm.dinner(tuesday)?.note)

        vm.openDay(tuesday)
        vm.saveDay("tacos", null, cookDouble = false, note = "")
        assertNull(vm.dinner(tuesday)?.servings, "another dish starts from its own portions")

        vm.openDay(tuesday)
        vm.moveDay(tuesday.plusDays(1))
        assertNull(vm.dayEditor.value)
        assertEquals("tacos", vm.dinner(tuesday.plusDays(1))?.title)
        vm.openDay(tuesday.plusDays(1))
        vm.clearDay()
        assertNull(vm.dinner(tuesday.plusDays(1)))
    }

    @Test
    fun aPlannedMealOpensTheReadView() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = viewModel(world)
        vm.setLunch("c-ella", tuesday, "Köttbullar med potatis")
        vm.plan(tuesday, MealSlot.Dinner, "Köttbullar och makaroner", "r-kb", cookDouble = false, servings = null, note = "")

        vm.viewDay(tuesday)
        val view = assertNotNull(vm.dayView.value)
        assertEquals("Köttbullar och makaroner", view.title)
        assertEquals("r-kb", view.recipe?.id)
        assertEquals(setOf("köttbullar"), view.lunches.single().clash)
        assertTrue(view.canEdit)
        assertNull(vm.dayEditor.value)

        vm.editViewed()
        assertNull(vm.dayView.value)
        assertEquals(tuesday, vm.dayEditor.value?.date, "Ändra opens the planning sheet for the same day")
        vm.closeDay()

        vm.viewDay(tuesday)
        vm.clear(tuesday, MealSlot.Dinner)
        assertNull(vm.dayView.value, "nothing left to show")
        vm.viewDay(monday)
        assertNull(vm.dayView.value, "an empty day has no read view")
    }

    @Test
    fun helpersGetTheReadViewWithoutChanging() = runTest {
        val helper = TestWorld(role = Role.Caregiver)
        helper.meals.setEntry(MealEntry(tuesday, title = "tacos"))
        helper.family.saveChild(Child("c-ella", "Ella", lunch = LunchSource(LunchProviderType.Manual)))
        val vm = viewModel(helper)
        vm.viewDay(tuesday)
        assertFalse(assertNotNull(vm.dayView.value).canEdit)
        assertTrue(vm.state.value.manualLunchChildren.isEmpty(), "helpers do not fill in lunches")
        assertFalse(vm.state.value.showFillLunch)
        assertFalse(vm.state.value.offerCopyPrevious)
    }

    @Test
    fun schoolLunchesAreFilledInForTheWeek() = runTest {
        val world = TestWorld().apply { seed() }
        world.family.saveChild(Child("c-simon", "Simon", lunch = LunchSource(LunchProviderType.Manual)))
        world.family.saveChild(Child("c-ida", "Ida"))
        val vm = viewModel(world)
        assertEquals(listOf("Ella", "Simon"), vm.state.value.manualLunchChildren.map { it.firstName })
        assertTrue(vm.state.value.showFillLunch, "one row for the week")

        vm.setWeekLunches(listOf("c-ella", "c-simon"), mapOf(monday to "Fiskgratäng\n Potatis ", tuesday to "Pannkakor"))
        val days = vm.state.value.days
        assertEquals(listOf("Ella", "Simon"), days[0].lunches.map { it.child.firstName })
        assertEquals(listOf("Fiskgratäng", "Potatis"), days[0].lunches.first().dishes)
        assertEquals(1, groupLunches(days[1].lunches).size, "the same lunch is one row")

        vm.setWeekLunches(listOf("c-simon"), mapOf(tuesday to ""))
        assertEquals(listOf("Ella"), vm.state.value.days[1].lunches.map { it.child.firstName }, "blank removes Simon's")
    }

    // region kinds and "Vem lagar"

    private suspend fun TestWorld.withJohan() {
        seed()
        family.saveMember(Member("m-johan", "Johan"))
    }

    @Test
    fun takeawayAndNoCookingSaveWithoutATitle() = runTest {
        val world = TestWorld().apply { withJohan() }
        val vm = viewModel(world)
        val messages = receipts(vm)

        vm.openDay(monday)
        vm.saveDay("", null, cookDouble = false, note = "", kind = MealKind.Takeaway, kindLabel = "Hämtmat")
        assertNull(vm.dayEditor.value, "the sheet closes")
        val takeaway = assertNotNull(vm.dinner(monday))
        assertEquals(MealKind.Takeaway, takeaway.kind)
        assertEquals("Hämtmat", takeaway.title, "the kind's label is the title, so every place that shows the meal reads well")
        assertEquals("Hämtmat", assertIs<MealsMessage.Planned>(messages.last()).title)

        vm.openDay(tuesday)
        vm.saveDay("  Hos mormor ", null, cookDouble = false, note = "", kind = MealKind.NoCooking, kindLabel = "Ingen matlagning")
        assertEquals("Hos mormor", vm.dinner(tuesday)?.title)
        assertEquals(MealKind.NoCooking, vm.dinner(tuesday)?.kind)

        vm.openDay(tuesday.plusDays(1))
        vm.saveDay("", null, cookDouble = false, note = "", kind = MealKind.Cooked, kindLabel = "")
        assertNotNull(vm.dayEditor.value, "cooking still needs to say what")
        assertNull(vm.dinner(tuesday.plusDays(1)))
        vm.saveDay("tacos", null, cookDouble = false, note = "")
        assertEquals(MealKind.Cooked, vm.dinner(tuesday.plusDays(1))?.kind)
    }

    @Test
    fun takeawayKeepsNoRecipeDoubleBatchOrCook() = runTest {
        val world = TestWorld().apply { withJohan() }
        val vm = viewModel(world)
        vm.openDay(monday)
        vm.saveDay("Pizza", "r-lax", cookDouble = true, note = "", kind = MealKind.Takeaway, cookId = "m-johan", kindLabel = "Hämtmat")
        val entry = assertNotNull(vm.dinner(monday))
        assertEquals("Pizza", entry.title)
        assertNull(entry.recipeId, "no ingredients for takeaway, even if a recipe was chosen before switching")
        assertNull(entry.cookId)
        assertFalse(entry.cookDouble)
        assertNull(vm.dinner(tuesday), "no leftovers")

        vm.calculateWeekNutrition()
        assertEquals(WeekNutritionState.NoRecipes, vm.weekNutrition.value)
    }

    @Test
    fun theCookIsSetAndCleared() = runTest {
        val world = TestWorld().apply { withJohan() }
        val vm = viewModel(world)
        vm.openDay(tuesday)
        val editor = assertNotNull(vm.dayEditor.value)
        assertEquals(setOf("Johan", "Sara"), editor.adults.map { it.name }.toSet(), "the adults, never the children")

        vm.saveDay("Ugnsbakad lax", "r-lax", cookDouble = false, note = "", cookId = "m-johan")
        assertEquals("m-johan", vm.dinner(tuesday)?.cookId)
        assertEquals("Johan", vm.state.value.people["m-johan"]?.name)
        assertEquals("m-sara", vm.state.value.me)

        vm.viewDay(tuesday)
        assertEquals("Johan", vm.dayView.value?.cook?.name)
        assertFalse(assertNotNull(vm.dayView.value).cookIsMe)
        vm.closeView()

        vm.openDay(tuesday)
        vm.saveDay("Ugnsbakad lax", "r-lax", cookDouble = false, note = "", cookId = "m-sara")
        vm.viewDay(tuesday)
        assertTrue(assertNotNull(vm.dayView.value).cookIsMe, "\"Du lagar\"")
        vm.closeView()

        vm.openDay(tuesday)
        vm.saveDay("Ugnsbakad lax", "r-lax", cookDouble = false, note = "", cookId = null)
        assertNull(vm.dinner(tuesday)?.cookId, "\"Ingen vald\"")
        assertEquals("r-lax", vm.dinner(tuesday)?.recipeId)
    }

    @Test
    fun aSuggestionKeepsTheChosenCook() = runTest {
        val world = TestWorld().apply { withJohan() }
        val vm = viewModel(world)
        vm.openDay(tuesday)
        val suggestion = assertNotNull(vm.dayEditor.value).suggestions.first()
        vm.pickSuggestion(suggestion.recipe, cookDouble = false, note = "", cookId = "m-johan")
        assertEquals("m-johan", vm.dinner(tuesday)?.cookId)
        assertEquals(MealKind.Cooked, vm.dinner(tuesday)?.kind)
    }

    @Test
    fun undoRestoresKindAndCook() = runTest {
        val world = TestWorld().apply { withJohan() }
        val vm = viewModel(world)
        val messages = receipts(vm)
        vm.openDay(monday)
        vm.saveDay("Tacos", null, cookDouble = false, note = "", cookId = "m-johan")
        vm.openDay(monday)
        vm.saveDay("", null, cookDouble = false, note = "", kind = MealKind.Takeaway, kindLabel = "Hämtmat")
        assertEquals(MealKind.Takeaway, vm.dinner(monday)?.kind)

        vm.undo(messages.last().undo)
        val back = assertNotNull(vm.dinner(monday))
        assertEquals("Tacos", back.title)
        assertEquals(MealKind.Cooked, back.kind)
        assertEquals("m-johan", back.cookId, "Ångra brings back who cooks too")

        vm.undo(messages.first().undo)
        assertNull(vm.dinner(monday))
    }

    @Test
    fun aMovedMealKeepsItsKindAndCook() = runTest {
        val world = TestWorld().apply { withJohan() }
        val vm = viewModel(world)
        vm.plan(monday, MealSlot.Dinner, "Pizza", null, cookDouble = false, servings = null, note = "", kind = MealKind.Takeaway)
        vm.plan(tuesday, MealSlot.Dinner, "Tacos", null, cookDouble = false, servings = null, note = "", cookId = "m-johan")
        vm.move(monday, tuesday)
        assertEquals(MealKind.Takeaway, vm.dinner(tuesday)?.kind)
        assertEquals("m-johan", vm.dinner(monday)?.cookId)
    }

    // endregion
}
