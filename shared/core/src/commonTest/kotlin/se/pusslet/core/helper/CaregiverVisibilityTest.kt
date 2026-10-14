package se.pusslet.core.helper

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.HandoffRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class CaregiverVisibilityTest {
    private val day = LocalDate(2026, 10, 16)

    private fun occurrence(id: String, shared: Boolean = true, source: EventSource = EventSource.Family): EventOccurrence {
        val event = Event(
            id = id,
            title = id,
            start = LocalDateTime(day, LocalTime(9, 0)),
            end = LocalDateTime(day, LocalTime(10, 0)),
            sharedWithCaregivers = shared,
        )
        return EventOccurrence(event, event.start, event.end, occurrenceDate = day, source = source)
    }

    private val school = occurrence("school")
    private val board = occurrence("board", shared = false)
    private val club = occurrence("club", source = EventSource.Subscription)
    private val work = occurrence("work", source = EventSource.Device)
    private val holiday = occurrence("holiday", source = EventSource.Holiday)

    @Test
    fun followsTheFirestoreRules() {
        val all = listOf(school, board, club, work, holiday)
        // Subscriptions and device calendars never reach a caregiver, even with the flag set on a parent's phone.
        assertEquals(listOf(school, holiday), CaregiverVisibility.occurrences(all))
        assertEquals(1, CaregiverVisibility.hiddenCount(all))
    }

    @Test
    fun onlySharedTasks() {
        val shared = FamilyTask("t1", "Köp mjölk", sharedWithCaregivers = true)
        val private = FamilyTask("t2", "Deklarera")
        assertEquals(listOf(shared), CaregiverVisibility.tasks(listOf(shared, private)))
    }

    @Test
    fun requestsAboutPrivateEventsAreHidden() {
        val visible = CaregiverVisibility.occurrences(listOf(school, board))
        val aboutSchool = HandoffRequest("r1", eventId = "school", occurrenceDate = day, fromMemberId = "m-sara")
        val aboutBoard = HandoffRequest("r2", eventId = "board", occurrenceDate = day, fromMemberId = "m-sara")
        assertEquals(listOf(aboutSchool), CaregiverVisibility.requests(listOf(aboutSchool, aboutBoard), visible))
    }
}
