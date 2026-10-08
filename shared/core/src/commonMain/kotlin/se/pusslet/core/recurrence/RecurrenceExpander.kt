package se.pusslet.core.recurrence

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus
import se.pusslet.core.time.isLeapYear
import se.pusslet.core.time.minusDays
import se.pusslet.core.time.plusDays

/**
 * Expands an [RRule] into occurrence start date-times. Works on floating local time: the time of day is taken
 * from [start] (wall clock), which is what families expect across daylight saving changes.
 *
 * Follows RFC 5545 semantics for the supported parts: BYxxx parts *expand* within the frequency period
 * or *limit* candidates, then BYSETPOS selects, then COUNT/UNTIL bound the set. DTSTART is always the first occurrence.
 */
object RecurrenceExpander {
    /** Hard cap against runaway rules (e.g. DAILY without end over decades). */
    private const val MAX_PERIODS = 20_000

    fun expand(
        rule: RRule,
        start: LocalDateTime,
        windowStart: LocalDate,
        windowEnd: LocalDate,
        exDates: Set<LocalDate> = emptySet(),
    ): List<LocalDateTime> {
        val result = ArrayList<LocalDateTime>()
        var emitted = 0
        val time = start.time
        val startDate = start.date

        fun accept(date: LocalDate): Boolean {
            // Returns false when the series is finished.
            val dt = LocalDateTime(date, time)
            if (rule.until != null && dt > rule.until) return false
            if (rule.count != null && emitted >= rule.count) return false
            emitted++
            if (date in windowStart..windowEnd && date !in exDates) result += dt
            return true
        }

        // DTSTART is always the first instance.
        if (!accept(startDate)) return result

        var periodIndex = 0
        var periods = 0
        while (periods < MAX_PERIODS) {
            // Occurrences are increasing, so once a period starts after the window nothing more can match.
            if (periodStart(rule, startDate, periodIndex) > windowEnd) break
            val candidates = candidatesForPeriod(rule, startDate, periodIndex) ?: break
            val selected = applySetPos(candidates.sorted(), rule.bySetPos)
            for (d in selected) {
                if (d <= startDate) continue
                if (d > windowEnd) return result
                if (!accept(d)) return result
            }
            periodIndex += rule.interval
            periods++
        }
        return result
    }

    /** Start of the period with the given index (index 0 contains DTSTART). */
    private fun periodStart(rule: RRule, startDate: LocalDate, index: Int): LocalDate = when (rule.freq) {
        Frequency.DAILY -> startDate.plusDays(index)
        Frequency.WEEKLY -> weekStartOf(startDate, rule.weekStart).plusDays(index * 7)
        Frequency.MONTHLY -> firstOfMonth(startDate).plus(index, DateTimeUnit.MONTH)
        Frequency.YEARLY -> LocalDate(startDate.year + index, 1, 1)
    }

    /** All dates in the period produced by the BYxxx parts, before BYSETPOS. Null = cannot continue. */
    private fun candidatesForPeriod(rule: RRule, startDate: LocalDate, index: Int): List<LocalDate>? {
        val ps = periodStart(rule, startDate, index)
        if (ps.year > 9000) return null
        return when (rule.freq) {
            Frequency.DAILY -> listOf(ps).filter { limitByMonth(rule, it) && limitByMonthDay(rule, it) && limitByDay(rule, it) }
            Frequency.WEEKLY -> {
                val days = if (rule.byDay.isEmpty()) listOf(startDate.dayOfWeek) else rule.byDay.map { it.day }
                (0 until 7).map { ps.plusDays(it) }.filter { it.dayOfWeek in days && limitByMonth(rule, it) }
            }
            Frequency.MONTHLY -> if (rule.byMonth.isNotEmpty() && ps.month.number !in rule.byMonth) {
                emptyList()
            } else {
                monthCandidates(rule, ps, startDate)
            }
            Frequency.YEARLY -> yearCandidates(rule, ps.year, startDate)
        }
    }

