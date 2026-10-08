package se.pusslet.data.sources

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.ics.IcsParser
import se.pusslet.core.model.EventSource
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.data.readFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The family's own feed (written by the calendarFeed Cloud Function, golden file checked in its unit tests) read back
 * by the app's ICS parser: what a work calendar sees is what the family planned.
 */
class OwnFeedRoundTripTest {
    private val zone = TimeZone.of("Europe/Stockholm")
    private val calendar = IcsParser().parse(readFixture("own_family_feed.ics"), "own:", zone)

    private fun starts(title: String) = EventExpander.expand(
        calendar.events.filter { it.title.startsWith(title) },
        LocalDate(2026, 10, 5),
        LocalDate(2026, 10, 25),
        zone,
        EventSource.Subscription,
    ).map { it.event.title to it.start }

    @Test
    fun seriesWithCancelledAndMovedOccurrences() {
        assertEquals(
            listOf(
                "Fotboll" to LocalDateTime(2026, 10, 6, 17, 30),
                "Fotboll (flyttad)" to LocalDateTime(2026, 10, 20, 18, 0),
            ),
            starts("Fotboll"),
            "13 Oct is cancelled, 20 Oct moved to 18:00",
        )
    }

    @Test
    fun textAllDayAndFoldedLinesSurvive() {
        val meeting = calendar.events.single { it.title.startsWith("Föräldramöte") }
        assertEquals("Föräldramöte; klass 3B", meeting.title)
        assertTrue("kaffe, kaka" in meeting.note)
        val studyDay = calendar.events.single { it.title == "Studiedag" }
        assertTrue(studyDay.allDay)
        assertEquals(LocalDate(2026, 10, 23), studyDay.start.date)
        assertTrue(calendar.events.any { it.title.endsWith("läsloggen och ämnesomdömena") }, "a folded long title is unfolded")
        assertEquals("Familjen Lind", calendar.name)
    }
}
