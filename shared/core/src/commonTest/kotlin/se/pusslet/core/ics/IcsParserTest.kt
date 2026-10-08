package se.pusslet.core.ics

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.recurrence.EventExpander
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class IcsParserTest {
    private val stockholm = TimeZone.of("Europe/Stockholm")
    private val parser = IcsParser()

    private fun d(s: String) = LocalDate.parse(s)

    private fun dt(s: String) = LocalDateTime.parse(s)

    // A club calendar the way laget.se / Google / Outlook typically publish them (CRLF, folded lines, VTIMEZONE).
    private val clubFeed = listOf(
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//Test//Pusslet//SV",
        "X-WR-CALNAME:IFK P2018",
        "X-WR-TIMEZONE:Europe/Stockholm",
        "BEGIN:VTIMEZONE",
        "TZID:Europe/Stockholm",
        "BEGIN:DAYLIGHT",
        "TZOFFSETFROM:+0100",
        "TZOFFSETTO:+0200",
        "DTSTART:19700329T020000",
        "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU",
        "END:DAYLIGHT",
        "BEGIN:STANDARD",
        "TZOFFSETFROM:+0200",
        "TZOFFSETTO:+0100",
        "DTSTART:19701025T030000",
        "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU",
        "END:STANDARD",
        "END:VTIMEZONE",
        "BEGIN:VEVENT",
        "UID:training-1@club",
        "DTSTART;TZID=Europe/Stockholm:20261006T173000",
        "DTEND;TZID=Europe/Stockholm:20261006T184500",
        "RRULE:FREQ=WEEKLY;BYDAY=TU;UNTIL=20261124T225959Z",
        "EXDATE;TZID=Europe/Stockholm:20261027T173000",
        "SUMMARY:Träning P2018\\, konstgräset",
        "LOCATION:Vallen\\; plan 2",
        "DESCRIPTION:Ta med vattenflaska.\\nOmklädning 17:15.",
        "CATEGORIES:Träning,Fotboll",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "UID:training-1@club",
        "RECURRENCE-ID;TZID=Europe/Stockholm:20261013T173000",
        "DTSTART;TZID=Europe/Stockholm:20261014T180000",
        "DTEND;TZID=Europe/Stockholm:20261014T191500",
        "SUMMARY:Träning (flyttad till onsdag)",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "UID:training-1@club",
        "RECURRENCE-ID;TZID=Europe/Stockholm:20261110T173000",
        "DTSTART;TZID=Europe/Stockholm:20261110T173000",
        "DTEND;TZID=Europe/Stockholm:20261110T184500",
        "STATUS:CANCELLED",
        "SUMMARY:Inställd",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "UID:cup@club",
        "DTSTART;VALUE=DATE:20261031",
        "DTEND;VALUE=DATE:20261102",
        "SUMMARY:Höstcupen i Uppsala med en mycket lång titel som viks över flera rader enl",
        " igt RFC 5545",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "UID:meeting@club",
        "DTSTART:20261015T160000Z",
        "DURATION:PT1H30M",
        "SUMMARY:Föräldramöte",
        "END:VEVENT",
        "END:VCALENDAR",
    ).joinToString("\r\n")

    @Test
    fun parsesCalendarNameAndEvents() {
        val cal = parser.parse(clubFeed, "sub1:", stockholm)
        assertEquals("IFK P2018", cal.name)
        assertEquals(4, cal.events.size)
        val training = cal.events.single { it.id == "sub1:training-1@club" }
        assertEquals("Träning P2018, konstgräset", training.title)
        assertEquals("Vallen; plan 2", training.location)
        assertEquals("Ta med vattenflaska.\nOmklädning 17:15.", training.note)
        assertEquals(listOf("träning", "fotboll"), training.tags)
        assertEquals(dt("2026-10-06T17:30"), training.start)
        assertEquals("Europe/Stockholm", training.timeZoneId)
    }

    @Test
    fun recurrenceWithExdateOverrideAndCancellation() {
        val cal = parser.parse(clubFeed, "sub1:", stockholm)
        val occ = EventExpander.expand(cal.events, d("2026-10-01"), d("2026-11-30"), stockholm)
            .filter { it.event.id.startsWith("sub1:training") }
        // Tue 6, (13 moved to Wed 14), 20, (27 excluded), Nov 3, (10 cancelled), 17, 24 (UNTIL 23:59 local).
        assertEquals(
            listOf("2026-10-06T17:30", "2026-10-14T18:00", "2026-10-20T17:30", "2026-11-03T17:30", "2026-11-17T17:30", "2026-11-24T17:30")
                .map { dt(it) },
            occ.map { it.start },
        )
        // After summer time ends (Oct 25) the wall-clock time is unchanged.
        assertEquals(dt("2026-11-03T18:45"), occ[3].end)
        assertEquals("Träning (flyttad till onsdag)", occ[1].event.title)
    }

    @Test
    fun allDayMultiDayEventAndFoldedTitle() {
        val cal = parser.parse(clubFeed, "sub1:", stockholm)
        val cup = cal.events.single { it.id == "sub1:cup@club" }
        assertTrue(cup.allDay)
        assertEquals("Höstcupen i Uppsala med en mycket lång titel som viks över flera rader enligt RFC 5545", cup.title)
        val o = EventExpander.expand(listOf(cup), d("2026-11-01"), d("2026-11-01"), stockholm).single()
        assertEquals(d("2026-11-01"), EventExpander.lastDayOf(o))
    }

    @Test
    fun utcTimeWithDurationIsShownInLocalTime() {
        val cal = parser.parse(clubFeed, "sub1:", stockholm)
        val meeting = cal.events.single { it.id == "sub1:meeting@club" }
        assertEquals("UTC", meeting.timeZoneId)
        val o = EventExpander.expand(listOf(meeting), d("2026-10-15"), d("2026-10-15"), stockholm).single()
        assertEquals(dt("2026-10-15T18:00"), o.start)
        assertEquals(dt("2026-10-15T19:30"), o.end)
    }

    @Test
    fun windowsZoneNamesAreMapped() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:x
            DTSTART;TZID=W. Europe Standard Time:20261008T080000
            DTEND;TZID=W. Europe Standard Time:20261008T090000
            SUMMARY:Utvecklingssamtal
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val e = parser.parse(ics, "", TimeZone.UTC).events.single()
        assertEquals("Europe/Stockholm", e.timeZoneId)
    }

    @Test
    fun unknownZoneFallsBackToVtimezoneOffset() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VTIMEZONE
            TZID:Custom Zone
            BEGIN:STANDARD
            TZOFFSETTO:+0100
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:y
            DTSTART;TZID="Custom Zone":20261208T080000
            SUMMARY:Luciafirande
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val e = parser.parse(ics, "", stockholm).events.single()
        val o = EventExpander.expand(listOf(e), d("2026-12-08"), d("2026-12-08"), stockholm).single()
        assertEquals(dt("2026-12-08T08:00"), o.start)
    }

    @Test
    fun brokenEventsAreSkippedNotFatal() {
        val ics = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:a\nDTSTART:garbage\nEND:VEVENT\nBEGIN:VEVENT\nUID:b\n" +
            "DTSTART:20261008T080000\nSUMMARY:Ok\nEND:VEVENT\nEND:VCALENDAR"
        val cal = parser.parse(ics, "", stockholm)
        assertEquals(listOf("b"), cal.events.map { it.id })
        assertEquals(1, cal.warnings.size)
    }

    @Test
    fun rdateAddsExtraOccurrences() {
        val ics = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:r\nDTSTART:20261008T080000\nDTEND:20261008T090000\n" +
            "RDATE:20261012T080000,20261020T080000\nSUMMARY:Simskola\nEND:VEVENT\nEND:VCALENDAR"
        val cal = parser.parse(ics, "", stockholm)
        val starts = EventExpander.expand(cal.events, d("2026-10-01"), d("2026-10-31"), stockholm).map { it.start.date }
        assertEquals(listOf(d("2026-10-08"), d("2026-10-12"), d("2026-10-20")), starts)
    }

    @Test
    fun durationParsing() {
        assertEquals(1.hours + 30.minutes, IcsParser.parseDuration("PT1H30M"))
        assertEquals(24.hours * 8, IcsParser.parseDuration("P1W1D"))
        assertEquals(-(15.minutes), IcsParser.parseDuration("-PT15M"))
    }
}
