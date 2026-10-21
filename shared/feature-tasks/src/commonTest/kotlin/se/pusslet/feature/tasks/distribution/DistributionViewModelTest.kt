package se.pusslet.feature.tasks.distribution

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
import se.pusslet.core.model.ChoreRotation
import se.pusslet.core.model.Completion
import se.pusslet.core.model.Event
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.model.Role
import se.pusslet.core.model.TaskKind
import se.pusslet.core.tasks.ShareKind
import se.pusslet.core.time.IsoWeek
import se.pusslet.feature.tasks.TestWorld
import se.pusslet.feature.tasks.TestWorld.Companion.BENGT
import se.pusslet.feature.tasks.TestWorld.Companion.ELLA
import se.pusslet.feature.tasks.TestWorld.Companion.JOHAN
import se.pusslet.feature.tasks.TestWorld.Companion.SARA
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** "Så har vi delat" on Tuesday 20 October 2026: weeks 40–43. */
@OptIn(ExperimentalCoroutinesApi::class)
class DistributionViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.observe(vm: DistributionViewModel): DistributionViewModel {
        val eager = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        eager.launch { vm.state.collect {} }
        return vm
    }

    /** Ella's school from Monday 12 October: Johan drops off, Sara picks up. */
    private val school = Event(
        id = "e-school",
        title = "Skola",
        start = LocalDateTime(LocalDate(2026, 10, 12), LocalTime(8, 15)),
        end = LocalDateTime(LocalDate(2026, 10, 12), LocalTime(15, 0)),
        rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
        participantIds = listOf(ELLA),
        dropOff = Handoff(JOHAN, LocalTime(8, 5)),
        pickUp = Handoff(SARA, LocalTime(15, 0)),
    )

    @Test
    fun theNumbersPerAdult() = runTest {
        val world = TestWorld()
        world.lind()
        world.calendar.saveEvent(school)
        // Bengt said yes to Friday's pick-up; no parent's phone has written it into the event yet.
        world.calendar.saveHandoffRequest(
            HandoffRequest(
                "r-fre",
                school.id,
                LocalDate(2026, 10, 16),
                HandoffKind.PickUp,
                fromMemberId = SARA,
                toMemberId = BENGT,
                status = RequestStatus.Accepted,
                respondedBy = BENGT,
                respondedAt = Instant.parse("2026-10-15T18:00:00Z"),
            ),
        )
        world.tasks.save(
            FamilyTask("t-fritids", "Betala fritidsavgiften", done = true, doneBy = JOHAN, doneAt = Instant.parse("2026-10-14T10:00:00Z")),
        )
        world.tasks.save(
            FamilyTask(
                "t-dammsug",
                "Dammsuga",
                kind = TaskKind.Chore,
                rrule = "FREQ=WEEKLY;BYDAY=SA",
                rotation = ChoreRotation(listOf(SARA, JOHAN), 1, IsoWeek(2026, 40)),
                completions = mapOf(
                    "2026-10-10" to Completion(SARA, null),
                    "2026-10-17" to Completion(SARA, null),
                    // Week 39: before the four weeks.
                    "2026-09-26" to Completion(JOHAN, null),
                ),
            ),
        )

        val state = observe(world.distribution()).state.value
        assertTrue(state.allowed)
        val d = assertNotNull(state.distribution)
        assertEquals(IsoWeek(2026, 40), d.fromWeek)
        assertEquals(IsoWeek(2026, 43), d.toWeek)
        val counts = d.shares.associate { s -> s.person.id to ShareKind.entries.map { s.count(it) } }
        // Tasks, chores, hand-offs (planned), yes to requests.
        assertEquals(listOf(0, 2, 6, 0), counts[SARA])
        assertEquals(listOf(1, 0, 7, 0), counts[JOHAN])
        assertEquals(listOf(0, 0, 1, 1), counts[BENGT])
        // Bengt has a row only where he did something.
        assertEquals(listOf(JOHAN, SARA), d.rows(ShareKind.Chores).map { it.person.id })
        assertEquals(listOf(JOHAN, SARA, BENGT), d.rows(ShareKind.Handoffs).map { it.person.id })
    }

    @Test
    fun caregiversDoNotSeeIt() = runTest {
        val world = TestWorld(role = Role.Caregiver, memberId = BENGT)
        world.lind()
        val state = observe(world.distribution()).state.value
        assertFalse(state.loading)
        assertFalse(state.allowed)
        assertNull(state.distribution)
    }

    @Test
    fun nothingYetIsEmpty() = runTest {
        val world = TestWorld()
        world.lind()
        val d = assertNotNull(observe(world.distribution()).state.value.distribution)
        assertTrue(d.isEmpty)
    }
}
