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
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.Role
import se.pusslet.core.model.TaskCategory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Today" is Tuesday 13 October 2026, 08:00 in Stockholm (see [TestWorld]). */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {
    private val saturday = LocalDate(2026, 10, 17)
    private val party = Event(
        id = "e-party",
        title = "Kalas hos Vera",
        start = LocalDateTime(saturday, LocalTime(14, 0)),
        end = LocalDateTime(saturday, LocalTime(16, 0)),
        participantIds = listOf("c-ella"),
    )
    private val partyOccurrence = EventOccurrence(party, party.start, party.end, occurrenceDate = saturday)

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

    @Test
    fun parentsCanChangeThingsFromToday() = runTest {
        val vm = viewModel(TestWorld(Role.Parent))

        val state = vm.state.value
        assertFalse(state.loading)
        assertTrue(state.canEdit)
        assertEquals(LocalDate(2026, 10, 13), state.plan?.date)
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
