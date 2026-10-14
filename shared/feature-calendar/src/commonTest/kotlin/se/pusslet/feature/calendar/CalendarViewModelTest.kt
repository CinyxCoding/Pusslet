package se.pusslet.feature.calendar

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
import se.pusslet.core.model.Role
import se.pusslet.data.repository.CalendarView
import se.pusslet.data.repository.Prefs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Today" is Tuesday 13 October 2026 in Stockholm (see [TestWorld]); its week starts on Monday 12 October. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {
    private val today = LocalDate(2026, 10, 13)
    private val friday = LocalDate(2026, 10, 16)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(world: TestWorld): CalendarViewModel {
        val vm = CalendarViewModel(world.calendar, world.family, world.prefs, world.sessions, world.clock, world.zone)
        val eager = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        eager.launch { vm.state.collect {} }
        return vm
    }

    @Test
    fun opensOnTheCurrentWeek() = runTest {
        val state = viewModel(TestWorld()).state.value

        assertEquals(CalendarView.Week, state.view)
        assertEquals(today, state.anchor)
        assertEquals(7, state.days.size)
        assertEquals(LocalDate(2026, 10, 12), state.days.first().date)
    }

    @Test
    fun aTappedDateOpensDayWithoutStoringIt() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)

        vm.showDay(friday)

        assertEquals(CalendarView.Day, vm.state.value.view)
        assertEquals(friday, vm.state.value.anchor)
        assertEquals(CalendarView.Week, world.prefs.observe(Prefs.CalendarViewMode).first())
        // After a restart the calendar opens in the week again, not stuck in Day.
        val restarted = viewModel(TestWorld(prefs = world.prefs))
        assertEquals(CalendarView.Week, restarted.state.value.view)
    }

    @Test
    fun anExplicitViewSwitchIsStored() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)

        vm.showDay(friday)
        vm.setView(CalendarView.Month)

        assertEquals(CalendarView.Month, vm.state.value.view)
        assertEquals(CalendarView.Month, world.prefs.observe(Prefs.CalendarViewMode).first())
        assertEquals(CalendarView.Month, viewModel(TestWorld(prefs = world.prefs)).state.value.view)
    }

    @Test
    fun goTodayComesBackFromAnotherWeek() = runTest {
        val vm = viewModel(TestWorld())

        vm.step(forward = true)
        assertEquals(LocalDate(2026, 10, 20), vm.state.value.anchor)
        vm.goToday()

        assertEquals(today, vm.state.value.anchor)
    }

    @Test
    fun plusUsesTodayOnTheCurrentWeekAndMondayOnAnother() = runTest {
        val vm = viewModel(TestWorld())

        assertNull(vm.state.value.addDate)
        vm.step(forward = true)
        assertEquals(LocalDate(2026, 10, 19), vm.state.value.addDate)
        vm.step(forward = false)
        vm.step(forward = false)
        assertEquals(LocalDate(2026, 10, 5), vm.state.value.addDate)
    }

    @Test
    fun plusUsesTheSelectedDayAndTheFirstOfAnotherMonth() = runTest {
        val vm = viewModel(TestWorld())

        vm.showDay(friday)
        assertEquals(friday, vm.state.value.addDate)

        vm.setView(CalendarView.Month)
        assertNull(vm.state.value.addDate)
        vm.step(forward = true)
        assertEquals(LocalDate(2026, 11, 1), vm.state.value.addDate)
    }

    @Test
    fun parentsCanAddButCaregiversCannot() = runTest {
        assertTrue(viewModel(TestWorld(Role.Parent)).state.value.canEdit)

        val caregiver = viewModel(TestWorld(Role.Caregiver)).state.value
        assertFalse(caregiver.canEdit)
        assertEquals(Role.Caregiver, caregiver.role)
    }
}
