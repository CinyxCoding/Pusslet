package se.pusslet.feature.calendar.editor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Event
import se.pusslet.core.quickadd.EventPrefill
import se.pusslet.feature.calendar.TestWorld
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Fyll i själv" in quick add opens the editor with what was understood (UX plan 4.6). */
@OptIn(ExperimentalCoroutinesApi::class)
class EventEditorPrefillTest {
    private val tuesday = LocalDate(2026, 10, 20)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun editor(world: TestWorld) = EventEditorViewModel(world.calendar, world.family, world.sessions, world.clock, world.zone)

    @Test
    fun whatQuickAddUnderstoodFillsANewEvent() = runTest {
        val vm = editor(TestWorld())
        val prefill = EventPrefill(
            title = "Dans",
            date = tuesday,
            startTime = LocalTime(17, 0),
            participantIds = listOf("c-ella"),
            location = "Kulturhuset",
            rrule = "FREQ=WEEKLY;BYDAY=TU",
            packingItems = listOf("Dansskor"),
        )

        vm.load(eventId = null, date = tuesday, occurrence = null, prefill = prefill)

        val draft = requireNotNull(vm.state.value.draft)
        assertTrue(vm.state.value.isNew)
        assertEquals("Dans", draft.title)
        assertEquals(tuesday, draft.startDate)
        assertEquals(LocalTime(17, 0), draft.startTime)
        // No end understood: one hour, like any new event.
        assertEquals(LocalTime(18, 0), draft.endTime)
        assertEquals(tuesday, draft.endDate)
        assertEquals(RepeatOption.Weekly, draft.repeat)
        assertEquals(setOf("c-ella"), draft.participants)
        assertEquals("Kulturhuset", draft.location)
        assertEquals(listOf("Dansskor"), draft.packing)
    }

    @Test
    fun anEveningPastMidnightEndsTheNextDay() = runTest {
        val vm = editor(TestWorld())
        val prefill = EventPrefill("Fest", tuesday, startTime = LocalTime(22, 0), endTime = LocalTime(1, 0))

        vm.load(eventId = null, date = tuesday, occurrence = null, prefill = prefill)

        val draft = requireNotNull(vm.state.value.draft)
        assertEquals(LocalDate(2026, 10, 21), draft.endDate)
        assertTrue(!draft.endsBeforeStart)
    }

    @Test
    fun anExistingEventIgnoresThePrefill() = runTest {
        val world = TestWorld()
        val event = Event(
            id = "e-1",
            title = "Simning",
            start = LocalDateTime(tuesday, LocalTime(16, 0)),
            end = LocalDateTime(tuesday, LocalTime(17, 0)),
        )
        world.calendar.saveEvent(event)
        val vm = editor(world)

        vm.load(eventId = "e-1", date = null, occurrence = null, prefill = EventPrefill("Dans", tuesday))

        assertEquals("Simning", vm.state.value.draft?.title)
    }
}
