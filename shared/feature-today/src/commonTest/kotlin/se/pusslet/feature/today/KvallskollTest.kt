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
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Child
import se.pusslet.core.model.Event
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.Member
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.PackingTrigger
import se.pusslet.core.model.Role
import se.pusslet.core.today.StoredTodayLayout
import se.pusslet.data.repository.Prefs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Kvällskollen on Idag: from 18:00 "Imorgon, onsdag" gets tomorrow ready (hand-offs, what to pack tonight, dinner). */
@OptIn(ExperimentalCoroutinesApi::class)
class KvallskollTest {
    private val wednesday = LocalDate(2026, 10, 14)

    /** Tuesday 13 October, 18:30 in Stockholm. */
    private val evening = Instant.parse("2026-10-13T16:30:00Z")

    /** Ella's school tomorrow: Johan drops off, Sara (the viewer) picks up. */
    private val schoolTomorrow = Event(
        id = "e-school-wed",
        title = "Skola",
        start = LocalDateTime(wednesday, LocalTime(8, 15)),
        end = LocalDateTime(wednesday, LocalTime(15, 0)),
        participantIds = listOf("c-ella"),
        dropOff = Handoff("m-johan", LocalTime(8, 5)),
        pickUp = Handoff("m-sara", LocalTime(15, 15)),
        tags = listOf("skola"),
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(world: TestWorld): TodayViewModel {
        val vm = TestPhone(world, backgroundScope).todayViewModel()
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { vm.state.collect {} }
        return vm
    }

    private suspend fun TestWorld.tomorrowWithGym() {
        family.saveMember(Member("m-sara", "Sara", Role.Parent))
        family.saveMember(Member("m-johan", "Johan", Role.Parent))
        family.saveChild(Child("c-ella", "Ella", birthDate = LocalDate(2017, 3, 14)))
        calendar.saveEvent(schoolTomorrow)
        packing.saveRule(PackingRule("p-gym", "Gympapåse", "c-ella", PackingTrigger.Weekly(setOf(DayOfWeek.WEDNESDAY))))
    }

    @Test
    fun theMorningHasNoKvallskoll() = runTest {
        val world = TestWorld()
        world.tomorrowWithGym()

        assertTrue(viewModel(world).state.value.tomorrow.isEmpty())
    }

    @Test
    fun theEveningGetsTomorrowReady() = runTest {
        val world = TestWorld(now = evening)
        world.tomorrowWithGym()
        val state = viewModel(world).state.value

        val handoffs = state.tomorrow.filterIsInstance<HeroRow.Handoff>().map { it.line }
        assertEquals(listOf(HandoffKind.DropOff, HandoffKind.PickUp), handoffs.map { it.kind })
        assertTrue(handoffs.last().isMe, "Du hämtar Ella 15:15")
        val packing = state.tomorrow.filterIsInstance<HeroRow.Packing>().single()
        assertEquals(wednesday, packing.date)
        assertEquals(listOf("Gympapåse"), packing.packing.children.single().items.map { it.text })
    }

    @Test
    fun packingTonightIsTickedForTomorrow() = runTest {
        val world = TestWorld(now = evening)
        world.tomorrowWithGym()
        val vm = viewModel(world)
        val item = vm.state.value.tomorrow.filterIsInstance<HeroRow.Packing>().single().packing.children.single().items.single()

        vm.setPacked(wednesday, item, true)

        assertTrue(vm.state.value.tomorrow.filterIsInstance<HeroRow.Packing>().single().packing.allPacked, "Allt är packat till imorgon")
    }

    @Test
    fun blocksSwitchedOffOnAnpassaIdagStayOff() = runTest {
        val world = TestWorld(now = evening)
        world.tomorrowWithGym()
        world.prefs.set(Prefs.TodayLayout, StoredTodayLayout(memberId = "m-sara", visible = mapOf("handoffs" to false)))

        val tomorrow = viewModel(world).state.value.tomorrow
        assertTrue(tomorrow.none { it is HeroRow.Handoff })
        assertTrue(tomorrow.any { it is HeroRow.Packing })
    }
}
