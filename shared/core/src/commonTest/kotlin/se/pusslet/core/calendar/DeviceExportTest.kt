package se.pusslet.core.calendar

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceExportTest {
    private val monday = LocalDate(2026, 10, 19)

    private fun at(date: LocalDate, h: Int) = LocalDateTime(date, LocalTime(h, 0))

    @Test
    fun aMovedOccurrenceIsItsOwnEventAndTheSeriesSkipsItsDate() {
        val nextMonday = LocalDate(2026, 10, 26)
        val series =
            Event("e-dans", "Dans", at(monday, 17), at(monday, 18), rrule = "FREQ=WEEKLY", exDates = listOf(LocalDate(2026, 11, 2)))
        val moved = Event(
            "e-dans-26",
            "Dans",
            at(nextMonday, 18),
            at(nextMonday, 19),
            rrule = "FREQ=WEEKLY",
            seriesId = "e-dans",
            originalDate = nextMonday,
        )
        val single = Event("e-tand", "Tandläkare", at(monday, 14), at(monday, 15))

        val export = DeviceExport.prepare(listOf(series, moved, single)).associateBy { it.id }
        assertEquals(listOf(nextMonday, LocalDate(2026, 11, 2)), export.getValue("e-dans").exDates, "sorted, the old one kept")
        assertNull(export.getValue("e-dans-26").rrule, "the move is one event, not a second series")
        assertEquals(single, export.getValue("e-tand"))
    }
}
