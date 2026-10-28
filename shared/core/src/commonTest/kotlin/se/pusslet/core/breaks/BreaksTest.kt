package se.pusslet.core.breaks

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toInstant
import se.pusslet.core.model.BreakCare
import se.pusslet.core.model.BreakDay
import se.pusslet.core.model.BreakPlan
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.Role
import se.pusslet.core.model.StayDay
import se.pusslet.core.model.StaySchedule
import se.pusslet.core.notify.NotificationPayload
import se.pusslet.core.notify.NotifyFixtures.input
import se.pusslet.core.notify.NotifyFixtures.zone
import se.pusslet.core.notify.ReminderPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** School breaks and preschool hours (ADR-051). "Today" is Monday 19 October 2026; week 44 starts 26 October. */
class BreaksTest {
    private val today = LocalDate(2026, 10, 19)

    private fun allDay(id: String, title: String, first: LocalDate, last: LocalDate, tags: List<String> = listOf(EventTags.HOLIDAY_BREAK)) =
        Event(
            id,
            title,
            LocalDateTime(first, LocalTime(0, 0)),
            LocalDateTime(LocalDate.fromEpochDays(last.toEpochDays() + 1), LocalTime(0, 0)),
            allDay = true,
            tags = tags,
        ).let { e -> EventOccurrence(e, e.start, e.end, first) }

    private val autumn = allDay("e-hl", "Höstlov", LocalDate(2026, 10, 26), LocalDate(2026, 10, 30))

    @Test
    fun aBreakIsItsWeekdaysAndThePlanForThem() {
        val plan = BreakPlan(
            BreakPlan.idFor("e-hl", LocalDate(2026, 10, 26)),
            "e-hl",
            LocalDate(2026, 10, 26),
            days = mapOf("2026-10-26" to BreakDay(BreakCare.Home, "m-sara"), "2026-10-27" to BreakDay(BreakCare.Care)),
        )
        val other = allDay("e-trip", "Utflykt", LocalDate(2026, 10, 27), LocalDate(2026, 10, 27), tags = listOf(EventTags.TRIP))
        val b = Breaks.breaks(listOf(autumn, other), listOf(plan), today).single()

        assertEquals("Höstlov", b.title)
        assertEquals(LocalDate(2026, 10, 30), b.end, "an all-day event ends at midnight after its last day")
        assertEquals(5, b.days.size)
        assertEquals(listOf(LocalDate(2026, 10, 28), LocalDate(2026, 10, 29), LocalDate(2026, 10, 30)), b.unplanned)
        assertEquals(listOf(LocalDate(2026, 10, 27)), b.careDays)
        assertTrue(Breaks.needsPlan(b, today))
        assertFalse(Breaks.needsPlan(b, LocalDate(2026, 10, 1)), "a month away is not Idag's business yet")
    }

    @Test
    fun redDaysAndWeekendsNeedNobody() {
        // Christmas break 2026: 21 Dec to 8 Jan; 24–26 Dec, 31 Dec, 1 Jan and 6 Jan are red or off days.
        val days = Breaks.weekdays(LocalDate(2026, 12, 21), LocalDate(2027, 1, 8))
        assertFalse(LocalDate(2026, 12, 25) in days)
        assertFalse(LocalDate(2027, 1, 1) in days)
        assertFalse(LocalDate(2027, 1, 6) in days)
        assertTrue(days.none { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY })
        assertTrue(LocalDate(2026, 12, 21) in days)
    }

    @Test
    fun theAutumnBreakIsWeek44() {
        assertEquals(LocalDate(2026, 10, 26) to LocalDate(2026, 10, 30), Breaks.autumnBreak(today))
        assertEquals(LocalDate(2027, 11, 1) to LocalDate(2027, 11, 5), Breaks.autumnBreak(LocalDate(2026, 11, 10)))
        assertEquals(LocalDate(2027, 3, 1) to LocalDate(2027, 3, 5), Breaks.weekBreak(today, 9))
    }

    @Test
    fun theHoursComeFromTheChildsRepeatingPreschoolDay() {
        val preschool = Event(
            "e-pre",
            "Förskola",
            LocalDateTime(today, LocalTime(8, 0)),
            LocalDateTime(today, LocalTime(16, 30)),
            rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
            participantIds = listOf("c-ella"),
            dropOff = Handoff("m-sara", LocalTime(7, 45)),
            pickUp = Handoff("m-johan", LocalTime(16, 15)),
        )
        val week = (0..4).map { i ->
            val d = LocalDate.fromEpochDays(today.toEpochDays() + i)
            EventOccurrence(preschool, LocalDateTime(d, LocalTime(8, 0)), LocalDateTime(d, LocalTime(16, 30)), d)
        }
        val stay = Breaks.stayFromCalendar("c-ella", week)
        assertEquals(StayDay(LocalTime(7, 45), LocalTime(16, 15)), stay[DayOfWeek.MONDAY])
        assertEquals(5, stay.size)
        assertEquals(5 * (8 * 60 + 30), Breaks.minutesPerWeek(StaySchedule("c-ella", stay)))
        assertTrue(Breaks.stayFromCalendar("c-simon", week).isEmpty())
    }

    @Test
    fun theParentsAreRemindedToBookBreakCare() {
        val plan = BreakPlan("p", "e-hl", LocalDate(2026, 10, 26), title = "Höstlov", deadline = LocalDate(2026, 10, 21))
        val now = LocalDateTime(today, LocalTime(8, 0)).toInstant(zone)
        val base = input(now = now).base.copy(breakPlans = listOf(plan))
        val planned = ReminderPlanner.plan(input(now = now).copy(base = base)).filter { it.key.startsWith("break:") }
        assertEquals(LocalDateTime(today, LocalTime(19, 0)).toInstant(zone), planned.single().at, "two days before, in the evening")
        assertEquals(NotificationPayload.BreakDeadline("Höstlov", LocalDate(2026, 10, 21)), planned.single().payload)

        val booked = base.copy(breakPlans = listOf(plan.copy(reported = true)))
        assertTrue(ReminderPlanner.plan(input(now = now).copy(base = booked)).none { it.key.startsWith("break:") })
        assertTrue(ReminderPlanner.plan(input(now = now, role = Role.Caregiver).copy(base = base)).none { it.key.startsWith("break:") })
    }
}
