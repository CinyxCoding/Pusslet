package se.pusslet.core.today

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.time.plusDays

/** What the home-screen widget has room for: the next thing, the day's hand-offs and what still has to be packed. */
data class TodayGlance(
    val date: LocalDate,
    /** All-day events today ("Studiedag"). */
    val allDay: List<EventOccurrence>,
    /** The next timed event that has not ended yet. */
    val next: TimelineItem.EventItem?,
    val nextIsNow: Boolean,
    /** How many timed events follow [next] today. */
    val laterToday: Int,
    /** Drop-offs and pick-ups still ahead today (gone at their time), including those nobody has taken yet. */
    val handoffs: List<TimelineItem.HandoffItem>,
    /** Unchecked items per child (children with nothing left are left out). */
    val packing: List<ChildPacking>,
    /** The first event tomorrow, shown when nothing timed is left today. */
    val tomorrow: EventOccurrence?,
    /** When the content changes by itself: the next event starts or ends, a hand-off passes, or midnight. */
    val refreshAt: LocalDateTime,
) {
    val isEmpty: Boolean get() = allDay.isEmpty() && next == null && handoffs.isEmpty() && packing.isEmpty()
}

object TodayGlancer {
    fun from(plan: TodayPlan, now: LocalTime): TodayGlance {
        val nowAt = LocalDateTime(plan.date, now)
        val events = plan.timeline.filterIsInstance<TimelineItem.EventItem>()
        val timedLeft = events.filter { !it.occurrence.allDay && it.occurrence.end > nowAt }.sortedBy { it.occurrence.start }
        val next = timedLeft.firstOrNull()
        val handoffs = plan.timeline.filterIsInstance<TimelineItem.HandoffItem>().filter { it.time > now }
        val tomorrowDate = plan.date.plusDays(1)
        val tomorrowEvents = plan.upcoming.firstOrNull { it.date == tomorrowDate }?.events.orEmpty()
        val tomorrow = if (next ==
            null
        ) {
            tomorrowEvents.filter { !it.allDay }.minByOrNull { it.start } ?: tomorrowEvents.firstOrNull()
        } else {
            null
        }
        val changes = buildList {
            next?.let { if (it.occurrence.start > nowAt) add(it.occurrence.start) else add(it.occurrence.end) }
            handoffs.forEach { add(LocalDateTime(plan.date, it.time)) }
        }
        return TodayGlance(
            date = plan.date,
            allDay = events.filter { it.occurrence.allDay }.map { it.occurrence },
            next = next,
            nextIsNow = next != null && next.occurrence.start <= nowAt,
            laterToday = (timedLeft.size - 1).coerceAtLeast(0),
            handoffs = handoffs,
            packing = plan.packing.map { p -> p.copy(items = p.items.filter { !it.checked }) }.filter { it.items.isNotEmpty() },
            tomorrow = tomorrow,
            refreshAt = changes.filter { it > nowAt }.minOrNull() ?: LocalDateTime(tomorrowDate, LocalTime(0, 0)),
        )
    }
}
