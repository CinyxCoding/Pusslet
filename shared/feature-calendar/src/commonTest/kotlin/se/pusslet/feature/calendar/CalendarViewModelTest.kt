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
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Child
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.Event
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.Household
import se.pusslet.core.model.Member
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.core.time.plusDays
import se.pusslet.data.repository.CalendarLayer
import se.pusslet.data.repository.CalendarView
import se.pusslet.data.repository.Prefs
import se.pusslet.data.usecase.HandoffAssigner
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
    private val monday = LocalDate(2026, 10, 12)
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
        val vm = CalendarViewModel(
            world.calendar,
            world.family,
            world.prefs,
            world.sessions,
            HandoffAssigner(world.calendar),
            world.clock,
            world.zone,
        )
        val eager = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        eager.launch { vm.state.collect {} }
        return vm
    }

    /** Sara (signed in), Johan and the children Simon and Ella. */
    private suspend fun TestWorld.seedFamily() {
        family.saveMember(Member(SARA, "Sara", Role.Parent, MemberColor.Sage))
        family.saveMember(Member(JOHAN, "Johan", Role.Parent, MemberColor.Ocean))
        family.saveChild(Child(SIMON, "Simon", MemberColor.Ochre, birthDate = LocalDate(2022, 5, 2)))
        family.saveChild(Child(ELLA, "Ella", MemberColor.Rose, birthDate = LocalDate(2017, 3, 14)))
    }

    /** Preschool every weekday: Johan drops off at 07:50, nobody picks up yet. */
    private suspend fun TestWorld.seedPreschool() {
        calendar.saveEvent(
            Event(
                "e-forskola",
                "Förskola",
                LocalDateTime(monday, LocalTime(8, 0)),
                LocalDateTime(monday, LocalTime(16, 0)),
                rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
                participantIds = listOf(SIMON),
                dropOff = Handoff(JOHAN, LocalTime(7, 50)),
                pickUp = Handoff(null, LocalTime(15, 30)),
                tags = listOf("förskola"),
            ),
        )
        calendar.saveEvent(
            Event(
                "e-fotboll",
                "Fotboll",
                LocalDateTime(today, LocalTime(17, 30)),
                LocalDateTime(today, LocalTime(18, 30)),
                participantIds = listOf(SIMON),
                location = "Vallen",
            ),
        )
    }

    @Test
    fun opensOnTheCurrentWeek() = runTest {
        val state = viewModel(TestWorld()).state.value

        assertEquals(CalendarView.Week, state.view)
        assertEquals(today, state.anchor)
        assertEquals(7, state.days.size)
        assertEquals(monday, state.days.first().date)
        assertTrue(state.showsToday)
    }

    @Test
    fun theOldDayAndAgendaViewsOpenTheWeek() = runTest {
        for (old in listOf(CalendarView.Day, CalendarView.Agenda)) {
            val world = TestWorld()
            world.prefs.set(Prefs.CalendarViewMode, old)

            val state = viewModel(world).state.value

            assertEquals(CalendarView.Week, state.view, "stored $old")
            assertEquals(monday, state.days.first().date)
            assertEquals(7, state.days.size)
        }
    }

    @Test
    fun anExplicitViewSwitchIsStored() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)

        vm.setView(CalendarView.Month)

        assertEquals(CalendarView.Month, vm.state.value.view)
        assertEquals(CalendarView.Month, world.prefs.observe(Prefs.CalendarViewMode).first())
        assertEquals(CalendarView.Month, viewModel(TestWorld(prefs = world.prefs)).state.value.view)

        // Only "Vecka | Månad" exist; anything else is the week.
        vm.setView(CalendarView.Day)
        assertEquals(CalendarView.Week, vm.state.value.view)
        assertEquals(CalendarView.Week, world.prefs.observe(Prefs.CalendarViewMode).first())
    }

    @Test
    fun goTodayComesBackFromAnotherWeek() = runTest {
        val vm = viewModel(TestWorld())

        vm.step(forward = true)
        assertEquals(LocalDate(2026, 10, 20), vm.state.value.anchor)
        assertFalse(vm.state.value.showsToday)
        vm.goToday()

        assertEquals(today, vm.state.value.anchor)
        assertTrue(vm.state.value.showsToday)
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
    fun aTappedDayStaysInTheMonthAndIsWhatPlusUses() = runTest {
        val vm = viewModel(TestWorld())
        vm.setView(CalendarView.Month)

        // The current month starts on today, which quick add uses anyway.
        assertEquals(today, vm.state.value.selected)
        assertEquals(today, vm.state.value.selectedDay?.date)
        assertNull(vm.state.value.addDate)

        vm.selectDay(friday)
        assertEquals(CalendarView.Month, vm.state.value.view)
        assertEquals(friday, vm.state.value.selected)
        assertEquals(friday, vm.state.value.addDate)

        // Another month starts on its first day.
        vm.step(forward = true)
        assertEquals(LocalDate(2026, 11, 1), vm.state.value.selected)
        assertEquals(LocalDate(2026, 11, 1), vm.state.value.addDate)
        assertFalse(vm.state.value.showsToday)
    }

    @Test
    fun aFilterIsAlwaysSpelledOut() = runTest {
        val world = TestWorld()
        world.seedFamily()
        val vm = viewModel(world)
        assertFalse(vm.state.value.filterActive)

        vm.togglePerson(SIMON)
        vm.toggleLayer(CalendarLayer.Holidays)

        val state = vm.state.value
        assertTrue(state.filterActive)
        assertEquals(listOf("Simon"), state.filteredPeople.map { it.name })
        assertEquals(listOf(CalendarLayer.Holidays), state.hiddenLayers)

        vm.showAll()
        assertFalse(vm.state.value.filterActive)
        assertEquals(CalendarLayer.entries.toSet(), vm.state.value.layers)
    }

    @Test
    fun aPersonFilterKeepsOnlyTheirEvents() = runTest {
        val world = TestWorld()
        world.seedFamily()
        world.seedPreschool()
        val vm = viewModel(world)

        vm.togglePerson(ELLA)

        assertTrue(vm.state.value.days.all { it.occurrences.isEmpty() })
        vm.clearPeople()
        assertTrue(vm.state.value.days.first { it.date == today }.occurrences.isNotEmpty())
    }

    @Test
    fun preschoolIsAQuietLineWithItsHandoffs() = runTest {
        val world = TestWorld()
        world.seedFamily()
        world.seedPreschool()

        val tuesday = viewModel(world).state.value.days.first { it.date == today }

        val line = tuesday.routines.single()
        assertEquals(listOf("Simon"), line.children.map { it.name })
        assertEquals(listOf(HandoffKind.DropOff, HandoffKind.PickUp), line.stops.map { it.kind })
        assertEquals("Johan", line.stops[0].person?.name)
        assertEquals(LocalTime(7, 50), line.stops[0].time)
        assertTrue(line.stops[1].isOpen)
        assertEquals(HandoffKind.PickUp, line.firstOpen?.kind)
        // No "Förskola 08:00–16:00" row: only football is an event.
        assertEquals(listOf("Fotboll"), tuesday.events.map { it.event.title })
    }

    @Test
    fun jagTarDetTakesTheOpenPickUp() = runTest {
        val world = TestWorld()
        world.seedFamily()
        world.seedPreschool()
        val vm = viewModel(world)
        val open = vm.state.value.days.first { it.date == today }.routines.single().firstOpen
        requireNotNull(open)

        vm.take(open.occurrence, open.kind)

        val line = vm.state.value.days.first { it.date == today }.routines.single()
        assertEquals("Sara", line.stops[1].person?.name)
        assertTrue(line.stops[1].isMe)
        assertNull(line.firstOpen)
        // Only this day: the series keeps its open pick-up on Wednesday.
        assertTrue(vm.state.value.days.first { it.date == today.plusDays(1) }.routines.single().stops[1].isOpen)
    }

    @Test
    fun caregiversCannotTakeAHandoff() = runTest {
        val world = TestWorld(Role.Caregiver)
        world.seedFamily()
        world.seedPreschool()
        val vm = viewModel(world)
        val open = requireNotNull(vm.state.value.days.first { it.date == today }.routines.single().firstOpen)

        vm.take(open.occurrence, open.kind)

        assertTrue(vm.state.value.days.first { it.date == today }.routines.single().stops[1].isOpen)
    }

    @Test
    fun theWeekSaysWhereTheChildrenAre() = runTest {
        val world = TestWorld()
        world.seedFamily()
        world.family.saveCustody(
            CustodySchedule(
                "cs",
                childIds = listOf(ELLA),
                households = listOf(Household("h-sara", "Sara"), Household("h-ahmed", "Ahmed")),
                anchorDate = monday,
            ),
        )
        val vm = viewModel(world)

        val week = vm.state.value.custodyWeek.single()
        // Only Ella moves, so the line names her ("Ella hos Sara (mån–sön)").
        assertEquals(listOf("Ella"), week.children.map { it.name })
        val period = week.periods.single()
        assertEquals("Sara", period.household.name)
        assertEquals(monday, period.start)
        assertEquals(LocalDate(2026, 10, 18), period.endInclusive)

        // The changeover to Ahmed is next Monday.
        vm.step(forward = true)
        val next = vm.state.value
        assertEquals("Ahmed", next.custodyWeek.single().periods.single().household.name)
        assertEquals("Ahmed", next.days.first().changeovers.single().handover.to.name)

        // A filter on Simon hides Ella's custody.
        vm.togglePerson(SIMON)
        assertTrue(vm.state.value.custodyWeek.isEmpty())
    }

    @Test
    fun parentsCanAddButCaregiversCannot() = runTest {
        assertTrue(viewModel(TestWorld(Role.Parent)).state.value.canEdit)

        val caregiver = viewModel(TestWorld(Role.Caregiver)).state.value
        assertFalse(caregiver.canEdit)
        assertEquals(Role.Caregiver, caregiver.role)
    }

    private companion object {
        const val SARA = "m-sara"
        const val JOHAN = "m-johan"
        const val SIMON = "c-simon"
        const val ELLA = "c-ella"
    }
}
