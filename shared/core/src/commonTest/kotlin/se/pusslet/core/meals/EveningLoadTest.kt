package se.pusslet.core.meals

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EveningLoadTest {
    private val day = LocalDate(2026, 10, 13)

    private fun occurrence(title: String, start: LocalTime, end: LocalTime, allDay: Boolean = false): EventOccurrence {
        val s = LocalDateTime(day, start)
        val e = LocalDateTime(day, end)
        return EventOccurrence(Event(id = title, title = title, start = s, end = e, allDay = allDay), s, e, day)
    }

    @Test
    fun trainingAtFiveIsABusyEvening() {
        val list = listOf(
            occurrence("Möte", LocalTime(9, 0), LocalTime(10, 0)),
            occurrence("Fotboll", LocalTime(17, 30), LocalTime(18, 30)),
        )
        assertTrue(EveningLoad.isBusy(list, day))
        assertEquals(listOf("Fotboll"), EveningLoad.busyEvents(list, day).map { it.event.title })
    }

    @Test
    fun daytimeAndLateEventsDoNotCount() {
        val list = listOf(
            occurrence("Jobb", LocalTime(8, 0), LocalTime(16, 30)),
            occurrence("Föräldramöte", LocalTime(19, 0), LocalTime(20, 0)),
            occurrence("Studiedag", LocalTime(0, 0), LocalTime(0, 0), allDay = true),
        )
        assertFalse(EveningLoad.isBusy(list, day))
    }
}
