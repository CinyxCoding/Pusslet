package se.pusslet.core.meals

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.EventOccurrence

/**
 * "Prioritera snabbt på dagar med kvällsaktiviteter": an evening is busy when something with a time overlaps the
 * dinner window (16:30–19:00 by default). All-day events (holidays, study days) do not count.
 */
object EveningLoad {
    val windowStart: LocalTime = LocalTime(16, 30)
    val windowEnd: LocalTime = LocalTime(19, 0)

    fun busyEvents(occurrences: List<EventOccurrence>, date: LocalDate): List<EventOccurrence> {
        val from = LocalDateTime(date, windowStart)
        val to = LocalDateTime(date, windowEnd)
        return occurrences.filter { !it.allDay && it.start < to && it.end > from }.sortedBy { it.start }
    }

    fun isBusy(occurrences: List<EventOccurrence>, date: LocalDate): Boolean = busyEvents(occurrences, date).isNotEmpty()
}
