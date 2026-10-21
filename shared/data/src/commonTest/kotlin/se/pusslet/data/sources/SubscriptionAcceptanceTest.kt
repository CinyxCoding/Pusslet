package se.pusslet.data.sources

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.EventSource
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.data.readFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fas 3 acceptance (BRIEF §8): "a public test ICS shows correctly incl. recurring events and time zones". The feeds go
 * through [SubscriptionSync] (download, parse, cache) into [se.pusslet.data.repository.CalendarRepository.occurrences],
 * across the end of summer time on Sunday 25 October 2026.
 *
 * - `ics_google_club_calendar.ics` is modelled on a public Google Calendar export (`…/public/basic.ics`, PRODID
 *   "Google Calendar 70.9054", folded lines, X-WR-*, VTIMEZONE with DAYLIGHT and STANDARD) of a club calendar like the
 *   ones laget.se teams keep in Google.
 * - `ics_outlook_custom_zone.ics` is modelled on an Exchange/Outlook "publish calendar" feed whose events use a
 *   "Customized Time Zone" that only its VTIMEZONE rules describe.
 */
class SubscriptionAcceptanceTest {
    private fun d(s: String) = LocalDate.parse(s)

    private fun dt(s: String) = LocalDateTime.parse(s)

    private val from = LocalDate.parse("2026-10-19")
    private val to = LocalDate.parse("2026-11-15")

    @Test
    fun aPublicClubCalendarShowsCorrectlyAcrossTheEndOfSummerTime() = runTest {
        val world = SubscriptionWorld()
        val url = "https://calendar.google.com/calendar/ical/c_8f3k2exempelbyf15/public/basic.ics"
        world.server.calendar(url, readFixture("ics_google_club_calendar.ics"))
        val sub = world.subscribe("ifk", url.replace("https://", "webcal://"), name = "IFK F15", personIds = listOf("c-ella"))

        val status = world.sync.refresh(sub)

        assertNull(status.error)
        // The series, its moved occurrence, the match, the parents' meeting and the autumn break.
        assertEquals(5, status.eventCount)
        val shown = world.calendar.occurrences(from, to, world.zone).first()
        assertTrue(shown.all { it.source == EventSource.Subscription && it.sourceName == "IFK F15" && "c-ella" in it.event.participantIds })

        // Weekly on Tuesdays 17:30 Swedish time: 27 October is an EXDATE (höstlov), 10 November moved to Wednesday 18:00.
        val training = shown.filter { it.event.title.startsWith("Träning") }
        assertEquals(listOf("2026-10-20T17:30", "2026-11-03T17:30", "2026-11-11T18:00").map { dt(it) }, training.map { it.start })
        assertEquals(listOf("2026-10-20T18:45", "2026-11-03T18:45", "2026-11-11T19:15").map { dt(it) }, training.map { it.end })
        // The same wall clock on both sides of the switch, so one hour later in UTC afterwards.
        val trainingUtc = world.calendar.occurrences(from, to, TimeZone.UTC).first().filter { it.event.title.startsWith("Träning") }
        assertEquals(listOf("2026-10-20T15:30", "2026-11-03T16:30", "2026-11-11T17:00").map { dt(it) }, trainingUtc.map { it.start })
        val moved = training.last()
        assertEquals("Träning F15 (inomhus, onsdag)", moved.event.title)
        assertEquals("Exempelhallen", moved.event.location)
        assertEquals(d("2026-11-10"), moved.occurrenceDate)
        // Escaped commas and a folded description.
        assertEquals("Exempelvallen, konstgräs 2, 123 45 Exempelby", training.first().event.location)
        assertEquals(
            "Ta med vattenflaska och benskydd. Omklädning från 17:15.\nVid åska ställer vi in – kolla appen.",
            training.first().event.note,
        )

        // UTC events: the match is in summer time (UTC+2), the parents' meeting in winter time (UTC+1).
        val match = shown.single { it.event.title == "Match mot IK Exempel" }
        assertEquals(dt("2026-10-24T10:00"), match.start)
        assertEquals(dt("2026-10-24T12:00"), match.end)
        assertEquals(dt("2026-10-29T18:00"), shown.single { it.event.title.startsWith("Föräldramöte") }.start)

        // All day, Monday 26 to Friday 30 October (DTEND is exclusive).
        val autumnBreak = shown.single { it.allDay }
        assertEquals("Höstlov – ingen träning", autumnBreak.event.title)
        assertEquals(d("2026-10-26"), autumnBreak.start.date)
        assertEquals(d("2026-10-30"), EventExpander.lastDayOf(autumnBreak))
    }

    @Test
    fun anOutlookCalendarWithItsOwnZoneRulesKeepsSummerTime() = runTest {
        val world = SubscriptionWorld()
        val url = "https://outlook.office365.com/owa/calendar/3f2a9c/8b7d1e/calendar.ics"
        world.server.calendar(url, readFixture("ics_outlook_custom_zone.ics"))
        val sub = world.subscribe("swim", url, name = "Simskolan")

        val status = world.sync.refresh(sub)

        assertNull(status.error)
        val lessons = world.calendar.occurrences(from, d("2026-11-08"), world.zone).first()
        assertEquals("Europe/Stockholm", lessons.first().event.timeZoneId)
        assertEquals(listOf("2026-10-22T16:30", "2026-10-29T16:30", "2026-11-05T16:30").map { dt(it) }, lessons.map { it.start })
        val utc = world.calendar.occurrences(from, d("2026-11-08"), TimeZone.UTC).first()
        assertEquals(listOf("2026-10-22T14:30", "2026-10-29T15:30", "2026-11-05T15:30").map { dt(it) }, utc.map { it.start })
    }
}
