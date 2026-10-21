package se.pusslet.feature.tasks

import androidx.compose.runtime.Composable
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.time.plusDays
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.weekdayShort
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.tasks_week_last
import se.pusslet.ui.resources.tasks_week_next
import se.pusslet.ui.resources.tasks_week_number
import se.pusslet.ui.resources.tasks_week_this
import se.pusslet.ui.resources.today
import se.pusslet.ui.resources.tomorrow
import se.pusslet.ui.resources.yesterday

/** "idag", "imorgon", "fre", "2 nov", with the time when there is one ("fre 18:00"). Lower case: it follows "senast". */
@Composable
internal fun dayLabel(date: LocalDate, today: LocalDate, time: LocalTime?): String {
    val days = date.toEpochDays() - today.toEpochDays()
    val day = when {
        date == today -> stringResource(Res.string.today).lowercase()
        date == today.plusDays(1) -> stringResource(Res.string.tomorrow).lowercase()
        date == today.plusDays(-1) -> stringResource(Res.string.yesterday).lowercase()
        days in -WEEK_DAYS..WEEK_DAYS -> weekdayShort(date.dayOfWeek)
        else -> formatDateShort(date)
    }
    return if (time == null) day else "$day ${formatTime(time)}"
}

/** "den här veckan", "nästa vecka", "förra veckan", else "vecka 46". */
@Composable
internal fun weekPhrase(offset: Int, week: Int?): String = when (offset) {
    0 -> stringResource(Res.string.tasks_week_this)
    1 -> stringResource(Res.string.tasks_week_next)
    -1 -> stringResource(Res.string.tasks_week_last)
    else -> stringResource(Res.string.tasks_week_number, week ?: 0)
}

private const val WEEK_DAYS = 6L
