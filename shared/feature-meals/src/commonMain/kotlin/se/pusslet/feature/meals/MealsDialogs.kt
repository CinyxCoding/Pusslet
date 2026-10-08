package se.pusslet.feature.meals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.FavoriteWeek
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.nutrition.NutrientValues
import se.pusslet.ui.components.ListRow
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.format.formatNumber
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import kotlin.math.roundToInt

@Composable
internal fun SaveFavoriteDialog(defaultName: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.meals_save_favorite)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.meals_favorite_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Composable
internal fun ApplyFavoriteDialog(
    favorites: List<FavoriteWeek>,
    onApply: (FavoriteWeek) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.meals_apply_favorite)) },
        text = {
            Column {
                if (favorites.isEmpty()) Text(stringResource(Res.string.meals_no_favorites))
                favorites.forEach { f ->
                    ListRow(
                        title = f.name,
                        supporting = stringResource(Res.string.meals_favorite_days, f.days.size),
                        modifier = Modifier.clickable { onApply(f) },
                        trailing = {
                            IconButton(onClick = { onDelete(f.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.meals_delete_favorite, f.name))
                            }
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) } },
    )
}

@Composable
internal fun ShoppingPreviewDialog(
    preview: ShoppingPreview,
    lists: List<ShoppingList>,
    onPickList: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val plan = preview.plan
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.meals_shop_title, preview.week.week)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (lists.size > 1) {
                    Text(stringResource(Res.string.meals_shop_list), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        lists.forEach { l ->
                            FilterChip(selected = l.id == preview.list.id, onClick = { onPickList(l.id) }, label = { Text(l.name) })
                        }
                    }
                }
                when {
                    !preview.hasRecipes -> Text(stringResource(Res.string.meals_shop_no_recipes))
                    plan.isEmpty -> Text(stringResource(Res.string.meals_shop_nothing))
                    else -> {
                        Text(
                            stringResource(Res.string.meals_shop_summary, plan.add.size, plan.update.size, plan.remove.size),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        val names = (plan.add + plan.update).map { it.name }.distinct().sortedBy { it.lowercase() }
                        Text(names.take(PREVIEW_NAMES).joinToString(", ") + if (names.size > PREVIEW_NAMES) " …" else "")
                        if (preview.pantryUsed) {
                            Text(
                                stringResource(Res.string.meals_shop_pantry_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !plan.isEmpty) { Text(stringResource(Res.string.meals_shop_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Composable
internal fun WeekNutritionCard(state: MealsUiState, onLoad: () -> Unit) {
    val n = state.nutrition
    SectionCard(stringResource(Res.string.meals_nutrition_week)) {
        when {
            state.nutritionLoading -> CircularProgressIndicator(Modifier.padding(Spacing.sm))
            n == null -> OutlinedButton(onClick = onLoad, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
                Text(stringResource(Res.string.nutrition_show))
            }
            else -> {
                if (n.withRecipe > 0) NutritionTable(n.perPortion)
                Text(
                    stringResource(Res.string.meals_nutrition_week_count, n.withRecipe, n.dinners),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NutritionSource()
            }
        }
    }
}

/** Neutral, informative numbers – no targets, no judgement. */
@Composable
internal fun NutritionTable(v: NutrientValues) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        v.energyKcal?.let {
            NutrientRow(stringResource(Res.string.nutrition_energy), stringResource(Res.string.nutrition_kcal, it.roundToInt().toString()))
        }
        v.protein?.let { NutrientRow(stringResource(Res.string.nutrition_protein), grams(it)) }
        v.fat?.let { NutrientRow(stringResource(Res.string.nutrition_fat), grams(it)) }
        v.carbohydrates?.let { NutrientRow(stringResource(Res.string.nutrition_carbs), grams(it)) }
        v.fiber?.let { NutrientRow(stringResource(Res.string.nutrition_fiber), grams(it)) }
        v.salt?.let { NutrientRow(stringResource(Res.string.nutrition_salt), grams(it)) }
    }
}

@Composable
private fun grams(value: Double): String = stringResource(Res.string.nutrition_grams, formatNumber(value))

@Composable
private fun NutrientRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun NutritionSource() {
    Text(
        stringResource(Res.string.nutrition_source),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val PREVIEW_NAMES = 12
