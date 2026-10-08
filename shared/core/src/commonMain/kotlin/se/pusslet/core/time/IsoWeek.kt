package se.pusslet.core.time

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable

/**
 * ISO 8601 week (the Swedish "vecka"): weeks start on Monday and week 1 is the week containing the first Thursday.
 * A year has 52 or 53 weeks; dates at the turn of the year can belong to the neighbouring week-year.
 */
@Serializable
data class IsoWeek(val weekYear: Int, val week: Int) : Comparable<IsoWeek> {
    init {
        require(week in 1..weeksInYear(weekYear)) { "Week $week does not exist in $weekYear" }
    }

    /** Monday of this week. */
    val monday: LocalDate
        get() = mondayOfWeekOne(weekYear).plus(DatePeriod(days = (week - 1) * 7))

    val sunday: LocalDate get() = monday.plus(DatePeriod(days = 6))

    val days: List<LocalDate> get() = (0 until 7).map { monday.plus(DatePeriod(days = it)) }

    fun next(): IsoWeek = of(monday.plus(DatePeriod(days = 7)))

    fun previous(): IsoWeek = of(monday.minus(DatePeriod(days = 7)))

    fun plusWeeks(n: Int): IsoWeek = of(monday.plus(DatePeriod(days = 7 * n)))

    operator fun contains(date: LocalDate): Boolean = of(date) == this

    /** Stable key used as Firestore document id for meal plans, e.g. "2026-W42". */
    val key: String get() = "$weekYear-W${week.toString().padStart(2, '0')}"

    override fun compareTo(other: IsoWeek): Int = compareValuesBy(this, other, { it.weekYear }, { it.week })

    override fun toString(): String = key

    companion object {
        fun of(date: LocalDate): IsoWeek {
            // The week-year is the year of this week's Thursday.
            val thursday = date.plus(DatePeriod(days = DayOfWeek.THURSDAY.isoDayNumber - date.dayOfWeek.isoDayNumber))
            val weekYear = thursday.year
            val week = (thursday.dayOfYear - 1) / 7 + 1
            return IsoWeek(weekYear, week)
        }

        fun parse(key: String): IsoWeek? {
            val match = Regex("""^(\d{4})-W(\d{1,2})$""").matchEntire(key.trim()) ?: return null
            val (year, week) = match.destructured
            return runCatching { IsoWeek(year.toInt(), week.toInt()) }.getOrNull()
        }

        fun weeksInYear(weekYear: Int): Int {
            // A year has 53 ISO weeks if Jan 1 is a Thursday, or a Wednesday in a leap year.
            val jan1 = LocalDate(weekYear, 1, 1).dayOfWeek
            val leap = isLeapYear(weekYear)
            return if (jan1 == DayOfWeek.THURSDAY || (leap && jan1 == DayOfWeek.WEDNESDAY)) 53 else 52
        }

        private fun mondayOfWeekOne(weekYear: Int): LocalDate {
            val jan4 = LocalDate(weekYear, 1, 4)
            return jan4.minus(DatePeriod(days = jan4.dayOfWeek.isoDayNumber - 1))
        }
    }
}

fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

val LocalDate.isoWeek: IsoWeek get() = IsoWeek.of(this)

/** Monday of the ISO week containing this date. */
fun LocalDate.startOfWeek(): LocalDate = minus(DatePeriod(days = dayOfWeek.isoDayNumber - 1))

fun LocalDate.plusDays(days: Int): LocalDate = plus(DatePeriod(days = days))

fun LocalDate.minusDays(days: Int): LocalDate = minus(DatePeriod(days = days))

val LocalDate.isWeekend: Boolean get() = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY

/** Inclusive range of dates, safe to iterate. */
fun LocalDate.datesUntil(endInclusive: LocalDate): List<LocalDate> {
    if (endInclusive < this) return emptyList()
    val result = ArrayList<LocalDate>()
    var d = this
    while (d <= endInclusive) {
        result += d
        d = d.plusDays(1)
    }
    return result
}
