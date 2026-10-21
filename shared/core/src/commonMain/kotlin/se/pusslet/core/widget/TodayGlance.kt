package se.pusslet.core.widget

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.notify.BriefHandoff
import se.pusslet.core.notify.BriefPacking
import se.pusslet.core.notify.ReminderPlanner
import se.pusslet.core.today.PackingState
import se.pusslet.core.today.TimelineItem
import se.pusslet.core.today.TodayPlan

/**
 * What the Idag widget on the home screen shows (BRIEF §4.11, ADR-039): the next event, who drops off and picks up, and
 * what to bring. The facts come from the same [TodayPlan] as Idag, and hand-offs and packing use Morgonkollen's brief
 * types so the widget says them in the same words. The widget does not follow Idag's own layout (UX plan, Idag).
 */
data class TodayGlance(
    val date: LocalDate,
    /**
     * The next event to start today, else today's first all-day event; null when nothing is left. One already under way
     * (school 08–16 at lunch) is not "next", as Morgonkollen's "Först idag".
     */
    val next: GlanceEvent? = null,
    /** Timed events that start later today, after [next]. */
    val later: Int = 0,
    /** Hand-offs still to come today, as Morgonkollen says them. */
    val handoffs: List<BriefHandoff> = emptyList(),
    /** What is still to be packed today. */
    val packing: List<BriefPacking> = emptyList(),
    /** "Allt är packat", until the children have left. */
    val allPacked: Boolean = false,
    /** Things under "Att lösa" (today and tomorrow). */
    val problems: Int = 0,
    /** The next time today the widget changes by itself (an event starts, a hand-off passes); null = not today. */
    val nextChange: LocalTime? = null,
) {
    val isEmpty: Boolean get() = next == null && handoffs.isEmpty() && packing.isEmpty() && !allPacked && problems == 0
}

/** One event: what, when (null = all day) and for whom. [date] is the occurrence's own date, for opening it. */
data class GlanceEvent(val eventId: String, val date: LocalDate, val title: String, val time: LocalTime?, val names: List<String>)

object TodayGlances {
    fun from(plan: TodayPlan): TodayGlance {
        val now = plan.now
        val today = plan.date
        val (allDay, timed) = plan.timeline.filterIsInstance<TimelineItem.EventItem>().partition { it.occurrence.allDay }
        val upcoming = timed.filter { it.occurrence.startsAfter(today, now) }.sortedBy { it.occurrence.start.time }
        val next = upcoming.firstOrNull() ?: allDay.firstOrNull()

        val packing = plan.hero.packing
        val packingShown = packing.isShownAt(now)
        val allPacked = packingShown && packing.state == PackingState.AllPacked
        val changes = buildList {
            upcoming.forEach { add(it.occurrence.start.time) }
            plan.hero.handoffs.forEach { line -> line.stops.forEach { add(it.time) } }
            if (allPacked) packing.leavesAt?.let(::add)
        }
        return TodayGlance(
            date = today,
            next = next?.let { item ->
                GlanceEvent(
                    eventId = item.occurrence.event.id,
                    date = item.occurrence.occurrenceDate,
                    title = item.occurrence.event.title,
                    time = if (item.occurrence.allDay) null else item.occurrence.start.time,
                    names = item.people.map { it.name },
                )
            },
            later = upcoming.count { it !== next },
            handoffs = plan.hero.handoffs.map(ReminderPlanner::brief),
            packing = if (packingShown && packing.state == PackingState.ToPack) ReminderPlanner.unpacked(packing.children) else emptyList(),
            allPacked = allPacked,
            problems = plan.problems.size + plan.cannotMake.size,
            nextChange = changes.filter { it > now }.minOrNull(),
        )
    }

    private fun EventOccurrence.startsAfter(today: LocalDate, now: LocalTime): Boolean = start.date == today && start.time > now
}
