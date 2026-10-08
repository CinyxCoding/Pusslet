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

/**
 * Phase 3 acceptance: a real, public ICS feed (the Kubernetes community calendar on Google Calendar, fetched 2026-10-08)
 * shows correctly in Sweden – recurring events, EXDATE, moved occurrences (RECURRENCE-ID) and a foreign time zone
 * whose daylight saving time ends a week later than ours. The fixture keeps the feed's own lines for two series;
 * descriptions and meeting links were left out. The full feed (15 643 events, 24 MB) was also parsed once by hand.
 */
class RealIcsFeedTest {
    private val stockholm = TimeZone.of("Europe/Stockholm")
    private val calendar = IcsParser().parse(readFixture("ics_kubernetes_community_subset.ics"), "ics:k8s:", stockholm)

    private fun occurrences(title: String) = EventExpander.expand(
        calendar.events.filter { it.title == title },
        LocalDate(2026, 9, 28),
        LocalDate(2026, 11, 30),
        stockholm,
        EventSource.Subscription,
    ).map { it.start }

    @Test
    fun weeklyMeetingFollowsBothDaylightSavingChanges() {
        // 10:00 in Los Angeles every Wednesday: 19:00 in Sweden, 18:00 in the week between the EU change (25 Oct)
        // and the US change (1 Nov), then 19:00 again.
        val starts = occurrences("SIG Node CI Group: weekly").filter { it.date in LocalDate(2026, 10, 21)..LocalDate(2026, 11, 4) }
        assertEquals(
            listOf(
                LocalDateTime(2026, 10, 21, 19, 0),
                LocalDateTime(2026, 10, 28, 18, 0),
                LocalDateTime(2026, 11, 4, 19, 0),
            ),
            starts,
        )
    }

    @Test
    fun biweeklySeriesWithExdatesAndMovedOccurrences() {
        // Every other Thursday 11:00 LA from 1 Oct; 1 Oct and 12 Nov are cancelled (EXDATE), 15 Oct, 29 Oct and
        // 26 Nov are rewritten single occurrences that must replace – not duplicate – the series.
        assertEquals(
            listOf(
                LocalDateTime(2026, 10, 15, 20, 0),
                LocalDateTime(2026, 10, 29, 19, 0),
                LocalDateTime(2026, 11, 26, 20, 0),
            ),
            occurrences("Bi-weekly SIG Architecture Meeting"),
        )
    }

    @Test
    fun calendarNameAndZoneAreRead() {
        assertEquals("calendar@kubernetes.io", calendar.name)
        assertEquals("America/Los_Angeles", calendar.defaultZoneId)
    }

    @Test
    fun historyIsDroppedButLiveSeriesAreKept() {
        val kept = se.pusslet.core.ics.FeedWindow.relevant(calendar.events, LocalDate(2026, 10, 8))
        assertEquals(calendar.events.size, kept.size, "every event in the subset can still occur")
        val past = se.pusslet.core.ics.FeedWindow.relevant(calendar.events, LocalDate(2028, 1, 1))
        assertEquals(
            listOf("SIG Node CI Group: weekly", "Bi-weekly SIG Architecture Meeting").sorted(),
            past.map {
                it.title
            }.distinct().sorted(),
        )
        assertEquals(2, past.size, "moved occurrences from 2026 are gone, the open-ended series stay")
    }
}
