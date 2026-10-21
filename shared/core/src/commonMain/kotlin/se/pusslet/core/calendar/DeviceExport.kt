package se.pusslet.core.calendar

import se.pusslet.core.model.Event

/**
 * The family's events as the phone's calendar should hold them (BRIEF §3, ADR-040). A series is written once with its
 * RRULE; an occurrence moved or changed on its own is written as an event of its own, and its original date is added to
 * the series' EXDATE so the phone does not show it twice.
 */
object DeviceExport {
    fun prepare(events: List<Event>): List<Event> {
        val overridden = events.filter { it.isOverride }.groupBy({ it.seriesId }, { it.originalDate })
        return events.map { e ->
            when {
                e.isOverride -> e.copy(rrule = null, exDates = emptyList())
                e.isRecurring -> e.copy(exDates = (e.exDates + overridden[e.id].orEmpty().filterNotNull()).distinct().sorted())
                else -> e
            }
        }
    }
}
