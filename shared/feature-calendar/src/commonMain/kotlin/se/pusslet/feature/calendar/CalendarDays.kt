package se.pusslet.feature.calendar

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.custody.CustodyCalculator
import se.pusslet.core.custody.CustodyPeriod
import se.pusslet.core.custody.Handover
import se.pusslet.core.holidays.Holiday
import se.pusslet.core.holidays.SwedishHolidays
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.Household
import se.pusslet.core.model.SickDay
import se.pusslet.core.sick.SickDays
import se.pusslet.core.time.datesUntil
import se.pusslet.core.time.plusDays
import se.pusslet.core.today.TodayPlanner
import se.pusslet.data.repository.CalendarLayer

/** One day in the week list, under the month grid and on the day screen. */
data class CalendarDay(
    val date: LocalDate,
    /** Everything this day after the filters, routines included. */
    val occurrences: List<EventOccurrence>,
    val holidays: List<Holiday>,
    /** Where the children are this day (one entry per custody schedule). */
    val custody: List<Household>,
    val redDay: Boolean,
    /** School and preschool as quiet lines (UX plan, Kalender 2). */
    val routines: List<RoutineLine> = emptyList(),
    /** The [occurrences] that are not part of a routine line: all-day first, then by start. */
    val events: List<EventOccurrence> = emptyList(),
    /** Custody changeovers this day: "Byte 15:00 till Ahmed". */
    val changeovers: List<CustodyChangeover> = emptyList(),
    /** Children home sick this day (ADR-047): "Ella sjuk · Sara hemma". Their activities are already left out. */
    val sick: List<SickLine> = emptyList(),
) {
    /** Nothing to show for the day; TalkBack hears "Inget planerat" with the date. */
    val isEmpty: Boolean get() = occurrences.isEmpty() && holidays.isEmpty() && changeovers.isEmpty() && sick.isEmpty()
}

/** A sick child on a calendar day, and who stays home (null = not decided). */
data class SickLine(val child: FamilyPerson, val homeWith: FamilyPerson?)

/**
 * A school or preschool day as one quiet line: "Simon: Johan lämnar 07:50 · Morfar hämtar 15:30". A tap opens [occurrence];
 * a line without hand-offs shows the routine itself ("Simon: Förskola 08:00–16:00").
 */
data class RoutineLine(
    /** The children it is about, in family order. */
    val children: List<FamilyPerson>,
    /** Drop-offs and pick-ups this day, by time. */
    val stops: List<RoutineStop>,
    /** The routine occurrences behind the line, by start; never empty. */
    val occurrences: List<EventOccurrence>,
) {
    /** What a tap on the line opens. */
    val occurrence: EventOccurrence get() = occurrences.first()

    /** The first hand-off nobody has taken: "Inte bestämt" and the target of "Jag tar det". */
    val firstOpen: RoutineStop? get() = stops.firstOrNull { it.isOpen }
}

/** One drop-off or pick-up in a [RoutineLine]. */
data class RoutineStop(
    val kind: HandoffKind,
    val time: LocalTime,
    /** Who does it; null is "Inte bestämt" (nobody set, or the person is no longer in the family). */
    val person: FamilyPerson?,
    /** The signed-in member does it ("Du lämnar 07:50"). */
    val isMe: Boolean,
    val occurrence: EventOccurrence,
) {
    val isOpen: Boolean get() = person == null
}

/**
 * Where the children are during the shown week: "Hos Ahmed (mån–sön)" (UX plan, Kalender 8). [children] is only filled when
 * the schedule does not cover every child in the family, so the line can say whose it is ("Ella hos Sara (mån–sön)").
 */
data class CustodyWeek(val children: List<FamilyPerson>, val periods: List<CustodyPeriod>)

/** The children move today: "Byte 15:00 till Ahmed". [children] as in [CustodyWeek]. */
data class CustodyChangeover(val handover: Handover, val children: List<FamilyPerson>)

