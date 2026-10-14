package se.pusslet.feature.calendar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.HandoffKind
import se.pusslet.data.seed.DemoSeed
import se.pusslet.data.usecase.HandoffAssigner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The demo week (Familjen Lind, Thursday 8 October 2026, week 41) as the calendar shows it; the screenshots use the same data. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarDemoTest {
    private val thursday = LocalDate(2026, 10, 8)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aDemoThursdayHasHandoffLinesAndNoPreschoolRow() = runTest {
        val world = TestWorld(now = Instant.parse("2026-10-08T05:10:00Z"))
        DemoSeed(world.family, world.docs, world.clock).seed(thursday)
        val vm = CalendarViewModel(
            world.calendar,
            world.family,
            world.prefs,
            world.sessions,
            HandoffAssigner(world.calendar),
            world.clock,
            world.zone,
        )
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { vm.state.collect {} }

        val state = vm.state.value
        val day = state.days.first { it.date == thursday }

        // UX plan, Kalender 2: no "Förskola 08:00–16:00" row, but a drop-off line with names.
        assertTrue(day.events.none { it.event.title == "Förskola" || it.event.title.startsWith("Skola") })
        assertEquals(listOf("Simon", "Ella"), day.routines.map { line -> line.children.single().name })
        val simon = day.routines.first()
        assertEquals(listOf(HandoffKind.DropOff, HandoffKind.PickUp), simon.stops.map { it.kind })
        assertEquals("Johan", simon.stops[0].person?.name)
        assertEquals(LocalTime(7, 50), simon.stops[0].time)
        assertEquals(LocalTime(15, 30), simon.stops[1].time)
        assertTrue(simon.stops.none { it.isOpen })

        // UX plan, Kalender 8: the week says where Ella is.
        val custody = state.custodyWeek.single()
        assertEquals(listOf("Ella"), custody.children.map { it.name })
        assertEquals("Sara", custody.periods.single().household.name)
        assertEquals(LocalDate(2026, 10, 5), custody.periods.single().start)
        assertEquals(LocalDate(2026, 10, 11), custody.periods.single().endInclusive)
    }
}
