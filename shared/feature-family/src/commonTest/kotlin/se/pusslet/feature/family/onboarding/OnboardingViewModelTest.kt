package se.pusslet.feature.family.onboarding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import se.pusslet.core.model.MemberColor
import se.pusslet.data.repository.Prefs
import se.pusslet.feature.family.TestWorld
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(world: TestWorld) = OnboardingViewModel(world.family, world.prefs, world.seed, world.ids, world.clock, world.zone)

    /** Welcome → Family ("Sara") → Children ([names]) → Place (skipped) → Done. */
    private fun OnboardingViewModel.runThrough(vararg names: String) {
        next("Familjen Sara")
        setMyName("Sara")
        next("Familjen Sara")
        names.forEach { name ->
            addChild()
            val id = state.value.children.last().id
            updateChild(id) { it.copy(name = name) }
        }
        next("Familjen Sara")
        next("Familjen Sara")
    }

    @Test
    fun firstChildIsNotTerracotta() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)

        vm.runThrough("Ella", "Simon")

        assertEquals(OnboardingStep.Done, vm.state.value.step)
        val colors = world.family.children.first().associate { it.firstName to it.color }
        assertEquals(MemberColor.Ocean, colors["Ella"])
        assertEquals(MemberColor.Plum, colors["Simon"])
        assertNotEquals(MemberColor.Terracotta, colors["Ella"])
    }

    @Test
    fun replayingFirstStartShowsTheWelcomeScreenAgain() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)
        vm.runThrough("Ella")
        assertTrue(world.prefs.get(Prefs.OnboardingDone))

        // "Visa första start igen" in Settings resets the flag while this view model is still alive.
        world.prefs.set(Prefs.OnboardingDone, false)

        assertEquals(OnboardingUiState(), vm.state.value)
    }

    @Test
    fun aRunInProgressIsKept() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)
        vm.next("Familjen Sara")
        vm.setMyName("Sara")

        world.prefs.set(Prefs.OnboardingDone, false)

        assertEquals(OnboardingStep.Family, vm.state.value.step)
        assertEquals("Sara", vm.state.value.myName)
    }
}