/** Pure building blocks for the calendar: filters, routine lines and custody, shared by the week, month and day views. */
object CalendarDays {
    /**
     * The days [from]..[to] with [occurrences] filtered by [personFilter] (empty = everyone) and [layers]. [me] is the signed-in
     * member, for "Du lämnar".
     */
    fun build(
        from: LocalDate,
        to: LocalDate,
        occurrences: List<EventOccurrence>,
        people: List<FamilyPerson>,
        schedules: List<CustodySchedule>,
        personFilter: Set<String>,
        layers: Set<CalendarLayer>,
        me: String?,
        sickDays: List<SickDay> = emptyList(),
    ): List<CalendarDay> {
        val byId = people.associateBy { it.id }
        val visible = if (personFilter.isEmpty()) {
            occurrences
        } else {
            occurrences.filter { o -> o.attending.any { it in personFilter } }
        }
        val custody = if (CalendarLayer.Custody in layers) relevant(schedules, personFilter) else emptyList()
        return from.datesUntil(to).map { d ->
            val todays = visible.filter { it.overlapsDate(d) }
            val routines = routineLines(d, todays, people, me)
            val quiet = routines.flatMap { line -> line.occurrences.map { it.key } }.toSet()
            CalendarDay(
                date = d,
                occurrences = todays,
                holidays = if (CalendarLayer.Holidays in layers) SwedishHolidays.forDate(d) else emptyList(),
                custody = custody.mapNotNull { CustodyCalculator.householdOn(it, d) },
                redDay = SwedishHolidays.isRedDay(d),
                routines = routines,
                events = todays.filter { it.key !in quiet }.sortedWith(compareBy({ !it.allDay }, { it.start })),
                changeovers = custody.mapNotNull { s ->
                    if (!CustodyCalculator.isHandoverDay(s, d)) return@mapNotNull null
                    CustodyCalculator.nextHandover(s, d)?.let { CustodyChangeover(it, namedChildren(s, people)) }
                },
                sick = SickDays.on(sickDays, d)
                    .filter { personFilter.isEmpty() || it.childId in personFilter }
                    .mapNotNull { s -> byId[s.childId]?.let { SickLine(it, s.homeWith?.let(byId::get)) } },
            )
        }
    }

    /** The custody stretches of the week [from]..[to], one entry per schedule that the filter lets through. */
    fun custodyWeek(
        from: LocalDate,
        to: LocalDate,
        schedules: List<CustodySchedule>,
        people: List<FamilyPerson>,
        personFilter: Set<String>,
        layers: Set<CalendarLayer>,
    ): List<CustodyWeek> {
        if (CalendarLayer.Custody !in layers) return emptyList()
        return relevant(schedules, personFilter).mapNotNull { s ->
            CustodyCalculator.periods(s, from, to).takeIf { it.isNotEmpty() }?.let { CustodyWeek(namedChildren(s, people), it) }
        }
    }

    /**
     * School, preschool and fritids (see [TodayPlanner.isRoutine]) with a child, timed: shown as their drop-off and pick-up
     * instead of a big event. Routines of the same children on the same day share one line.
     */
    fun routineLines(date: LocalDate, occurrences: List<EventOccurrence>, people: List<FamilyPerson>, me: String?): List<RoutineLine> {
        val byId = people.associateBy { it.id }
        val children = people.filter { it.isChild }
        return occurrences
            .filter { o -> !o.allDay && TodayPlanner.isRoutine(o) && o.attending.any { byId[it]?.isChild == true } }
            .sortedBy { it.start }
            .groupBy { o -> children.filter { it.id in o.attending }.map { it.id } }
            .map { (ids, group) ->
                RoutineLine(
                    children = ids.mapNotNull { byId[it] },
                    stops = group.flatMap { stopsOf(it, date, byId, me) }.sortedBy { it.time },
                    occurrences = group,
                )
            }
            .sortedBy { line -> line.stops.firstOrNull()?.time ?: line.occurrence.start.time }
    }

    private fun stopsOf(o: EventOccurrence, date: LocalDate, byId: Map<String, FamilyPerson>, me: String?): List<RoutineStop> {
        // A drop-off belongs to the first day, a pick-up to the last one (an end at midnight belongs to the day before).
        val lastDay = if (o.end.time == LocalTime(0, 0) && o.end.date > o.start.date) o.end.date.plusDays(-1) else o.end.date

        fun stop(kind: HandoffKind, personId: String?, time: LocalTime) =
            RoutineStop(kind, time, personId?.let(byId::get), isMe = personId != null && personId == me, occurrence = o)
        return buildList {
            val dropTime = o.dropOffTime()
            o.event.dropOff?.let { if (dropTime != null && o.start.date == date) add(stop(HandoffKind.DropOff, it.personId, dropTime)) }
            val pickTime = o.pickUpTime()
            o.event.pickUp?.let { if (pickTime != null && lastDay == date) add(stop(HandoffKind.PickUp, it.personId, pickTime)) }
        }
    }

    /** The stored person filter without people who have left the family, so they can no longer hide everything else. */
    fun effectiveFilter(personFilter: Set<String>, people: List<FamilyPerson>): Set<String> =
        personFilter.filterTo(mutableSetOf()) { id -> people.any { it.id == id } }

    /** Schedules shown with the person filter: all of them, or those about a child in the filter. */
    private fun relevant(schedules: List<CustodySchedule>, personFilter: Set<String>): List<CustodySchedule> =
        if (personFilter.isEmpty()) schedules else schedules.filter { s -> s.childIds.any { it in personFilter } }

    /** The schedule's children when it does not cover every child in the family; empty otherwise. */
    private fun namedChildren(schedule: CustodySchedule, people: List<FamilyPerson>): List<FamilyPerson> {
        val children = people.filter { it.isChild }
        return if (children.all { it.id in schedule.childIds }) emptyList() else children.filter { it.id in schedule.childIds }
    }
}
