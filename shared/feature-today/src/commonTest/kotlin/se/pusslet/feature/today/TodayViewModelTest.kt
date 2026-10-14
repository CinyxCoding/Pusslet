package se.pusslet.feature.today

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Child
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.model.TaskCategory
import se.pusslet.core.today.StoredTodayLayout
import se.pusslet.core.today.TimelineItem
import se.pusslet.core.today.TodayBlock
import se.pusslet.data.repository.Prefs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** "Today" is Tuesday 13 October 2026 (week 42), 08:00 in Stockholm (see [TestWorld]). */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {
    private val tuesday = LocalDate(2026, 10, 13)
    private val saturday = LocalDate(2026, 10, 17)
    private val party = Event(
        id = "e-party",
        title = "Kalas hos Vera",
        start = LocalDateTime(saturday, LocalTime(14, 0)),
        end = LocalDateTime(saturday, LocalTime(16, 0)),
        participantIds = listOf("c-ella"),
        tags = listOf(EventTags.PARTY),
    )
    private val partyOccurrence = EventOccurrence(party, party.start, party.end, occurrenceDate = saturday)

    /** Ella's school today: Johan picks her up at 15:00. */
    private val school = Event(
        id = "e-school",
        title = "Skola",
        start = LocalDateTime(tuesday, LocalTime(8, 15)),
        end = LocalDateTime(tuesday, LocalTime(15, 0)),
        participantIds = listOf("c-ella"),
        pickUp = Handoff("m-johan", LocalTime(15, 0)),
        tags = listOf("skola"),
    )
    private val swimming = Event(
        id = "e-swim",
        title = "Simskola",
        start = LocalDateTime(tuesday, LocalTime(17, 30)),
        end = LocalDateTime(tuesday, LocalTime(18, 15)),
        location = "Eriksdalsbadet",
        participantIds = listOf("c-ella"),
    )
    private val dentist = Event(
        id = "e-dentist",
        title = "Tandläkare",
        start = LocalDateTime(LocalDate(2026, 10, 15), LocalTime(10, 0)),
        end = LocalDateTime(LocalDate(2026, 10, 15), LocalTime(10, 45)),
        participantIds = listOf("c-ella"),
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(world: TestWorld) = TodayViewModel(
        world.family,
        world.calendar,
        world.packing,
        world.meals,
        world.tasks,
        world.lunches,
        world.shopping,
        world.prefs,
        NoWeather,
        world.sessions,
        world.handoffs,
        world.ids,
        world.clock,
        world.zone,
    )

    private fun TestScope.observe(vm: TodayViewModel) {
        val eager = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        eager.launch { vm.state.collect {} }
    }

    private fun TestScope.viewModel(world: TestWorld): TodayViewModel = newViewModel(world).also { observe(it) }

    /** Sara and Johan (parents), Ella (9). */
    private suspend fun TestWorld.family() {
        family.saveMember(Member("m-sara", "Sara", Role.Parent))
        family.saveMember(Member("m-johan", "Johan", Role.Parent))
        family.saveChild(Child("c-ella", "Ella", birthDate = LocalDate(2017, 3, 14)))
    }

    /** Something in every default block: a hand-off, an activity tonight, a task and the dentist on Thursday. */
    private suspend fun TestWorld.busyDay() {
        family()
        calendar.saveEvent(school)
        calendar.saveEvent(swimming)
        calendar.saveEvent(dentist)
        tasks.save(FamilyTask("t-forms", "Lämna blanketten", assigneeId = "m-sara", due = tuesday))
    }

    private suspend fun TestWorld.storeLayout(layout: StoredTodayLayout) = prefs.set(Prefs.TodayLayout, layout)

    @Test
    fun parentsCanChangeThingsFromToday() = runTest {
        val vm = viewModel(TestWorld(Role.Parent))

        val state = vm.state.value
        assertFalse(state.loading)
        assertTrue(state.canEdit)
        assertEquals(LocalDate(2026, 10, 13), state.plan?.date)
    }

    @Test
    fun theDefaultIdagAnswersFirstAndListsTheDefaultSections() = runTest {
        val world = TestWorld()
        world.busyDay()
        val state = viewModel(world).state.value

        val handoff = state.hero.filterIsInstance<HeroRow.Handoff>().single().line
        assertEquals("m-johan", handoff.person?.id)
        assertEquals(listOf(TodayBlock.Timeline, TodayBlock.Tasks, TodayBlock.Upcoming), state.sections)
        assertFalse(state.quiet)
        // One fact once: the pick-up is in Morgonkollen, so "Idag händer" only has the swimming.
        val timeline = state.plan?.timelineFor(state.layout).orEmpty()
        assertTrue(timeline.none { it is TimelineItem.HandoffItem })
        assertEquals(listOf("Simskola"), timeline.filterIsInstance<TimelineItem.EventItem>().map { it.occurrence.event.title })
    }

    @Test
    fun theStoredLayoutHidesBlocksAndOrdersTheSections() = runTest {
        val world = TestWorld()
        world.busyDay()
        world.storeLayout(
            StoredTodayLayout(
                memberId = "m-sara",
                sectionOrder = listOf("upcoming", "timeline", "tasks"),
                visible = mapOf("tasks" to false, "handoffs" to false),
            ),
        )
        val vm = viewModel(world)

        val state = vm.state.value
        assertEquals(listOf(TodayBlock.Upcoming, TodayBlock.Timeline), state.sections, "Att göra is off, Kommande first")
        assertTrue(state.hero.none { it is HeroRow.Handoff })
        // With the Morgonkollen row off, the pick-up moves to "Idag händer".
        assertTrue(state.plan?.timelineFor(state.layout).orEmpty().any { it is TimelineItem.HandoffItem })

        // A change on "Anpassa Idag" applies at once.
        world.storeLayout(StoredTodayLayout(memberId = "m-sara", visible = mapOf("upcoming" to false)))
        assertEquals(listOf(TodayBlock.Timeline, TodayBlock.Tasks), vm.state.value.sections)
    }

    @Test
    fun aLayoutStoredForAnotherMemberIsIgnored() = runTest {
        val world = TestWorld()
        world.busyDay()
        world.storeLayout(StoredTodayLayout(memberId = "m-johan", visible = mapOf("timeline" to false)))

        assertEquals(listOf(TodayBlock.Timeline, TodayBlock.Tasks, TodayBlock.Upcoming), viewModel(world).state.value.sections)
    }

    @Test
    fun theHeaderAndTheProblemsStayWithEveryBlockOff() = runTest {
        val world = TestWorld()
        world.busyDay()
        // Nobody picks Ella up from swimming: a problem today.
        world.calendar.saveEvent(swimming.copy(pickUp = Handoff(null, LocalTime(18, 15))))
        world.storeLayout(StoredTodayLayout(memberId = "m-sara", visible = TodayBlock.entries.associate { it.id to false }))

        val state = viewModel(world).state.value
        val plan = assertNotNull(state.plan)
        assertEquals(42, plan.week.week, "the week is always in the header")
        assertTrue(plan.problems.isNotEmpty(), "what has to be solved can never be hidden")
        assertTrue(state.hero.isEmpty())
        assertTrue(state.sections.isEmpty())
        assertTrue(state.quiet, "Inget mer idag")
    }

    @Test
    fun aCaregiverGetsNoPlusAndNoEditActions() = runTest {
        val world = TestWorld(Role.Caregiver)
        world.busyDay()
        world.meals.setEntry(MealEntry(LocalDate(2026, 10, 6), title = "Tacos"))
        // Kommande looks three days ahead, so the party is on Friday here.
        val friday = LocalDate(2026, 10, 16)
        world.calendar.saveEvent(party.copy(start = LocalDateTime(friday, LocalTime(14, 0)), end = LocalDateTime(friday, LocalTime(16, 0))))
        val state = viewModel(world).state.value

        assertFalse(state.canEdit, "no +, no Jag tar det, no Påminn mig")
        // No dinner, no timeline for helpers, and no weather place to choose (that is the family's setting).
        assertTrue(TodayBlock.Dinner !in state.layout && TodayBlock.Timeline !in state.layout)
        assertTrue(state.hero.none { it == HeroRow.PlanDinner || it == HeroRow.ChoosePlace })
        // The party reminder is still shown in Kommande, without its action (the screen checks canEdit).
        assertTrue(state.plan?.comingUp.orEmpty().any { day -> day.reminders.isNotEmpty() })
    }

    @Test
    fun parentsWithoutAWeatherPlaceAreOfferedToChooseOne() = runTest {
        val world = TestWorld()
        world.family()
        val state = viewModel(world).state.value

        assertEquals(listOf<HeroRow>(HeroRow.ChoosePlace), state.hero)
    }

    @Test
    fun aDinnerPlannedLastWeekOffersPlanningTonightUntilSix() = runTest {
        val morning = TestWorld()
        morning.family()
        assertTrue(viewModel(morning).state.value.hero.none { it == HeroRow.PlanDinner }, "a family that never plans dinner is not nagged")

        morning.meals.setEntry(MealEntry(LocalDate(2026, 10, 6), title = "Tacos"))
        assertTrue(viewModel(morning).state.value.hero.contains(HeroRow.PlanDinner))

        val evening = TestWorld(now = Instant.parse("2026-10-13T16:30:00Z"))
        evening.family()
        evening.meals.setEntry(MealEntry(LocalDate(2026, 10, 6), title = "Tacos"))
        assertTrue(viewModel(evening).state.value.hero.none { it == HeroRow.PlanDinner }, "18:30 is too late to help")
    }

    @Test
    fun tonightsDinnerIsShownInsteadOfThePrompt() = runTest {
        val world = TestWorld()
        world.family()
        world.meals.setEntry(MealEntry(tuesday, title = "Fiskgratäng"))

        val dinner = viewModel(world).state.value.hero.filterIsInstance<HeroRow.Dinner>().single()
        assertEquals("Fiskgratäng", dinner.dinner.title)
    }

    @Test
    fun requestsToMeAreListedButNotMyOwn() = runTest {
        val world = TestWorld()
        world.busyDay()
        val friday = LocalDate(2026, 10, 16)
        val fridaySchool = school.copy(
            id = "e-school-fri",
            start = LocalDateTime(friday, LocalTime(8, 15)),
            end = LocalDateTime(friday, LocalTime(15, 0)),
        )
        world.calendar.saveEvent(fridaySchool)
        world.calendar.saveHandoffRequest(HandoffRequest("r-1", fridaySchool.id, friday, HandoffKind.PickUp, fromMemberId = "m-johan"))
        world.calendar.saveHandoffRequest(HandoffRequest("r-2", school.id, tuesday, HandoffKind.PickUp, fromMemberId = "m-sara"))

        val request = viewModel(world).state.value.requests.single()
        assertEquals("r-1", request.request.id)
        assertEquals("Johan", request.from?.name)
        assertEquals(listOf("Ella"), request.children.map { it.name })
        assertEquals(LocalTime(15, 0), request.time)
    }

    @Test
    fun theShoppingListIsOnlyReadWhenItsBlockIsOn() = runTest {
        val world = TestWorld()
        world.family()
        world.shopping.saveList(ShoppingList("l-week", "Veckohandling"))
        world.shopping.saveItem(ShoppingItem(id = "i-1", listId = "l-week", name = "Mjölk"))
        world.shopping.saveItem(ShoppingItem(id = "i-2", listId = "l-week", name = "Bröd"))
        val vm = viewModel(world)
        assertNull(vm.state.value.plan?.shopping, "off by default")
        assertTrue(TodayBlock.Shopping !in vm.state.value.sections)

        world.storeLayout(StoredTodayLayout(memberId = "m-sara", visible = mapOf("shopping" to true)))
        val shopping = assertNotNull(vm.state.value.plan?.shopping)
        assertEquals("Veckohandling", shopping.listName)
        assertEquals(2, shopping.remaining)
        assertTrue(TodayBlock.Shopping in vm.state.value.sections)
    }

    @Test
    fun aCaregiverIsReadOnlyFromTheFirstFrame() = runTest {
        val vm = newViewModel(TestWorld(Role.Caregiver))
        assertTrue(vm.state.value.loading)
        assertFalse(vm.state.value.canEdit, "the + must not flash by before Idag has loaded")

        observe(vm)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.canEdit)
    }

    @Test
    fun aCaregiverCannotTakeAHandoffOrAddAReminder() = runTest {
        val world = TestWorld(Role.Caregiver)
        world.calendar.saveEvent(party)
        val vm = viewModel(world)

        vm.takeHandoff(partyOccurrence, HandoffKind.PickUp)
        vm.addGiftTask(partyOccurrence, party.title)

        assertNull(world.calendar.event(party.id)?.pickUp, "the rules would refuse it, so nothing is written locally either")
        assertTrue(world.tasks.tasks.first().isEmpty())
    }

    @Test
    fun aParentTakesTheHandoffAndIsRemindedTheDayBefore() = runTest {
        val world = TestWorld(Role.Parent)
        world.calendar.saveEvent(party)
        val vm = viewModel(world)

        vm.takeHandoff(partyOccurrence, HandoffKind.PickUp)
        vm.addGiftTask(partyOccurrence, party.title)

        assertEquals("m-sara", world.calendar.event(party.id)?.pickUp?.personId)
        val task = world.tasks.tasks.first().single()
        assertEquals(party.title, task.title)
        assertEquals(TaskCategory.Gift, task.category)
        assertEquals("m-sara", task.assigneeId)
        assertEquals(LocalDate(2026, 10, 16), task.due)
        assertEquals(party.id, task.linkedEventId)
    }
}
