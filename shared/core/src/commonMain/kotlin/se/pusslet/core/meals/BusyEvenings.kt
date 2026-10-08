package se.pusslet.core.meals

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource

/**
 * "Prioritera snabbt på dagar med kvällsaktiviteter": an evening is busy when something with a time overlaps the
 * dinner window (16:30–19:00) – football practice, a parents' meeting, swimming. All-day events and holidays do not count.
 */
object BusyEvenings {
    val windowStart = LocalTime(16, 30)
    val windowEnd = LocalTime(19, 0)

    fun of(occurrences: List<EventOccurrence>): Map<LocalDate, List<EventOccurrence>> = occurrences
        .filter { !it.allDay && it.source != EventSource.Holiday }
        .flatMap { occ -> datesInWindow(occ).map { it to occ } }
        .groupBy({ it.first }, { it.second })

    private fun datesInWindow(occ: EventOccurrence): List<LocalDate> {
        val dates = generateSequence(occ.start.date) { d -> if (d < occ.end.date) LocalDate.fromEpochDays(d.toEpochDays() + 1) else null }
        return dates.filter { d ->
            val from = LocalDateTime(d, windowStart)
            val to = LocalDateTime(d, windowEnd)
            occ.start < to && occ.end > from
        }.toList()
    }
}
