package se.pusslet.ui.format

import androidx.compose.runtime.Composable
import kotlinx.datetime.DayOfWeek
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.recurrence.Frequency
import se.pusslet.core.recurrence.RRule
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.repeat_biweekly
import se.pusslet.ui.resources.repeat_biweekly_on
import se.pusslet.ui.resources.repeat_custom
import se.pusslet.ui.resources.repeat_daily
import se.pusslet.ui.resources.repeat_every_n_weeks
import se.pusslet.ui.resources.repeat_monthly
import se.pusslet.ui.resources.repeat_none
import se.pusslet.ui.resources.repeat_weekdays
import se.pusslet.ui.resources.repeat_weekly
import se.pusslet.ui.resources.repeat_weekly_on
import se.pusslet.ui.resources.repeat_yearly

private val workdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

/** "Varje tisdag", "Varannan fredag", "Vardagar" … */
@Composable
fun describe(rule: RRule?): String {
    if (rule == null) return stringResource(Res.string.repeat_none)
    val days = rule.byDay.filter { it.ordinal == null }.map { it.day }
    return when (rule.freq) {
        Frequency.DAILY -> stringResource(Res.string.repeat_daily)
        Frequency.MONTHLY -> stringResource(Res.string.repeat_monthly)
        Frequency.YEARLY -> stringResource(Res.string.repeat_yearly)
        Frequency.WEEKLY -> when {
            rule.interval == 1 && days.toSet() == workdays -> stringResource(Res.string.repeat_weekdays)
            rule.interval == 1 && days.isNotEmpty() -> stringResource(Res.string.repeat_weekly_on, joinNames(days.map { weekdayName(it) }))
            rule.interval == 1 -> stringResource(Res.string.repeat_weekly)
            rule.interval == 2 && days.isNotEmpty() -> stringResource(
                Res.string.repeat_biweekly_on,
                joinNames(
                    days.map {
                        weekdayName(it)
                    },
                ),
            )
            rule.interval == 2 -> stringResource(Res.string.repeat_biweekly)
            else -> stringResource(Res.string.repeat_every_n_weeks, rule.interval)
        }
    }.ifBlank { stringResource(Res.string.repeat_custom) }
}

@Composable
fun describe(rrule: String?): String = describe(rrule?.let { RRule.parse(it) })
