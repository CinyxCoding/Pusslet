package se.pusslet.feature.today

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import se.pusslet.core.model.Role
import se.pusslet.core.today.StoredTodayLayout
import se.pusslet.core.today.TodayBlock
import se.pusslet.core.today.TodayLayouts
import se.pusslet.data.repository.Prefs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TodayLayoutViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(world: TestWorld): TodayLayoutViewModel {
        val vm = TodayLayoutViewModel(world.prefs, world.sessions, world.family)
        val eager = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        eager.launch { vm.state.collect {} }
        return vm
    }

    @Test
    fun startsFromTheRoleDefault() = runTest {
        val vm = viewModel(TestWorld())
        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(TodayLayouts.defaultFor(Role.Parent), state.layout)
        assertTrue(state.isDefault)
        assertFalse(state.hasWeatherPlace)
    }

    @Test
    fun aCaregiverOnlyGetsTheirBlocks() = runTest {
        val blocks = viewModel(TestWorld(role = Role.Caregiver)).state.value.layout.items.map { it.block }
        assertFalse(TodayBlock.Dinner in blocks)
        assertFalse(TodayBlock.Timeline in blocks)
        assertTrue(TodayBlock.Handoffs in blocks)
    }

    @Test
    fun aSwitchIsStoredAtOnceAndOnlyAsAChange() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)
        vm.setVisible(TodayBlock.Tasks, false)
        assertEquals(StoredTodayLayout(memberId = "m-sara", visible = mapOf("tasks" to false)), world.prefs.get(Prefs.TodayLayout))
        assertFalse(vm.state.value.layout.isOn(TodayBlock.Tasks))
        assertFalse(vm.state.value.isDefault)
        // Turning it back on is the default again: nothing is kept.
        vm.setVisible(TodayBlock.Tasks, true)
        assertNull(world.prefs.get(Prefs.TodayLayout))
        assertTrue(vm.state.value.isDefault)
    }

    @Test
    fun sectionsMoveButNotPastTheEnds() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)
        vm.move(TodayBlock.Upcoming, up = true)
        assertEquals(
            listOf(TodayBlock.Timeline, TodayBlock.Upcoming, TodayBlock.Tasks, TodayBlock.Shopping),
            vm.state.value.layout.sections.map { it.block },
        )
        vm.move(TodayBlock.Timeline, up = true)
        vm.move(TodayBlock.Shopping, up = false)
        assertEquals(
            listOf("timeline", "upcoming", "tasks", "shopping"),
            assertNotNull(world.prefs.get(Prefs.TodayLayout)).sectionOrder,
        )
        // A hidden section keeps its place.
        vm.setVisible(TodayBlock.Upcoming, false)
        assertEquals(TodayBlock.Upcoming, vm.state.value.layout.sections[1].block)
    }

    @Test
    fun resetCanBeUndone() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)
        vm.setVisible(TodayBlock.Weather, false)
        val previous = vm.reset()
        assertTrue(vm.state.value.isDefault)
        assertNull(world.prefs.get(Prefs.TodayLayout))
        vm.restore(previous)
        assertFalse(vm.state.value.layout.isOn(TodayBlock.Weather))
        assertEquals(previous, world.prefs.get(Prefs.TodayLayout))
    }
}
