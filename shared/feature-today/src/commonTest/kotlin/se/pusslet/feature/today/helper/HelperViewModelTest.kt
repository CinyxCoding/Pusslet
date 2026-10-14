package se.pusslet.feature.today.helper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.helper.HelperAnswer
import se.pusslet.core.model.Child
import se.pusslet.core.model.Event
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.Member
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.model.Role
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.today.StoredTodayLayout
import se.pusslet.core.today.TodayBlock
import se.pusslet.data.repository.Prefs
import se.pusslet.data.sync.SyncState
import se.pusslet.data.sync.SyncStatus
import se.pusslet.data.usecase.FirstTimeHints
import se.pusslet.data.usecase.HandoffRequests
import se.pusslet.feature.today.NoWeather
import se.pusslet.feature.today.TestWorld
import se.pusslet.feature.today.helper.HelperViewModel.Companion.waitsForNetwork
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Morfar Bengt's phone on Thursday 8 October 2026, 07:10 in Stockholm (the demo's test clock): he picks up Simon 15:30 and
 * Ella 15:45 today, and Sara asks him about Ella's pick-up on Friday.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HelperViewModelTest {
    private val thursday = LocalDate(2026, 10, 8)
    private val friday = LocalDate(2026, 10, 9)

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime(date, LocalTime(h, m))

    private val preschool = Event(
        "e-forskola-to",
        "Förskola",
        at(thursday, 8),
        at(thursday, 16),
        participantIds = listOf(SIMON),
        dropOff = Handoff(JOHAN, LocalTime(7, 50)),
        pickUp = Handoff(BENGT, LocalTime(15, 30)),
        tags = listOf("förskola"),
    )
    private val school = Event(
        "e-skola-to",
        "Skola & fritids",
        at(thursday, 8, 15),
        at(thursday, 16),
        participantIds = listOf(ELLA),
        dropOff = Handoff(JOHAN, LocalTime(8, 5)),
        pickUp = Handoff(BENGT, LocalTime(15, 45)),
        tags = listOf("skola"),
    )
    private val schoolFriday = school.copy(
        id = "e-skola-fre",
        start = at(friday, 8, 15),
        end = at(friday, 16, 30),
        pickUp = Handoff(SARA, LocalTime(16, 30)),
    )
    private val ask = HandoffRequest(
        id = "r-fre",
        eventId = "e-skola-fre",
        occurrenceDate = friday,
        kind = HandoffKind.PickUp,
        fromMemberId = SARA,
        toMemberId = BENGT,
        createdAt = Instant.parse("2026-10-07T18:00:00Z"),
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun world() = TestWorld(Role.Caregiver, now = Instant.parse("2026-10-08T05:10:00Z"), memberId = BENGT)

    private fun TestScope.viewModel(world: TestWorld, sync: SyncStatus = SyncStatus()): HelperViewModel {
        val vm = HelperViewModel(
            world.family,
            world.calendar,
            world.packing,
            world.tasks,
            world.shopping,
            world.prefs,
            NoWeather,
            world.sessions,
            HandoffRequests(world.calendar, world.sessions, world.ids, world.clock),
            FirstTimeHints(world.prefs),
            flowOf(sync),
            world.clock,
            world.zone,
        )
        val eager = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        eager.launch { vm.state.collect {} }
        return vm
    }

    private suspend fun TestWorld.thursday(request: HandoffRequest? = ask) {
        family.saveMember(Member(SARA, "Sara", Role.Parent))
        family.saveMember(Member(JOHAN, "Johan", Role.Parent))
        family.saveMember(Member(BENGT, "Bengt", Role.Caregiver))
        family.saveChild(Child(ELLA, "Ella", birthDate = LocalDate(2017, 3, 14)))
        family.saveChild(Child(SIMON, "Simon", birthDate = LocalDate(2022, 5, 2)))
        listOf(preschool, school, schoolFriday).forEach { calendar.saveEvent(it) }
        if (request != null) calendar.saveHandoffRequest(request)
    }

    @Test
    fun bengtSeesHisPickUpsAndSarasQuestion() = runTest {
        val world = world()
        world.thursday()
        val state = viewModel(world).state.value

        assertFalse(state.loading)
        val plan = assertNotNull(state.plan)
        assertEquals(listOf("Simon", "Ella"), plan.missions.map { it.children.single().name })
        assertEquals(listOf(LocalTime(15, 30), LocalTime(15, 45)), plan.missions.map { it.time })
        assertEquals("Sara", plan.requests.single().from?.name)
        assertEquals("Bengt", state.me?.name)
        assertEquals(listOf("Sara", "Johan"), state.parents.map { it.name }.sortedDescending())
        assertFalse(state.waitingForNetwork)
    }

    @Test
    fun yesAcceptsWithBengtAsTheOneWhoAnswered() = runTest {
        val world = world()
        world.thursday()
        val vm = viewModel(world)
        val events = world.calendar.events.first()

        vm.accept(assertNotNull(vm.state.value.plan).requests.single())

        val saved = world.calendar.handoffRequests.first().single()
        assertEquals(RequestStatus.Accepted, saved.status)
        assertEquals(BENGT, saved.respondedBy)
        assertEquals(world.clock.now(), saved.respondedAt)
        assertEquals(events, world.calendar.events.first(), "A caregiver never writes events")
        // The card stays with his answer, and Friday's pick-up is his at once.
        val plan = assertNotNull(vm.state.value.plan)
        assertEquals(HelperAnswer.Yes, plan.requests.single().answer)
        assertEquals(friday, plan.upcoming.single().date)
    }

    @Test
    fun noOnARequestToHimDeclinesIt() = runTest {
        val world = world()
        world.thursday()
        val vm = viewModel(world)
        val events = world.calendar.events.first()

        vm.decline(assertNotNull(vm.state.value.plan).requests.single())

        val saved = world.calendar.handoffRequests.first().single()
        assertEquals(RequestStatus.Declined, saved.status)
        assertEquals(BENGT, saved.respondedBy)
        assertEquals(events, world.calendar.events.first())
        assertEquals(HelperAnswer.No, vm.state.value.plan?.requests?.single()?.answer)
    }

    @Test
    fun noOnARequestToAnyoneOnlyRecordsHisNo() = runTest {
        val world = world()
        world.thursday(ask.copy(toMemberId = null))
        val vm = viewModel(world)
        val events = world.calendar.events.first()

        vm.decline(assertNotNull(vm.state.value.plan).requests.single())

        val saved = world.calendar.handoffRequests.first().single()
        assertEquals(RequestStatus.Pending, saved.status, "The others can still answer")
        assertTrue(BENGT in saved.declines)
        assertNull(saved.respondedBy)
        assertEquals(events, world.calendar.events.first())
    }

    @Test
    fun changeAnswerOpensTheQuestionAgain() = runTest {
        val world = world()
        world.thursday(ask.copy(status = RequestStatus.Accepted, respondedBy = BENGT, respondedAt = Instant.parse("2026-10-07T19:00:00Z")))
        val vm = viewModel(world)

        vm.changeAnswer(assertNotNull(vm.state.value.plan).requests.single())

        assertEquals(RequestStatus.Pending, world.calendar.handoffRequests.first().single().status)
        assertNull(vm.state.value.plan?.requests?.single()?.answer)
    }

    @Test
    fun cannotMakeAsksTheWholeFamilyAndChangesNoEvent() = runTest {
        val world = world()
        world.thursday(request = null)
        val vm = viewModel(world)
        val events = world.calendar.events.first()

        vm.cannotMake(assertNotNull(vm.state.value.plan).cannotMakeCandidates.take(1))

        val asked = world.calendar.handoffRequests.first().single()
        assertEquals(BENGT, asked.fromMemberId)
        assertNull(asked.toMemberId)
        assertEquals(RequestStatus.Pending, asked.status)
        assertEquals("e-forskola-to", asked.eventId)
        assertEquals(events, world.calendar.events.first())
        val plan = assertNotNull(vm.state.value.plan)
        assertTrue(plan.missions.first().waitingForTakeover)
        assertEquals(listOf(ELLA), plan.cannotMakeCandidates.map { it.children.single().id })
    }

    @Test
    fun tickingPackingAndTasksWritesOnlyThose() = runTest {
        val world = world()
        world.thursday(request = null)
        world.tasks.save(FamilyTask("t-lasning", "Läsa 20 minuter", rrule = "FREQ=DAILY", sharedWithCaregivers = true))
        val vm = viewModel(world)
        val events = world.calendar.events.first()

        // A shared task nobody has, on a day he picks up.
        val task = assertNotNull(vm.state.value.plan).tasks.single()
        vm.setTaskDone(task, true)

        assertTrue(world.tasks.tasks.first().single().completions.containsKey(thursday.toString()))
        assertTrue(vm.state.value.plan?.tasks.orEmpty().isEmpty())
        assertEquals(events, world.calendar.events.first())
    }

    @Test
    fun theHomeScreenFollowsHisChoices() = runTest {
        val world = world()
        // He has said yes to Friday, so Kommande has a day.
        world.thursday(ask.copy(status = RequestStatus.Accepted, respondedBy = BENGT, respondedAt = Instant.parse("2026-10-07T19:00:00Z")))
        world.tasks.save(FamilyTask("t-lasning", "Läsa 20 minuter", rrule = "FREQ=DAILY", sharedWithCaregivers = true))
        world.shopping.saveList(ShoppingList("l-vecka", "Veckohandling"))
        world.shopping.saveItem(ShoppingItem("i1", "l-vecka", "Mjölk"))
        val vm = viewModel(world)

        // Default: Att göra and Kommande on, no shopping row.
        assertEquals(listOf(TodayBlock.Tasks, TodayBlock.Upcoming), vm.state.value.sections)
        assertNull(vm.state.value.shopping)
        assertTrue(vm.state.value.showWeather)

        world.prefs.set(
            Prefs.TodayLayout,
            StoredTodayLayout(memberId = BENGT, visible = mapOf("tasks" to false, "shopping" to true, "weather" to false)),
        )
        val state = vm.state.value
        assertEquals(listOf(TodayBlock.Upcoming), state.sections, "Att göra is off")
        assertEquals(1, state.shopping?.remaining)
        assertFalse(state.showWeather)
        // The questions and Idag can never be hidden.
        assertEquals(1, state.plan?.requests?.size)
        assertEquals(2, state.plan?.missions?.size)
    }

    @Test
    fun theFirstTimeHintShowsUntilJagForstar() = runTest {
        val world = world()
        world.thursday()
        val vm = viewModel(world)
        assertTrue(vm.state.value.showHint)

        vm.dismissHint()

        assertFalse(vm.state.value.showHint)
        assertFalse(viewModel(world).state.value.showHint, "Gone for good on this phone")
    }

    @Test
    fun answersWaitForTheNetworkOnlyWhenTheyCannotBeSent() = runTest {
        assertTrue(SyncStatus(SyncState.Offline).waitsForNetwork())
        assertTrue(SyncStatus(SyncState.Connecting, pendingWrites = 1).waitsForNetwork())
        assertFalse(SyncStatus(SyncState.Synced).waitsForNetwork())
        assertFalse(SyncStatus(SyncState.Disabled, pendingWrites = 3).waitsForNetwork(), "A family without a backend never waits")

        val world = world()
        world.thursday()
        assertTrue(viewModel(world, SyncStatus(SyncState.Offline)).state.value.waitingForNetwork)
    }

    private companion object {
        const val SARA = "m-sara"
        const val JOHAN = "m-johan"
        const val BENGT = "m-bengt"
        const val ELLA = "c-ella"
        const val SIMON = "c-simon"
    }
}
