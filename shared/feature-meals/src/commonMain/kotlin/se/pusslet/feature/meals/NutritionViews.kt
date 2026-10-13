package se.pusslet.feature.meals

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.nutrition.NutrientValues
import se.pusslet.ui.resources.*
import kotlin.math.roundToInt

/** Energy, protein, fat, carbohydrates, fibre and salt – the values Livsmedelsverket reports – as plain label/value rows. */
@Composable
internal fun NutrientRows(values: NutrientValues) {
    val rows = listOfNotNull(
        values.energyKcal?.let { Res.string.nutrition_energy to stringResource(Res.string.nutrition_kcal, it.roundToInt()) },
        values.protein?.let { Res.string.nutrition_protein to grams(it) },
        values.fat?.let { Res.string.nutrition_fat to grams(it) },
        values.carbohydrates?.let { Res.string.nutrition_carbs to grams(it) },
        values.fiber?.let { Res.string.nutrition_fiber to grams(it) },
        values.salt?.let { Res.string.nutrition_salt to grams(it) },
    )
    rows.forEach { (label, value) ->
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** "12 g", "0,8 g". */
@Composable
private fun grams(value: Double): String {
    val text = if (value >= GRAMS_WHOLE) value.roundToInt().toString() else ((value * 10).roundToInt() / 10.0).toString().replace('.', ',')
    return stringResource(Res.string.nutrition_grams, text)
}

private const val GRAMS_WHOLE = 10.0
