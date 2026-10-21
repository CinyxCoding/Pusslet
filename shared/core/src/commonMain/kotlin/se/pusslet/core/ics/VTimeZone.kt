package se.pusslet.core.ics

import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import se.pusslet.core.recurrence.RRule
import se.pusslet.core.recurrence.RecurrenceExpander
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * A VTIMEZONE from a feed (RFC 5545 §3.6.5): its STANDARD and DAYLIGHT observances, each with the offsets around the switch,
 * the local time of the first switch and an optional yearly RRULE (BYMONTH + BYDAY such as `-1SU` or `2SU`) or RDATEs.
 *
 * It matters only when the TZID is not a zone we know by name, e.g. Outlook's "Customized Time Zone" or a localised Windows
 * name. kotlinx-datetime cannot build a time zone from rules, so [resolve] looks for a known zone ([candidates]) that keeps
 * the same clock as these rules in the years the feed uses, and otherwise keeps the standard offset without daylight saving.
 */
internal class VTimeZone(val id: String, val observances: List<Observance>) {
    data class Observance(
        val daylight: Boolean,
        val offsetFrom: UtcOffset,
        val offsetTo: UtcOffset,
        /** Local time of the first switch, on the clock before it ([offsetFrom]). */
        val start: LocalDateTime,
        val rule: RRule? = null,
        val rDates: List<LocalDateTime> = emptyList(),
    )

    data class Transition(val at: Instant, val offset: UtcOffset)

    /** The offset outside daylight saving: the newest STANDARD observance (or the only one there is). */
    val standardOffset: UtcOffset?
        get() = (observances.filter { !it.daylight }.maxByOrNull { it.start } ?: observances.maxByOrNull { it.start })?.offsetTo

    /** Every switch from the start of [fromYear] to the end of [toYear], plus the single-switch observances that came before. */
    fun transitions(fromYear: Int, toYear: Int): List<Transition> {
        val from = LocalDate(fromYear, 1, 1)
        val to = LocalDate(toYear, DECEMBER, LAST_DAY)
        return observances.flatMap { o ->
            val starts = when (val rule = o.rule) {
                null -> listOf(o.start)
                else -> RecurrenceExpander.expand(rule, o.start, from, to)
            } + o.rDates
            starts.map { Transition(it.toInstant(o.offsetFrom), o.offsetTo) }
        }.sortedBy { it.at }
    }

    /** The offset in force at [instant]: the newest switch at or before it, else the standard offset. */
    fun offsetAt(instant: Instant, transitions: List<Transition>): UtcOffset? =
        transitions.lastOrNull { it.at <= instant }?.offset ?: standardOffset

    /**
     * The zone to use for events with this TZID. [years] are the years the feed's events start in; the rules are compared
     * with each candidate zone at noon on the 1st and 15th of every month and an hour on either side of every switch.
     * Returns null when the VTIMEZONE has no usable observance.
     */
    fun resolve(years: Collection<Int>): Resolved? {
        val standard = standardOffset ?: return null
        val sampleYears = years.distinct().sorted().takeLast(MAX_SAMPLE_YEARS)
        if (observances.none { it.daylight } || sampleYears.isEmpty()) return Resolved(FixedOffsetTimeZone(standard), matched = true)
        val transitions = transitions(sampleYears.first() - 1, sampleYears.last())
        val switchesInYears = transitions.filter { t -> sampleYears.any { y -> t.at in yearRange(y) } }
        if (switchesInYears.isEmpty()) {
            // The daylight saving rules ended before these years: a fixed offset is exact.
            val offset = sampleYears.firstNotNullOfOrNull {
                offsetAt(LocalDateTime(it, 7, 1, 12, 0).toInstant(UtcOffset.ZERO), transitions)
            }
            return Resolved(FixedOffsetTimeZone(offset ?: standard), matched = true)
        }
        val samples = sampleYears.flatMap { y ->
            (1..MONTHS).flatMap { m -> SAMPLE_DAYS.map { d -> LocalDateTime(LocalDate(y, m, d), NOON).toInstant(UtcOffset.ZERO) } }
        } + switchesInYears.flatMap { listOf(it.at - 1.hours, it.at + 1.hours) }
        val expected = samples.map { offsetAt(it, transitions) }
        val match = candidates.firstNotNullOfOrNull { id ->
            val zone = runCatching { TimeZone.of(id) }.getOrNull()
            zone?.takeIf { z -> samples.indices.all { i -> z.offsetAt(samples[i]) == expected[i] } }
        }
        return match?.let { Resolved(it, matched = true) } ?: Resolved(FixedOffsetTimeZone(standard), matched = false)
    }

    /** [matched] is false when the rules have daylight saving but no known zone keeps the same clock. */
    data class Resolved(val zone: TimeZone, val matched: Boolean)

    private fun yearRange(year: Int): ClosedRange<Instant> =
        LocalDateTime(year, 1, 1, 0, 0).toInstant(UtcOffset.ZERO)..LocalDateTime(year, DECEMBER, LAST_DAY, 23, 59).toInstant(UtcOffset.ZERO)

    companion object {
        private const val MAX_SAMPLE_YEARS = 3
        private const val MONTHS = 12
        private const val DECEMBER = 12
        private const val LAST_DAY = 31
        private val SAMPLE_DAYS = listOf(1, 15)
        private val NOON = LocalTime(12, 0)

        /**
         * Zones with daylight saving that feeds commonly describe with rules instead of a name, most likely first: the EU
         * forms (last Sunday of March and October at 01:00 UTC), North America (second Sunday of March, first of November),
         * Australia and New Zealand. Zones without daylight saving need no match: their fixed offset is exact.
         */
        val candidates = listOf(
            "Europe/Stockholm",
            "Europe/London",
            "Europe/Helsinki",
            "Atlantic/Azores",
            "America/New_York",
            "America/Chicago",
            "America/Denver",
            "America/Los_Angeles",
            "America/Anchorage",
            "America/Halifax",
            "America/St_Johns",
            "Australia/Sydney",
            "Australia/Adelaide",
            "Pacific/Auckland",
        )
    }
}
