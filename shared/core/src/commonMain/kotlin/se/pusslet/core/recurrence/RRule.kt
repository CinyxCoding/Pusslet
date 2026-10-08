package se.pusslet.core.recurrence

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/** A BYDAY entry: "TU", "2MO" (second Monday), "-1FR" (last Friday). */
data class WeekdayNum(val day: DayOfWeek, val ordinal: Int? = null) {
    override fun toString(): String = (ordinal?.toString() ?: "") + RRule.dayCode(day)
}

/**
 * Subset of RFC 5545 RRULE used by family calendars and the ICS feeds we subscribe to.
 * Unsupported parts (BYHOUR, BYWEEKNO …) are kept in [unsupported] so the caller can decide how to degrade.
 */
data class RRule(
    val freq: Frequency,
    val interval: Int = 1,
    val count: Int? = null,
    /** Last allowed occurrence start, in the event's local time zone. */
    val until: LocalDateTime? = null,
    val byDay: List<WeekdayNum> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val byMonth: List<Int> = emptyList(),
    val bySetPos: List<Int> = emptyList(),
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val unsupported: Map<String, String> = emptyMap(),
) {
    init {
        require(interval >= 1) { "INTERVAL must be >= 1" }
    }

    /** Canonical RRULE value (without the "RRULE:" prefix). UNTIL is written as a local date-time. */
    fun format(): String = buildList {
        add("FREQ=${freq.name}")
        if (interval != 1) add("INTERVAL=$interval")
        count?.let { add("COUNT=$it") }
        until?.let { add("UNTIL=" + formatLocal(it)) }
        if (byDay.isNotEmpty()) add("BYDAY=" + byDay.joinToString(","))
        if (byMonthDay.isNotEmpty()) add("BYMONTHDAY=" + byMonthDay.joinToString(","))
        if (byMonth.isNotEmpty()) add("BYMONTH=" + byMonth.joinToString(","))
        if (bySetPos.isNotEmpty()) add("BYSETPOS=" + bySetPos.joinToString(","))
        if (weekStart != DayOfWeek.MONDAY) add("WKST=" + dayCode(weekStart))
    }.joinToString(";")

    override fun toString(): String = format()

    companion object {
        private val dayCodes = mapOf(
            "MO" to DayOfWeek.MONDAY,
            "TU" to DayOfWeek.TUESDAY,
            "WE" to DayOfWeek.WEDNESDAY,
            "TH" to DayOfWeek.THURSDAY,
            "FR" to DayOfWeek.FRIDAY,
            "SA" to DayOfWeek.SATURDAY,
            "SU" to DayOfWeek.SUNDAY,
        )

        fun dayCode(day: DayOfWeek): String = dayCodes.entries.first { it.value == day }.key

        /**
         * Parses "FREQ=WEEKLY;BYDAY=TU,TH;UNTIL=20261231T225959Z". A UTC UNTIL is converted to [zone].
         * Returns null for invalid rules (missing or unknown FREQ, malformed numbers).
         */
        fun parse(value: String, zone: TimeZone = TimeZone.UTC): RRule? {
            val parts = value.removePrefix("RRULE:").split(';').mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null else part.substring(0, idx).trim().uppercase() to part.substring(idx + 1).trim()
            }.toMap()
            val freq = parts["FREQ"]?.let { f -> Frequency.entries.firstOrNull { it.name == f.uppercase() } } ?: return null
            return runCatching {
                RRule(
                    freq = freq,
                    interval = parts["INTERVAL"]?.toInt() ?: 1,
                    count = parts["COUNT"]?.toInt(),
                    until = parts["UNTIL"]?.let { parseUntil(it, zone) },
                    byDay = parts["BYDAY"]?.split(',')?.filter { it.isNotBlank() }?.map { parseWeekdayNum(it) } ?: emptyList(),
                    byMonthDay = parts["BYMONTHDAY"]?.split(',')?.map { it.trim().toInt() } ?: emptyList(),
                    byMonth = parts["BYMONTH"]?.split(',')?.map { it.trim().toInt() } ?: emptyList(),
                    bySetPos = parts["BYSETPOS"]?.split(',')?.map { it.trim().toInt() } ?: emptyList(),
                    weekStart = parts["WKST"]?.let { dayCodes[it.uppercase()] } ?: DayOfWeek.MONDAY,
                    unsupported = parts.filterKeys { it !in SUPPORTED },
                )
            }.getOrNull()
        }

        private val SUPPORTED = setOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "BYMONTH", "BYSETPOS", "WKST")

        private fun parseWeekdayNum(token: String): WeekdayNum {
            val t = token.trim().uppercase()
            val code = t.takeLast(2)
            val day = requireNotNull(dayCodes[code]) { "Unknown weekday $token" }
            val ordinal = t.dropLast(2).takeIf { it.isNotEmpty() }?.removePrefix("+")?.toInt()
            return WeekdayNum(day, ordinal)
        }

        private fun parseUntil(raw: String, zone: TimeZone): LocalDateTime {
            val v = raw.trim()
            if (v.length == 8) {
                // DATE form: inclusive end of that day.
                return LocalDateTime(parseDate(v), LocalTime(23, 59, 59))
            }
            val local = LocalDateTime(parseDate(v.substring(0, 8)), parseTime(v.substring(9, 15)))
            return if (v.endsWith("Z")) local.toInstant(TimeZone.UTC).toLocalDateTime(zone) else local
        }

        internal fun parseDate(v: String): LocalDate = LocalDate(v.substring(0, 4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt())

        internal fun parseTime(v: String): LocalTime = LocalTime(v.substring(0, 2).toInt(), v.substring(2, 4).toInt(), v.substring(4, 6).toInt())

        internal fun formatLocal(dt: LocalDateTime): String {
            fun p(n: Int, w: Int = 2) = n.toString().padStart(w, '0')
            return "${p(dt.year, 4)}${p(dt.month.ordinal + 1)}${p(dt.day)}T${p(dt.hour)}${p(dt.minute)}${p(dt.second)}"
        }

        /** Convenience builders for the UI. */
        fun weekly(vararg days: DayOfWeek, interval: Int = 1): RRule = RRule(Frequency.WEEKLY, interval, byDay = days.map { WeekdayNum(it) })

        fun daily(interval: Int = 1): RRule = RRule(Frequency.DAILY, interval)

        fun monthlyOnDay(day: Int): RRule = RRule(Frequency.MONTHLY, byMonthDay = listOf(day))

        fun yearly(): RRule = RRule(Frequency.YEARLY)
    }
}
