package se.pusslet.ui.format

import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import se.pusslet.core.units.Quantity
import se.pusslet.core.units.Units
import se.pusslet.ui.components.Labels
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.and_separator
import se.pusslet.ui.resources.date_long
import se.pusslet.ui.resources.decimal_separator
import se.pusslet.ui.resources.list_separator
import se.pusslet.ui.resources.months
import se.pusslet.ui.resources.weekdays

// The same texts as the composable formatters, for notifications and widgets that are built outside a composition.

/** "Sara och Johan", "Ella, Simon och Vera". */
suspend fun joinNamesText(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names.first()
    else -> names.dropLast(1).joinToString(getString(Res.string.list_separator)) + getString(Res.string.and_separator) + names.last()
}

/** "Tisdag 13 oktober" (capitalised: it starts a line). */
suspend fun dateLongText(date: LocalDate): String {
    val weekday = getStringArray(Res.array.weekdays)[date.dayOfWeek.isoDayNumber - 1]
    val month = getStringArray(Res.array.months)[date.month.ordinal]
    return getString(Res.string.date_long, weekday, date.day, month).replaceFirstChar { it.uppercase() }
}

/** "2 dl", "1 ½ msk". */
suspend fun quantityText(q: Quantity): String {
    val sep = getString(Res.string.decimal_separator)
    val number = Units.formatNumber(q.value).replace(",", sep) + (q.max?.let { "–" + Units.formatNumber(it).replace(",", sep) } ?: "")
    val unit = q.unit ?: return number
    return "$number ${getString(Labels.unit(unit))}"
}
