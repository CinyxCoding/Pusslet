package se.pusslet.core.recurrence

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.MemberColor
import se.pusslet.core.time.minusDays
import kotlin.time.Duration

/**
 * Turns stored events (series, single events and per-occurrence overrides) into concrete occurrences in a date window,
 * expressed in [displayZone]. Unparseable rules degrade to a single occurrence instead of failing.
 */
object EventExpander {
    fun expand(
        events: List<Event>,
        windowStart: LocalDate,
        windowEnd: LocalDate,
        displayZone: TimeZone,
        source: EventSource = EventSource.Family,
        sourceName: String? = null,
        sourceColor: MemberColor? = null,
    ): List<EventOccurrence> {
        val overriddenDates: Map<String, Set<LocalDate>> = events
            .mapNotNull { e -> e.seriesId?.let { series -> e.originalDate?.let { series to it } } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, dates) -> dates.toSet() }

        val result = ArrayList<EventOccurrence>()
        for (event in events) {
            val zone = zoneOf(event.timeZoneId, displayZone)
            val duration = wallClockDuration(event.start, event.end)
            // Multi-day events that start before the window may still overlap it.
            val lookBack = maxOf(0L, duration.inWholeDays + 1).toInt()
            val starts: List<LocalDateTime> = when {
                event.seriesId != null -> listOf(event.start)
                !event.isRecurring -> listOf(event.start)
                else -> {
                    val rule = RRule.parse(requireNotNull(event.rrule), zone)
                    if (rule == null) {
                        listOf(event.start)
                    } else {
                        val excluded = event.exDates.toSet() + overriddenDates[event.id].orEmpty()
                        RecurrenceExpander.expand(rule, event.start, windowStart.minusDays(lookBack), windowEnd, excluded)
                    }
                }
            }
            for (s in starts) {
                val e = addWallClock(s, duration)
                val occurrence = EventOccurrence(
                    event = event,
                    start = convert(s, zone, displayZone, event.allDay),
                    end = convert(e, zone, displayZone, event.allDay),
                    occurrenceDate = if (event.seriesId != null) event.originalDate ?: s.date else s.date,
                    source = source,
                    sourceName = sourceName,
                    sourceColor = sourceColor,
                    dropOffAt = event.dropOff?.time?.let { convert(LocalDateTime(s.date, it), zone, displayZone, event.allDay).time },
                    pickUpAt = event.pickUp?.time?.let { convert(LocalDateTime(e.date, it), zone, displayZone, event.allDay).time },
                )
                if (overlapsWindow(occurrence, windowStart, windowEnd)) result += occurrence
            }
        }
        return result.sortedWith(compareBy({ it.start }, { !it.allDay }, { it.event.title }))
    }

    fun overlapsWindow(o: EventOccurrence, from: LocalDate, to: LocalDate): Boolean {
        val lastDay = lastDayOf(o)
        return o.start.date <= to && lastDay >= from
    }

    /** Last calendar day touched (exclusive midnight ends don't count). */
    fun lastDayOf(o: EventOccurrence): LocalDate {
        val endsAtMidnight = o.end.hour == 0 && o.end.minute == 0 && o.end.second == 0
        return if (endsAtMidnight && o.end > o.start) o.end.date.minusDays(1) else maxOf(o.start.date, o.end.date)
    }

    private fun zoneOf(id: String, fallback: TimeZone): TimeZone = runCatching { TimeZone.of(id) }.getOrDefault(fallback)

    /** Duration measured on the wall clock (ignores DST jumps so "17:30-18:30" stays one hour long). */
    fun wallClockDuration(start: LocalDateTime, end: LocalDateTime): Duration =
        end.toInstant(TimeZone.UTC) - start.toInstant(TimeZone.UTC)

    fun addWallClock(start: LocalDateTime, duration: Duration): LocalDateTime =
        (start.toInstant(TimeZone.UTC) + duration).toLocalDateTime(TimeZone.UTC)

    private fun convert(dt: LocalDateTime, from: TimeZone, to: TimeZone, allDay: Boolean): LocalDateTime =
        if (allDay || from == to) dt else dt.toInstant(from).toLocalDateTime(to)
}
