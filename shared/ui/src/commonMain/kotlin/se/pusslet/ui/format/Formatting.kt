package se.pusslet.ui.format

import androidx.compose.runtime.Composable
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.and_separator
import se.pusslet.ui.resources.date_long
import se.pusslet.ui.resources.date_long_year
import se.pusslet.ui.resources.date_short
import se.pusslet.ui.resources.list_separator
import se.pusslet.ui.resources.months
import se.pusslet.ui.resources.months_short
import se.pusslet.ui.resources.time_range
import se.pusslet.ui.resources.today
import se.pusslet.ui.resources.tomorrow
import se.pusslet.ui.resources.week_long
import se.pusslet.ui.resources.week_short
import se.pusslet.ui.resources.weekdays
import se.pusslet.ui.resources.weekdays_short
import se.pusslet.ui.resources.yesterday

/** 24-hour clock, always ("Svensk vardag på riktigt"). */
fun formatTime(t: LocalTime): String = "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"

@Composable
fun weekdayName(day: DayOfWeek): String = stringArrayResource(Res.array.weekdays)[day.isoDayNumber - 1]

@Composable
fun weekdayShort(day: DayOfWeek): String = stringArrayResource(Res.array.weekdays_short)[day.isoDayNumber - 1]

@Composable
fun monthName(month: Int): String = stringArrayResource(Res.array.months)[month - 1]

@Composable
fun monthShort(month: Int): String = stringArrayResource(Res.array.months_short)[month - 1]

/** "tisdag 13 oktober" (adds the year when it differs from [referenceYear]). */
@Composable
fun formatDateLong(date: LocalDate, referenceYear: Int? = null): String {
    val wd = weekdayName(date.dayOfWeek)
    val m = monthName(date.month.ordinal + 1)
    return if (referenceYear != null && referenceYear != date.year) {
        stringResource(Res.string.date_long_year, wd, date.day, m, date.year)
    } else {
        stringResource(Res.string.date_long, wd, date.day, m)
    }
}

/** "13 okt" */
@Composable
fun formatDateShort(date: LocalDate): String = stringResource(Res.string.date_short, date.day, monthShort(date.month.ordinal + 1))

@Composable
fun formatTimeRange(start: LocalTime, end: LocalTime): String = stringResource(Res.string.time_range, formatTime(start), formatTime(end))

@Composable
fun weekShort(date: LocalDate): String = stringResource(Res.string.week_short, date.isoWeek.week)

@Composable
fun weekLong(week: Int): String = stringResource(Res.string.week_long, week)

/** "Idag", "Imorgon", "Igår", else the weekday (within a week) or a short date. */
@Composable
fun relativeDay(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(Res.string.today)
    today.plusDays(1) -> stringResource(Res.string.tomorrow)
    today.plusDays(-1) -> stringResource(Res.string.yesterday)
    else -> {
        val days = (date.toEpochDays() - today.toEpochDays())
        if (days in 2..6) weekdayName(date.dayOfWeek) else formatDateShort(date)
    }
}

/** "Sara och Johan", "Ella, Simon och Vera". */
@Composable
fun joinNames(names: List<String>): String {
    val sep = stringResource(Res.string.list_separator)
    val and = stringResource(Res.string.and_separator)
    return when (names.size) {
        0 -> ""
        1 -> names.first()
        else -> names.dropLast(1).joinToString(sep) + and + names.last()
    }
}
