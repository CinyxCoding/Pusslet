package se.pusslet.core.recurrence

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecurrenceTest {
    private val stockholm = TimeZone.of("Europe/Stockholm")

    private fun dt(s: String) = LocalDateTime.parse(s)

    private fun d(s: String) = LocalDate.parse(s)

    private fun expand(rule: String, start: String, from: String, to: String, ex: Set<LocalDate> = emptySet()) =
        RecurrenceExpander.expand(assertNotNull(RRule.parse(rule)), dt(start), d(from), d(to), ex)

    @Test
    fun parseAndFormatRoundTrip() {
        val r = assertNotNull(RRule.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH;COUNT=10"))
        assertEquals(Frequency.WEEKLY, r.freq)
        assertEquals(2, r.interval)
        assertEquals(listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), r.byDay.map { it.day })
        assertEquals("FREQ=WEEKLY;INTERVAL=2;COUNT=10;BYDAY=TU,TH", r.format())
        assertEquals(r, RRule.parse(r.format()))
    }

    @Test
    fun parseOrdinalsAndPrefix() {
        val r = assertNotNull(RRule.parse("RRULE:FREQ=MONTHLY;BYDAY=-1FR,+2MO"))
        assertEquals(listOf(WeekdayNum(DayOfWeek.FRIDAY, -1), WeekdayNum(DayOfWeek.MONDAY, 2)), r.byDay)
    }

    @Test
    fun invalidRulesReturnNull() {
        assertNull(RRule.parse("FREQ=SOMETIMES"))
        assertNull(RRule.parse("BYDAY=MO"))
        assertNull(RRule.parse("FREQ=WEEKLY;INTERVAL=x"))
        assertNull(RRule.parse("FREQ=WEEKLY;BYDAY=XX"))
    }

    @Test
    fun utcUntilIsConvertedToLocalZone() {
        val r = assertNotNull(RRule.parse("FREQ=DAILY;UNTIL=20261231T225959Z", stockholm))
        assertEquals(dt("2026-12-31T23:59:59"), r.until)
        val dateOnly = assertNotNull(RRule.parse("FREQ=DAILY;UNTIL=20261231"))
        assertEquals(dt("2026-12-31T23:59:59"), dateOnly.until)
    }

    @Test
    fun weeklyTraining() {
        // "Simon fotboll tis 17:30 varje vecka"
        val list = expand("FREQ=WEEKLY;BYDAY=TU", "2026-10-06T17:30", "2026-10-01", "2026-10-31")
        assertEquals(listOf("2026-10-06", "2026-10-13", "2026-10-20", "2026-10-27").map { dt(it + "T17:30") }, list)
    }

    @Test
    fun weeklyWithoutByDayUsesStartWeekday() {
        val list = expand("FREQ=WEEKLY", "2026-10-07T08:00", "2026-10-01", "2026-10-21")
        assertEquals(listOf(d("2026-10-07"), d("2026-10-14"), d("2026-10-21")), list.map { it.date })
    }

    @Test
    fun everyOtherFriday() {
        // "Bibliotekbok varannan fredag"
        val list = expand("FREQ=WEEKLY;INTERVAL=2;BYDAY=FR", "2026-10-09T08:00", "2026-10-01", "2026-11-30")
        assertEquals(listOf("2026-10-09", "2026-10-23", "2026-11-06", "2026-11-20").map(::d), list.map { it.date })
    }

    @Test
    fun multipleWeekdaysAndCount() {
        val list = expand("FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=5", "2026-10-05T07:00", "2026-01-01", "2027-12-31")
        assertEquals(listOf("2026-10-05", "2026-10-07", "2026-10-09", "2026-10-12", "2026-10-14").map(::d), list.map { it.date })
    }

    @Test
    fun countIsHonouredEvenWhenWindowStartsLater() {
        val list = expand("FREQ=DAILY;COUNT=10", "2026-10-01T09:00", "2026-10-08", "2026-10-31")
        assertEquals(listOf("2026-10-08", "2026-10-09", "2026-10-10").map(::d), list.map { it.date })
    }

    @Test
    fun untilIsInclusive() {
        val list = expand("FREQ=DAILY;UNTIL=20261003T090000", "2026-10-01T09:00", "2026-09-01", "2026-12-31")
        assertEquals(3, list.size)
    }

    @Test
    fun exDatesAreSkipped() {
        val list = expand("FREQ=WEEKLY;BYDAY=TU", "2026-10-06T17:30", "2026-10-01", "2026-10-31", setOf(d("2026-10-20")))
        assertEquals(listOf("2026-10-06", "2026-10-13", "2026-10-27").map(::d), list.map { it.date })
    }

    @Test
    fun wallClockTimeIsStableAcrossDaylightSavingEnd() {
        // Summer time ends 2026-10-25; the training is still at 17:30 local time.
        val list = expand("FREQ=WEEKLY;BYDAY=TU", "2026-10-20T17:30", "2026-10-19", "2026-11-03")
        assertTrue(list.all { it.time == LocalTime(17, 30) })
        assertEquals(3, list.size)
    }

    @Test
    fun weeklyAcrossWeek53AndNewYear() {
        val list = expand("FREQ=WEEKLY;BYDAY=MO", "2026-12-21T08:00", "2026-12-01", "2027-01-15")
        assertEquals(listOf("2026-12-21", "2026-12-28", "2027-01-04", "2027-01-11").map(::d), list.map { it.date })
    }

    @Test
    fun monthlyByMonthDaySkipsShortMonths() {
        val list = expand("FREQ=MONTHLY", "2027-01-31T10:00", "2027-01-01", "2027-06-30")
        assertEquals(listOf("2027-01-31", "2027-03-31", "2027-05-31").map(::d), list.map { it.date })
    }

    @Test
    fun monthlyLastFridayAndFirstMonday() {
        val last = expand("FREQ=MONTHLY;BYDAY=-1FR", "2026-10-30T18:00", "2026-10-01", "2027-01-31")
        assertEquals(listOf("2026-10-30", "2026-11-27", "2026-12-25", "2027-01-29").map(::d), last.map { it.date })
        val first = expand("FREQ=MONTHLY;BYDAY=1MO", "2026-11-02T18:00", "2026-11-01", "2027-01-31")
        assertEquals(listOf("2026-11-02", "2026-12-07", "2027-01-04").map(::d), first.map { it.date })
    }

    @Test
    fun monthlyNegativeMonthDay() {
        val list = expand("FREQ=MONTHLY;BYMONTHDAY=-1", "2027-01-31T12:00", "2027-01-01", "2027-04-30")
        assertEquals(listOf("2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30").map(::d), list.map { it.date })
    }

    @Test
    fun monthlyLastWeekdayWithSetPos() {
        // RFC 5545: last work day of the month.
        val list = expand("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1", "2026-10-30T09:00", "2026-10-01", "2027-01-31")
        assertEquals(listOf("2026-10-30", "2026-11-30", "2026-12-31", "2027-01-29").map(::d), list.map { it.date })
    }

    @Test
    fun rfcExampleFridayThe13th() {
        // RFC 5545: FREQ=MONTHLY;BYDAY=FR;BYMONTHDAY=13
        val list = expand("FREQ=MONTHLY;BYDAY=FR;BYMONTHDAY=13", "2026-02-13T09:00", "2026-01-01", "2027-12-31")
        assertEquals(listOf("2026-02-13", "2026-03-13", "2026-11-13", "2027-08-13").map(::d), list.map { it.date })
    }

    @Test
    fun yearlyBirthdayOnLeapDay() {
        val list = expand("FREQ=YEARLY", "2024-02-29T00:00", "2024-01-01", "2032-12-31")
        assertEquals(listOf("2024-02-29", "2028-02-29", "2032-02-29").map(::d), list.map { it.date })
    }

    @Test
    fun yearlyByMonthAndByDay() {
        // Mors dag: last Sunday in May.
        val list = expand("FREQ=YEARLY;BYMONTH=5;BYDAY=-1SU", "2026-05-31T00:00", "2026-01-01", "2028-12-31")
        assertEquals(listOf("2026-05-31", "2027-05-30", "2028-05-28").map(::d), list.map { it.date })
    }

    @Test
    fun dailyWithIntervalAndByMonthLimit() {
        val list = expand("FREQ=DAILY;INTERVAL=10;BYMONTH=11", "2026-10-02T09:00", "2026-10-01", "2026-12-31")
        assertEquals(listOf("2026-10-02", "2026-11-01", "2026-11-11", "2026-11-21").map(::d), list.map { it.date })
    }

    @Test
    fun weekStartAffectsBiweeklyGrouping() {
        // RFC 5545 example: WKST changes which days fall in the same week.
        val mo = expand("FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=TU,SU;WKST=MO", "1997-08-05T09:00", "1997-01-01", "1997-12-31")
        assertEquals(listOf("1997-08-05", "1997-08-10", "1997-08-19", "1997-08-24").map(::d), mo.map { it.date })
        val su = expand("FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=TU,SU;WKST=SU", "1997-08-05T09:00", "1997-01-01", "1997-12-31")
        assertEquals(listOf("1997-08-05", "1997-08-17", "1997-08-19", "1997-08-31").map(::d), su.map { it.date })
    }

    @Test
    fun farFutureWindowIsFastAndBounded() {
        val list = expand("FREQ=DAILY", "2026-01-01T09:00", "2060-01-01", "2060-01-03")
        assertEquals(3, list.size)
    }

    @Test
    fun eventExpanderAppliesOverridesAndDuration() {
        val series = Event(
            id = "s",
            title = "Fotboll",
            start = dt("2026-10-06T17:30"),
            end = dt("2026-10-06T18:45"),
            rrule = "FREQ=WEEKLY;BYDAY=TU",
        )
        val moved = series.copy(
            id = "o",
            rrule = null,
            seriesId = "s",
            originalDate = d("2026-10-13"),
            start = dt("2026-10-14T18:00"),
            end = dt("2026-10-14T19:15"),
        )
        val list = EventExpander.expand(listOf(series, moved), d("2026-10-01"), d("2026-10-20"), stockholm)
        assertEquals(listOf(dt("2026-10-06T17:30"), dt("2026-10-14T18:00"), dt("2026-10-20T17:30")), list.map { it.start })
        assertEquals(dt("2026-10-20T18:45"), list.last().end)
        assertEquals(d("2026-10-13"), list[1].occurrenceDate)
    }

    @Test
    fun multiDayAllDayEventOverlapsWindowEvenIfItStartedBefore() {
        val lov = Event(
            id = "lov",
            title = "Höstlov",
            start = dt("2026-10-26T00:00"),
            end = dt("2026-10-31T00:00"),
            allDay = true,
        )
        val list = EventExpander.expand(listOf(lov), d("2026-10-28"), d("2026-10-28"), stockholm)
        assertEquals(1, list.size)
        assertEquals(d("2026-10-30"), EventExpander.lastDayOf(list.single()))
        assertTrue(EventExpander.expand(listOf(lov), d("2026-10-31"), d("2026-11-01"), stockholm).isEmpty())
    }

    @Test
    fun handOffTimesAreConvertedWithTheEvent() {
        // A phone set to UTC shows a Stockholm pick-up at 15:30 CEST as 13:30, like the event itself.
        val school = Event(
            id = "s",
            title = "Förskola",
            start = dt("2026-10-08T08:00"),
            end = dt("2026-10-08T16:00"),
            dropOff = se.pusslet.core.model.Handoff("johan", LocalTime(7, 50)),
            pickUp = se.pusslet.core.model.Handoff("bengt", LocalTime(15, 30)),
        )
        val o = EventExpander.expand(listOf(school), d("2026-10-08"), d("2026-10-08"), TimeZone.UTC).single()
        assertEquals(dt("2026-10-08T06:00"), o.start)
        assertEquals(LocalTime(5, 50), o.dropOffTime())
        assertEquals(LocalTime(13, 30), o.pickUpTime())
        val home = EventExpander.expand(listOf(school), d("2026-10-08"), d("2026-10-08"), stockholm).single()
        assertEquals(LocalTime(15, 30), home.pickUpTime())
    }

    @Test
    fun eventInOtherZoneIsConvertedForDisplay() {
        val call = Event(
            id = "c",
            title = "Möte New York",
            start = dt("2026-10-08T09:00"),
            end = dt("2026-10-08T10:00"),
            timeZoneId = "America/New_York",
        )
        val o = EventExpander.expand(listOf(call), d("2026-10-08"), d("2026-10-08"), stockholm).single()
        assertEquals(dt("2026-10-08T15:00"), o.start)
    }

    @Test
    fun brokenRuleDegradesToSingleOccurrence() {
        val e = Event("b", "Trasig", dt("2026-10-08T09:00"), dt("2026-10-08T10:00"), rrule = "FREQ=NEVER")
        assertEquals(1, EventExpander.expand(listOf(e), d("2026-10-01"), d("2026-12-31"), stockholm).size)
    }
}
