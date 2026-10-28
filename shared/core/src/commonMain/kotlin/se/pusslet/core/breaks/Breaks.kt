package se.pusslet.core.breaks

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.holidays.SwedishHolidays
import se.pusslet.core.model.BreakCare
import se.pusslet.core.model.BreakPlan
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.StayDay
import se.pusslet.core.model.StaySchedule
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.datesUntil
import se.pusslet.core.time.minusDays
import se.pusslet.core.time.plusDays

/** A school break in the calendar, with the weekdays that need someone and the family's plan for them (ADR-051). */
data class SchoolBreak(
    val eventId: String,
    val title: String,
    val start: LocalDate,
    /** Inclusive. */
    val end: LocalDate,
    /** The children it is for; empty = every child. */
    val childIds: List<String>,
    /** Monday to Friday that are not red days: the days someone has to look after the children. */
    val days: List<LocalDate>,
    val plan: BreakPlan,
) {
    val planId: String get() = plan.id

    /** Days nobody has said anything about yet. */
    val unplanned: List<LocalDate> get() = days.filter { it.toString() !in plan.days }

    /** Days on break care: those are what the school wants booked. */
    val careDays: List<LocalDate> get() = days.filter { plan.days[it.toString()]?.care == BreakCare.Care }
}

/**
 * "Lov och vistelsetider" as data (ADR-051). Breaks come from the calendar: any all-day event tagged
 * [EventTags.HOLIDAY_BREAK], typed ("höstlov v 44"), added in the view or from the school's ICS feed.
 */
object Breaks {
    /** How far ahead Idag mentions a break that is not planned yet (Idag reads ten days ahead). */
    const val SOON_DAYS = 10

    /** The parents are reminded of the care booking this many days before [BreakPlan.deadline], and on the day. */
    const val DEADLINE_LEAD_DAYS = 2

    /** The breaks in [occurrences] that are not over on [today], first first; one per event and first day. */
    fun breaks(occurrences: List<EventOccurrence>, plans: List<BreakPlan>, today: LocalDate): List<SchoolBreak> {
        val byId = plans.associateBy { it.id }
        return occurrences
            .filter { o -> o.allDay && o.event.tags.any { it == EventTags.HOLIDAY_BREAK } }
            .map { o ->
                // An all-day event ends at midnight after its last day.
                val last = if (o.end.date > o.start.date && o.end.time == LocalTime(0, 0)) o.end.date.minusDays(1) else o.end.date
                Triple(o, o.start.date, maxOf(o.start.date, last))
            }
            .filter { (_, _, end) -> end >= today }
            .distinctBy { (o, start, _) -> BreakPlan.idFor(o.event.seriesId ?: o.event.id, start) }
            .map { (o, start, end) ->
                val eventId = o.event.seriesId ?: o.event.id
                val id = BreakPlan.idFor(eventId, start)
                SchoolBreak(
                    eventId = eventId,
                    title = o.event.title,
                    start = start,
                    end = end,
                    childIds = o.event.participantIds,
                    days = weekdays(start, end),
                    plan = byId[id] ?: BreakPlan(id, eventId, start),
                )
            }
            .sortedBy { it.start }
    }

    /** Monday–Friday from [start] through [end] that are not red days ("helgdagar"). */
    fun weekdays(start: LocalDate, end: LocalDate): List<LocalDate> =
        start.datesUntil(end).filter { it.dayOfWeek !in WEEKEND && !SwedishHolidays.isRedDay(it) }

    /** A break that starts within [SOON_DAYS] days (or is going on) with days or a booking left to sort out. */
    fun needsPlan(b: SchoolBreak, today: LocalDate): Boolean {
        val soon = b.start.toEpochDays() - today.toEpochDays() <= SOON_DAYS
        val open = b.unplanned.any { it >= today } || (b.plan.deadline != null && !b.plan.reported && b.plan.deadline >= today)
        return soon && open
    }

    /** "Höstlov" in week 44 of the autumn after [today]: Monday to Friday, the week most municipalities use. */
    fun autumnBreak(today: LocalDate): Pair<LocalDate, LocalDate> {
        val year = if (today > IsoWeek(today.year, AUTUMN_WEEK).sunday) today.year + 1 else today.year
        val week = IsoWeek(year, AUTUMN_WEEK)
        return week.monday to week.monday.plusDays(4)
    }

    /** The Monday–Friday of ISO week [week] next time it comes ("sportlov v 9"). */
    fun weekBreak(today: LocalDate, week: Int): Pair<LocalDate, LocalDate> {
        var year = today.year
        if (IsoWeek(year, week).sunday < today) year += 1
        val w = IsoWeek(year, week)
        return w.monday to w.monday.plusDays(4)
    }

    // region vistelsetider

    /**
     * A child's hours as the calendar has them: for each weekday, the child's weekly preschool or school series with a
     * drop-off or pick-up, from the drop-off (or start) to the pick-up (or end). Earliest start and latest end win when
     * there are two (school and fritids).
     */
    fun stayFromCalendar(childId: String, occurrences: List<EventOccurrence>): Map<DayOfWeek, StayDay> = occurrences
        .filter { o ->
            !o.allDay && childId in o.event.participantIds && o.event.isRecurring &&
                (o.event.dropOff != null || o.event.pickUp != null)
        }
        .groupBy { it.start.date.dayOfWeek }
        .mapValues { (_, list) ->
            StayDay(
                start = list.minOf { it.dropOffAt ?: it.event.dropOff?.time ?: it.start.time },
                end = list.maxOf { it.pickUpAt ?: it.event.pickUp?.time ?: it.end.time },
            )
        }
        .filterKeys { it !in WEEKEND }

    /** Minutes a week in the schedule ("38 tim 30 min"). */
    fun minutesPerWeek(schedule: StaySchedule): Int = schedule.days.values.sumOf { d ->
        ((d.end.hour * 60 + d.end.minute) - (d.start.hour * 60 + d.start.minute)).coerceAtLeast(0)
    }

    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    private const val AUTUMN_WEEK = 44

    // endregion
}
