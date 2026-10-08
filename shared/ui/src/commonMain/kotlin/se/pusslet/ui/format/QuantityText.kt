package se.pusslet.ui.format

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.units.Quantity
import se.pusslet.core.units.Units
import se.pusslet.ui.components.Labels
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.decimal_separator
import kotlin.math.abs
import kotlin.math.roundToLong

/** "2 dl", "1 ½ msk", "2–3 st" – with the unit in the user's language ("2 tbsp") and its decimal separator. */
@Composable
fun formatQuantity(q: Quantity): String {
    val number = formatNumber(q.value) + (q.max?.let { "–" + formatNumber(it) } ?: "")
    val unit = q.unit ?: return number
    return "$number ${stringResource(Labels.unit(unit))}"
}

@Composable
fun formatNumber(value: Double): String = Units.formatNumber(value).replace(",", stringResource(Res.string.decimal_separator))

/** Prices: "24,95" or "30" – two decimals only when needed. */
@Composable
fun formatPrice(value: Double): String {
    val cents = (value * 100).roundToLong()
    val text = if (cents % 100 == 0L) {
        (cents / 100).toString()
    } else {
        val whole = cents / 100
        val rest = abs(cents % 100).toString().padStart(2, '0')
        "$whole,$rest"
    }
    return text.replace(",", stringResource(Res.string.decimal_separator))
}