    private fun monthCandidates(rule: RRule, firstOfMonth: LocalDate, startDate: LocalDate): List<LocalDate> {
        val year = firstOfMonth.year
        val month = firstOfMonth.month.number
        val length = monthLength(year, month)
        val all = (1..length).map { LocalDate(year, month, it) }
        val byMonthDay = rule.byMonthDay.mapNotNull { md ->
            val day = if (md > 0) md else length + md + 1
            if (day in 1..length) LocalDate(year, month, day) else null
        }
        val byDay = if (rule.byDay.isEmpty()) emptyList() else expandByDayIn(all, rule.byDay)
        return when {
            rule.byMonthDay.isNotEmpty() && rule.byDay.isNotEmpty() -> byMonthDay.filter { it in byDay }
            rule.byMonthDay.isNotEmpty() -> byMonthDay
            rule.byDay.isNotEmpty() -> byDay
            // Default: same day of month as DTSTART; months without that day are skipped (RFC 5545).
            startDate.day <= length -> listOf(LocalDate(year, month, startDate.day))
            else -> emptyList()
        }
    }

    private fun yearCandidates(rule: RRule, year: Int, startDate: LocalDate): List<LocalDate> {
        if (rule.byMonth.isNotEmpty()) {
            return rule.byMonth.sorted().flatMap { m ->
                val first = LocalDate(year, m, 1)
                if (rule.byMonthDay.isEmpty() && rule.byDay.isEmpty()) {
                    val len = monthLength(year, m)
                    if (startDate.day <= len) listOf(LocalDate(year, m, startDate.day)) else emptyList()
                } else {
                    monthCandidates(rule.copy(byMonth = emptyList()), first, startDate)
                }
            }
        }
        if (rule.byDay.isNotEmpty()) {
            val all = LocalDate(year, 1, 1).let { jan1 -> (0 until if (isLeapYear(year)) 366 else 365).map { jan1.plusDays(it) } }
            return expandByDayIn(all, rule.byDay)
        }
        if (rule.byMonthDay.isNotEmpty()) {
            return (1..12).flatMap { m -> monthCandidates(rule, LocalDate(year, m, 1), startDate) }
        }
        val m = startDate.month.number
        val d = startDate.day
        // Feb 29 only occurs in leap years.
        return if (d <= monthLength(year, m)) listOf(LocalDate(year, m, d)) else emptyList()
    }

    /** BYDAY within a set of consecutive dates (month or year): plain days match all, ordinals pick nth/nth-last. */
    private fun expandByDayIn(dates: List<LocalDate>, byDay: List<WeekdayNum>): List<LocalDate> = byDay.flatMap { wd ->
        val matching = dates.filter { it.dayOfWeek == wd.day }
        when {
            wd.ordinal == null -> matching
            wd.ordinal > 0 -> listOfNotNull(matching.getOrNull(wd.ordinal - 1))
            else -> listOfNotNull(matching.getOrNull(matching.size + wd.ordinal))
        }
    }.distinct()

    private fun applySetPos(sorted: List<LocalDate>, setPos: List<Int>): List<LocalDate> {
        if (setPos.isEmpty()) return sorted
        return setPos.mapNotNull { p -> if (p > 0) sorted.getOrNull(p - 1) else sorted.getOrNull(sorted.size + p) }.distinct().sorted()
    }

    private fun limitByMonth(rule: RRule, d: LocalDate) = rule.byMonth.isEmpty() || d.month.number in rule.byMonth

    private fun limitByMonthDay(rule: RRule, d: LocalDate): Boolean {
        if (rule.byMonthDay.isEmpty()) return true
        val len = monthLength(d.year, d.month.number)
        return rule.byMonthDay.any { md -> (if (md > 0) md else len + md + 1) == d.day }
    }

    private fun limitByDay(rule: RRule, d: LocalDate) = rule.byDay.isEmpty() || rule.byDay.any { it.day == d.dayOfWeek }

    private fun weekStartOf(date: LocalDate, weekStart: DayOfWeek): LocalDate {
        val diff = (date.dayOfWeek.isoDayNumber - weekStart.isoDayNumber + 7) % 7
        return date.minusDays(diff)
    }

    private fun firstOfMonth(d: LocalDate) = LocalDate(d.year, d.month, 1)

    fun monthLength(year: Int, month: Int): Int = when (month) {
        2 -> if (isLeapYear(year)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
}
