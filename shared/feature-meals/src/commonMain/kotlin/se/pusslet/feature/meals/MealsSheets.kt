package se.pusslet.feature.meals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.FavoriteWeek
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.ListRow
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.components.SmallAction
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.weekdayName
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/**
 * The read view of a planned meal (UX plan, Mat 2): the day, the dish, what is special about it, the school lunch (whole
 * menu) and a clash said once. "Visa recept" when the meal has a recipe and, for parents, "Ändra", which opens the sheet.
 */
@Composable
internal fun DayViewSheet(
    state: DayViewState,
    showSlot: Boolean,
    onDismiss: () -> Unit,
    onOpenRecipe: (String) -> Unit,
    onEdit: () -> Unit,
) {
    MealSheet(onDismiss) {
        val day = formatDateLong(state.date).replaceFirstChar { it.uppercase() }
        Text(
            if (showSlot) stringResource(Res.string.meals_sheet_title, stringResource(Labels.mealSlot(state.slot)), day) else day,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MealTitle(state.entry, state.title, MaterialTheme.typography.titleLarge, Modifier.semantics(mergeDescendants = true) { heading() })
        KindLine(state.entry)
        state.cook?.let { NoteLine(DayNote(cookText(it, state.cookIsMe))) }
        mealDetails(state.entry)?.let { NoteLine(DayNote(it)) }
        lunchLine(groupLunches(state.lunches, wholeMenu = true), wholeMenu = true)?.let { NoteLine(DayNote(it)) }
        val clashing = state.lunches.filter { it.clash.isNotEmpty() }
        if (clashing.isNotEmpty()) {
            NoteLine(DayNote(stringResource(Res.string.meals_clash, clashing.joinToString(", ") { it.child.firstName }), clash = true))
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            val recipe = state.recipe
            if (recipe != null) {
                FilledTonalButton(onClick = { onOpenRecipe(recipe.id) }) { Text(stringResource(Res.string.meals_show_recipe)) }
            }
            if (state.canEdit) {
                if (recipe != null) {
                    OutlinedButton(onClick = onEdit) { Text(stringResource(Res.string.action_change)) }
                } else {
                    FilledTonalButton(onClick = onEdit) { Text(stringResource(Res.string.action_change)) }
                }
            }
        }
    }
}

/**
 * "Fyll i skollunch" for the whole week (UX plan, Mat 3): one field per school day, for the children whose lunch is typed in.
 * With several such children, chips choose who the lunches are for (all of them at first). Only the days typed in are saved.
 */
@Composable
internal fun LunchWeekSheet(
    state: MealsUiState,
    onDismiss: () -> Unit,
    onSave: (childIds: List<String>, dishes: Map<LocalDate, String>) -> Unit,
) {
    val children = state.manualLunchChildren
    var selected by remember { mutableStateOf(children.map { it.id }.toSet()) }
    val typed = remember { mutableStateMapOf<LocalDate, String>() }
    val days = state.days.filter { day -> selected.any { it !in day.noSchool } }

    fun initial(day: DayPlan) = day.lunches.firstOrNull { it.child.id in selected }?.dishes?.joinToString("\n").orEmpty()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                state.week?.let { week ->
                    SheetTitle(stringResource(Res.string.meals_lunch_week_title, week.week))
                }
                if (children.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        children.forEach { child ->
                            FilterChip(
                                selected = child.id in selected,
                                onClick = { selected = if (child.id in selected) selected - child.id else selected + child.id },
                                label = { Text(child.firstName) },
                                colors = SelectionColors.filterChip(),
                            )
                        }
                    }
                }
                days.forEach { day ->
                    OutlinedTextField(
                        value = typed[day.date] ?: initial(day),
                        onValueChange = { typed[day.date] = it },
                        label = { Text(weekdayName(day.date.dayOfWeek).replaceFirstChar { it.uppercase() }) },
                        placeholder = { Text(stringResource(Res.string.meals_lunch_hint)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            SheetActions(
                canSave = selected.isNotEmpty(),
                onCancel = onDismiss,
                onSave = {
                    // Only the days typed in: an untouched day keeps each child's own lunch.
                    val shown = days.map { it.date }.toSet()
                    onSave(children.map { it.id }.filter { it in selected }, typed.filterKeys { it in shown })
                },
            )
        }
    }
}

/**
 * "Näring för veckan": averaged per portion over the week's planned recipes, neutral and with its source. The first time it
 * downloads Livsmedelsverket's food list, so it is only calculated when this opens.
 */
@Composable
internal fun WeekNutritionSheet(
    state: WeekNutritionState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    balance: @Composable ColumnScope.() -> Unit = {},
) {
    MealSheet(onDismiss) {
        SheetTitle(stringResource(Res.string.week_nutrition_title))
        // Fish, vegetarian and meat with the school lunch first: it needs no download and is what the week can act on.
        balance()
        when (state) {
            WeekNutritionState.Idle, WeekNutritionState.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                CircularProgressIndicator(Modifier.padding(Spacing.xs))
                Text(stringResource(Res.string.nutrition_loading), style = MaterialTheme.typography.bodyMedium)
            }
            WeekNutritionState.NoRecipes -> BodyText(stringResource(Res.string.week_nutrition_no_recipes))
            WeekNutritionState.Unavailable -> {
                BodyText(stringResource(Res.string.nutrition_unavailable))
                TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
            }
            is WeekNutritionState.Ready -> {
                BodyText(stringResource(Res.string.week_nutrition_average))
                NutrientRows(state.week.averagePerPortion)
                QuietText(stringResource(Res.string.week_nutrition_coverage, state.week.estimated, state.week.meals))
            }
        }
        QuietText(stringResource(Res.string.nutrition_source))
    }
}

/** What the round + on Recept opens: a recipe from a link, typed in, or pasted text split into the fields. */
@Composable
internal fun NewRecipeSheet(onDismiss: () -> Unit, onImport: () -> Unit, onTypeIn: () -> Unit, onPaste: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = Spacing.xl).navigationBarsPadding()) {
            Text(
                stringResource(Res.string.recipe_new),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm).semantics { heading() },
            )
            val choices = listOf(
                Res.string.meals_recipe_from_link to onImport,
                Res.string.meals_recipe_type_in to onTypeIn,
                Res.string.meals_recipe_paste_text to onPaste,
            )
            choices.forEach { (label, choose) ->
                ListRow(
                    title = stringResource(label),
                    modifier = Modifier
                        .clickable(role = Role.Button) {
                            onDismiss()
                            choose()
                        }
                        .padding(horizontal = Spacing.screenHorizontal),
                )
            }
        }
    }
}

