package se.pusslet.core.ics

import kotlinx.datetime.LocalDate
import se.pusslet.core.model.Event
import se.pusslet.core.recurrence.RRule
import se.pusslet.core.time.minusDays

/**
 * Subscribed feeds can hold years of history (a public project calendar had 15 000 events). Only what can still show up
 * is kept: one-off events that end after [today] minus [keepPastDays], series that have not ended by then (open-ended
 * or COUNT series are kept) and moved occurrences in that window.
 */
object FeedWindow {
    fun relevant(events: List<Event>, today: LocalDate, keepPastDays: Int = 90): List<Event> {
        val cutoff = today.minusDays(keepPastDays)
        return events.filter { e ->
            when {
                e.isOverride -> e.end.date >= cutoff || (e.originalDate ?: e.start.date) >= cutoff
                e.isRecurring -> RRule.parse(e.rrule ?: "")?.until?.let { it.date >= cutoff } ?: true
                else -> e.end.date >= cutoff
            }
        }
    }
}
