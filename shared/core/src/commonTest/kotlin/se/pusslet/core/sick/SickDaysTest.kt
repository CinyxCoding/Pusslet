package se.pusslet.core.sick

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toInstant
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.PackingTrigger
import se.pusslet.core.model.SickDay
import se.pusslet.core.model.VabPart
import se.pusslet.core.notify.NotificationKind
import se.pusslet.core.notify.NotificationPayload
import se.pusslet.core.notify.NotifyFixtures.base
import se.pusslet.core.notify.NotifyFixtures.ella
import se.pusslet.core.notify.NotifyFixtures.event
import se.pusslet.core.notify.NotifyFixtures.input
import se.pusslet.core.notify.NotifyFixtures.johan
import se.pusslet.core.notify.NotifyFixtures.preschool
import se.pusslet.core.notify.NotifyFixtures.sara
import se.pusslet.core.notify.NotifyFixtures.school
import se.pusslet.core.notify.NotifyFixtures.simon
import se.pusslet.core.notify.NotifyFixtures.wednesday
import se.pusslet.core.notify.NotifyFixtures.zone
import se.pusslet.core.notify.ReminderPlanner
import se.pusslet.core.time.plusDays
import se.pusslet.core.today.TimelineItem
import se.pusslet.core.today.TodayPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SickDaysTest {
    private val children = setOf(ella.id, simon.id)
    private val monday = LocalDate(2026, 10, 19)
    private val tuesday = LocalDate(2026, 10, 20)
    private val thursday = LocalDate(2026, 10, 22)

    private fun sick(child: String, date: LocalDate, home: String? = null, vab: Boolean = false, part: VabPart = VabPart.Whole) =
        SickDay(SickDay.idFor(child, date), child, date, reportedBy = sara.id, homeWith = home, vab = vab, vabPart = part)

    private val dance = event("e-dance", "Dans", wednesday, LocalTime(17, 0), LocalTime(18, 0), participants = listOf(ella.id))
    private val swim = event("e-swim", "Simning", wednesday, LocalTime(18, 0), LocalTime(19, 0), participants = listOf(ella.id, simon.id))
    private val meeting = event("e-meet", "Möte", wednesday, LocalTime(10, 0), LocalTime(11, 0), participants = listOf(sara.id))
    private val camp = event("e-camp", "Läger", tuesday, LocalTime(0, 0), LocalTime(0, 0), participants = listOf(ella.id), allDay = true)
        .let { it.copy(end = LocalDateTime(tuesday.plusDays(3), LocalTime(0, 0))) }

    @Test
    fun theSickChildIsAbsentAndWhatOnlySheGoesToIsCancelled() {
        val occurrences = base(events = listOf(school, preschool, dance, swim, meeting, camp)).occurrences
        val applied = SickDays.apply(occurrences, listOf(sick(ella.id, wednesday)), children)

        val cancelled = applied.cancelled.map { it.key }.toSet()
        assertEquals(setOf("e-school@$wednesday", "e-dance@$wednesday"), cancelled)
        val kept = applied.occurrences.associateBy { it.key }
        assertEquals(listOf(simon.id), kept.getValue("e-swim@$wednesday").attending, "Simon still swims")
        assertEquals(listOf(ella.id, simon.id), kept.getValue("e-swim@$wednesday").event.participantIds, "the event itself is untouched")
        assertTrue("e-meet@$wednesday" in kept, "an adult's own event is not touched")
        assertTrue(kept.keys.any { it.startsWith("e-camp@") }, "an event over several days is not touched")
        assertTrue("e-pre@$wednesday" in kept && "e-school@$thursday" in kept, "her brother and the next day go on")
    }

    @Test
    fun idagLeavesHerHandOffsPackingAndClothesOut() {
        val gym = PackingRule("p-gym", "Gympapåse", ella.id, PackingTrigger.Weekly(setOf(DayOfWeek.WEDNESDAY)))
        val days = listOf(sick(ella.id, wednesday, home = sara.id))
        val input = base(rules = listOf(gym)).let { b ->
            b.copy(occurrences = SickDays.apply(b.occurrences, days, children).occurrences, sickDays = days)
        }
        val plan = TodayPlanner.build(input)
        val handoffs = plan.timeline.filterIsInstance<TimelineItem.HandoffItem>()
        assertTrue(handoffs.none { h -> h.children.any { it.id == ella.id } }, "nobody picks Ella up")
        assertTrue(handoffs.any { h -> h.children.any { it.id == simon.id } }, "Simon still goes to preschool")
        assertTrue(plan.packing.none { it.child.id == ella.id })
    }

    @Test
    fun theMorningAfterAsksIfSheIsStillSick() {
        val monday = listOf(sick(ella.id, monday))
        assertEquals(monday, SickDays.stillSick(monday, wednesday), "after a weekend day too")
        assertTrue(SickDays.stillSick(monday.map { it.copy(wellAgain = true) }, wednesday).isEmpty())
        assertTrue(SickDays.stillSick(monday + sick(ella.id, wednesday), wednesday).isEmpty(), "today is already reported")
        assertTrue(SickDays.stillSick(monday, monday.first().date.plusDays(5)).isEmpty(), "too long ago")
    }

    @Test
    fun vabIsCountedAndRemindedOnceTheChildIsWell() {
        val days = listOf(
            sick(ella.id, monday, home = sara.id, vab = true),
            sick(ella.id, tuesday, home = sara.id, vab = true, part = VabPart.Half),
            sick(simon.id, tuesday, home = johan.id),
        )
        val report = SickDays.vabToReport(days, sara.id, wednesday).single()
        assertEquals(1.5, report.total)
        assertEquals(monday, report.first)
        assertEquals(tuesday, report.last)
        assertTrue(SickDays.vabToReport(days, johan.id, wednesday).isEmpty(), "Johan stayed home without VAB")
        assertTrue(SickDays.vabToReport(days, sara.id, tuesday).isEmpty(), "not while she is still sick")
        assertTrue(SickDays.vabToReport(days.map { it.copy(vabReported = true) }, sara.id, wednesday).isEmpty())
        assertEquals(mapOf(sara.id to 1.5), SickDays.vabInYear(days, 2026))
        assertEquals(emptyMap(), SickDays.vabInYear(days, 2025))
    }

    @Test
    fun remindersForTheAbsenceReportAndForVab() {
        val morning = LocalDateTime(wednesday, LocalTime(7, 0)).toInstant(zone)
        val days = listOf(sick(ella.id, wednesday, home = sara.id, vab = true))
        val planned = ReminderPlanner.plan(input(now = morning).let { it.copy(base = it.base.copy(sickDays = days)) })

        val absence = planned.single { it.key == "sick:absence:${days[0].id}" }
        assertEquals(LocalDateTime(wednesday, LocalTime(7, 45)).toInstant(zone), absence.at)
        assertEquals(NotificationKind.Reminder, absence.kind)
        assertEquals(NotificationPayload.SickAbsence("Ella", "Björkängsskolan"), absence.payload)

        val vab = planned.single { it.key == "sick:vab:${ella.id}:$wednesday" }
        assertEquals(LocalDateTime(thursday, LocalTime(19, 0)).toInstant(zone), vab.at, "the evening she is well again")
        assertEquals(NotificationPayload.VabReport("Ella", 1.0), vab.payload)

        val johans = ReminderPlanner.plan(input(now = morning, me = johan.id).let { it.copy(base = it.base.copy(sickDays = days)) })
        assertTrue(johans.none { it.key.startsWith("sick:") }, "nothing for the one who is not at home")
        val reported = days.map { it.copy(absenceReported = true, vabReported = true) }
        val done = ReminderPlanner.plan(input(now = morning).let { it.copy(base = it.base.copy(sickDays = reported)) })
        assertTrue(done.none { it.key.startsWith("sick:") })
    }

    @Test
    fun sickDaysAreKeptForThisAndLastYear() {
        val old = sick(ella.id, LocalDate(2024, 12, 30))
        val last = sick(ella.id, LocalDate(2025, 1, 2))
        assertEquals(listOf(old), SickDays.expired(listOf(old, last), wednesday))
    }
}