@Composable
internal fun FavoriteNameDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.meals_save_favorite)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.meals_favorite_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/** The favourite weeks: a tap puts one into the shown week (with Ångra on the receipt); "Ta bort" forgets it. */
@Composable
internal fun FavoritesDialog(
    state: MealsUiState,
    onDismiss: () -> Unit,
    onApply: (FavoriteWeek) -> Unit,
    onDelete: (FavoriteWeek) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.meals_favorites)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(Res.string.meals_favorites_hint), style = MaterialTheme.typography.bodyMedium)
                state.favorites.forEach { fav ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable(role = Role.Button) { onApply(fav) }
                                .padding(vertical = Spacing.sm),
                        ) {
                            Text(fav.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                fav.days.entries.sortedBy { it.key }.joinToString(", ") { it.value.title },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        SmallAction(stringResource(Res.string.action_delete)) { onDelete(fav) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) } },
    )
}

/** A sheet whose content scrolls and that has no fixed buttons. */
@Composable
internal fun MealSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            content = content,
        )
    }
}

@Composable
internal fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

/** "Avbryt" and "Spara", fixed under a sheet's scrolling content so they are never below the fold. */
@Composable
internal fun SheetActions(canSave: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.action_cancel)) }
        Button(onClick = onSave, enabled = canSave, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.action_save)) }
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun QuietText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
