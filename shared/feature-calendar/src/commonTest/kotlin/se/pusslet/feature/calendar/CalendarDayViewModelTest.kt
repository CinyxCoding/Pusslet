package se.pusslet.feature.calendar

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
import se.pusslet.core.model.Child
import se.pusslet.core.model.Event
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.Member
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.data.repository.Prefs
import se.pusslet.data.usecase.HandoffAssigner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The day screen (ADR-033); "today" is Tuesday 13 October 2026 (see [TestWorld]). */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarDayViewModelTest {
    private val tuesday = LocalDate(2026, 10, 13)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(world: TestWorld): CalendarDayViewModel {
        val vm = CalendarDayViewModel(
            world.calendar,
            world.family,
            world.prefs,
            world.sessions,
            HandoffAssigner(world.calendar),
            world.clock,
            world.zone,
        )
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { vm.state.collect {} }
        return vm
    }

    private suspend fun TestWorld.seed() {
        family.saveMember(Member("m-sara", "Sara", Role.Parent, MemberColor.Sage))
        family.saveChild(Child("c-simon", "Simon", MemberColor.Ochre))
        calendar.saveEvent(
            Event(
                "e-forskola",
                "Förskola",
                LocalDateTime(tuesday, LocalTime(8, 0)),
                LocalDateTime(tuesday, LocalTime(16, 0)),
                participantIds = listOf("c-simon"),
                pickUp = Handoff(null, LocalTime(15, 30)),
                tags = listOf("förskola"),
            ),
        )
    }

    @Test
    fun showsTheDayAndStepsWithTheArrows() = runTest {
        val world = TestWorld()
        world.seed()
        val vm = viewModel(world)

        vm.load(tuesday)
        assertEquals(tuesday, vm.state.value.day?.date)
        assertEquals("Simon", vm.state.value.day?.routines?.single()?.children?.single()?.name)

        vm.step(forward = true)
        // Loading again (the screen recomposes) keeps the day stepped to.
        vm.load(tuesday)
        assertEquals(LocalDate(2026, 10, 14), vm.state.value.date)
        assertTrue(vm.state.value.day?.occurrences.orEmpty().isEmpty())
    }

    @Test
    fun usesTheCalendarFilter() = runTest {
        val world = TestWorld()
        world.seed()
        world.prefs.set(Prefs.CalendarPersonFilter, setOf("m-sara"))
        val vm = viewModel(world)

        vm.load(tuesday)

        assertTrue(vm.state.value.day?.occurrences.orEmpty().isEmpty())
    }

    @Test
    fun parentsTakeTheOpenPickUpButCaregiversCannot() = runTest {
        val caregiver = TestWorld(Role.Caregiver)
        caregiver.seed()
        val looking = viewModel(caregiver)
        looking.load(tuesday)
        assertFalse(looking.state.value.canEdit)
        val stop = requireNotNull(looking.state.value.day?.routines?.single()?.firstOpen)
        looking.take(stop.occurrence, stop.kind)
        assertTrue(looking.state.value.day?.routines?.single()?.stops?.single()?.isOpen == true)

        val parent = TestWorld()
        parent.seed()
        val vm = viewModel(parent)
        vm.load(tuesday)
        val open = requireNotNull(vm.state.value.day?.routines?.single()?.firstOpen)
        vm.take(open.occurrence, open.kind)
        val taken = vm.state.value.day?.routines?.single()?.stops?.single()
        assertEquals("Sara", taken?.person?.name)
        assertTrue(taken?.isMe == true)
    }
}
