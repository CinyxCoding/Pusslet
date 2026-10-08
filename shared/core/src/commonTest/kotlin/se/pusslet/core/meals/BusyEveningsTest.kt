package se.pusslet.core.meals

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BusyEveningsTest {
    private val day = LocalDate(2026, 10, 13)

    private fun occ(id: String, start: LocalTime, end: LocalTime, allDay: Boolean = false, source: EventSource = EventSource.Family) =
        EventOccurrence(
            event = Event(id, id, LocalDateTime(day, start), LocalDateTime(day, end), allDay = allDay),
            start = LocalDateTime(day, start),
            end = LocalDateTime(day, end),
            occurrenceDate = day,
            source = source,
        )

    @Test
    fun practiceAtFiveMakesTheEveningBusy() {
        val busy = BusyEvenings.of(
            listOf(
                occ("fotboll", LocalTime(17, 30), LocalTime(18, 30)),
                occ("tandläkare", LocalTime(9, 0), LocalTime(10, 0)),
                occ("jobbmöte", LocalTime(16, 0), LocalTime(17, 0), source = EventSource.Device),
            ),
        )
        assertEquals(listOf("fotboll", "jobbmöte"), busy[day]?.map { it.event.id })
    }

    @Test
    fun morningsLateNightsAndAllDayEventsDoNotCount() {
        val busy = BusyEvenings.of(
            listOf(
                occ("möte", LocalTime(15, 0), LocalTime(16, 30)),
                occ("bio", LocalTime(19, 0), LocalTime(21, 0)),
                occ("studiedag", LocalTime(0, 0), LocalTime(0, 0), allDay = true),
                occ("midsommar", LocalTime(17, 0), LocalTime(18, 0), source = EventSource.Holiday),
            ),
        )
        assertTrue(busy.isEmpty())
    }
}
