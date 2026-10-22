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
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.data.repository.Prefs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** "Hämtad ✓" on Idag (ADR-048). Tuesday 13 October 2026 at 15:05: Sara picks Ella up at 15:15, Johan picked Simon up at 14:30. */
@OptIn(ExperimentalCoroutinesApi::class)
class PickupTodayTest {
    private val tuesday = LocalDate(2026, 10, 13)

    private val school = Event(
        id = "e-school",
        title = "Skola",
        start = LocalDateTime(tuesday, LocalTime(8, 15)),
        end = LocalDateTime(tuesday, LocalTime(15, 0)),
        participantIds = listOf("c-ella"),
        pickUp = Handoff("m-sara", LocalTime(15, 15)),
        tags = listOf("skola"),
    )
    private val preschool = Event(
        id = "e-pre",
        title = "Förskola",
        start = LocalDateTime(tuesday, LocalTime(8, 0)),
        end = LocalDateTime(tuesday, LocalTime(14, 30)),
        participantIds = listOf("c-simon"),
        pickUp = Handoff("m-johan", LocalTime(14, 30)),
        tags = listOf("förskola"),
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun world() = TestWorld(now = Instant.parse("2026-10-13T13:05:00Z"))

    private fun TestScope.viewModel(world: TestWorld): TodayViewModel {
        val vm = TestPhone(world, backgroundScope).todayViewModel()
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { vm.state.collect {} }
        return vm
    }

    private suspend fun TestWorld.family() {
        family.saveMember(Member("m-sara", "Sara", Role.Parent))
        family.saveMember(Member("m-johan", "Johan", Role.Parent))
        family.saveChild(Child("c-ella", "Ella"))
        family.saveChild(Child("c-simon", "Simon"))
        calendar.saveEvent(school)
        calendar.saveEvent(preschool)
    }

    private fun TodayViewModel.myPickUp() = state.value.hero.filterIsInstance<HeroRow.Handoff>().map { it.line }.firstOrNull { it.isMe }

    @Test
    fun hamtadTicksOffMyPickUpForEveryoneAndCanBeUndone() = runTest {
        val world = world()
        world.family()
        val vm = viewModel(world)
        val line = vm.myPickUp()
        assertTrue(line != null && line.confirmable, "ten minutes before: Hämtad is offered")

        vm.pickedUp(line.stops)
        assertEquals(null, vm.myPickUp(), "ticked off: gone from Morgonkollen")
        val done = vm.state.value.pickedUp.first { it.children.any { c -> c.id == "c-ella" } }
        assertEquals("Sara", done.by?.name)
        assertTrue(done.byMe)
        assertEquals(1, world.calendar.handoffReceipts.first().size)

        vm.undoPickup(done.receipt)
        assertTrue(vm.myPickUp()?.confirmable == true, "back to tick off")
    }

    @Test
    fun johansPickUpNobodyTickedOffIsAProblemSaraCanSolve() = runTest {
        val world = world()
        world.family()
        val vm = viewModel(world)
        val johans = vm.state.value.unconfirmedPickups.single()
        assertEquals("Johan", johans.person?.name)
        assertEquals(listOf("Simon"), johans.pickup.children.map { it.name })

        vm.confirmPickup(johans.pickup)
        assertTrue(vm.state.value.unconfirmedPickups.isEmpty())
        assertTrue(
            vm.state.value.pickedUp.any {
                it.children.any { c ->
                    c.id == "c-simon"
                } && it.by?.name == "Sara"
            },
            "Sara ticked it off",
        )
    }

    @Test
    fun theWarningCanBeSwitchedOff() = runTest {
        val world = world()
        world.family()
        world.prefs.set(Prefs.PickupWarnings, false)
        val vm = viewModel(world)
        assertTrue(vm.state.value.unconfirmedPickups.isEmpty())
    }
}
