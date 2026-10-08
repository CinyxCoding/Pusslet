package se.pusslet.feature.meals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.meals.MealSuggestion
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Recipe
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.Stepper
import se.pusslet.ui.components.SwitchRow
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.weekdayName
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

@Composable
internal fun MealDaySheet(
    sheet: DaySheet,
    canEdit: Boolean,
    weekDays: List<LocalDate>,
    onDismiss: () -> Unit,
    onPlanRecipe: (Recipe) -> Unit,
    onPlanText: (String) -> Unit,
    onServings: (Int) -> Unit,
    onCookDouble: (Boolean) -> Unit,
    onClear: () -> Unit,
    onMove: (LocalDate) -> Unit,
    onSetLunch: (String, String) -> Unit,
    onOpenRecipe: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                stringResource(Res.string.meals_day_title, stringResource(Labels.mealSlot(sheet.slot)), formatDateLong(sheet.date)),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            if (sheet.busy.isNotEmpty()) {
                Text(busyText(sheet.busy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (sheet.meal.entry != null) {
                CurrentMeal(sheet, canEdit, weekDays, onServings, onCookDouble, onClear, onMove, onOpenRecipe)
            }
            if (canEdit) {
                WhatField(onPlanText)
                Suggestions(sheet, onPlanRecipe)
                RecipeSearch(sheet, onPlanRecipe)
            }
            if (sheet.lunches.isNotEmpty()) LunchSection(sheet, canEdit, onSetLunch)
        }
    }
}

@Composable
private fun CurrentMeal(
    sheet: DaySheet,
    canEdit: Boolean,
    weekDays: List<LocalDate>,
    onServings: (Int) -> Unit,
    onCookDouble: (Boolean) -> Unit,
    onClear: () -> Unit,
    onMove: (LocalDate) -> Unit,
    onOpenRecipe: (String) -> Unit,
) {
    val entry = sheet.meal.entry ?: return
    SectionCard(null) {
        Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ClashLines(sheet.meal.clashes)
        if (sheet.meal.conflicts.isNotEmpty()) WarningLine(conflictText(sheet.meal.conflicts))
        sheet.meal.recipe?.let { r ->
            TextButton(onClick = { onOpenRecipe(r.id) }) { Text(stringResource(Res.string.meals_open_recipe)) }
        }
        if (!canEdit) return@SectionCard
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.meals_servings), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Stepper(
                value = entry.servings ?: sheet.defaultServings,
                onValueChange = onServings,
                decreaseLabel = stringResource(Res.string.meals_servings_decrease),
                increaseLabel = stringResource(Res.string.meals_servings_increase),
            )
        }
        if (sheet.slot == MealSlot.Dinner && entry.leftoversFrom == null) {
            SwitchRow(
                stringResource(Res.string.meals_cook_double),
                entry.cookDouble,
                onCookDouble,
                supporting = stringResource(Res.string.meals_cook_double_body),
            )
        }
        var moveMenu by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { moveMenu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)) {
                Text(stringResource(Res.string.meals_move))
            }
            DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                weekDays.filter { it != sheet.date }.forEach { d ->
                    DropdownMenuItem(text = { Text(weekdayName(d.dayOfWeek).replaceFirstChar { it.uppercase() }) }, onClick = {
                        moveMenu = false
                        onMove(d)
                    })
                }
            }
        }
        TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.meals_remove)) }
    }
}

@Composable
private fun WhatField(onPlanText: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onPlanText(text)
            text = ""
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(Res.string.meals_what)) },
            placeholder = { Text(stringResource(Res.string.meals_what_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = submit, enabled = text.isNotBlank(), modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
            Text(stringResource(Res.string.action_save))
        }
    }
}

@Composable
private fun Suggestions(sheet: DaySheet, onPlanRecipe: (Recipe) -> Unit) {
    SectionCard(stringResource(Res.string.meals_suggestions)) {
        if (sheet.suggestions.isEmpty()) {
            Text(stringResource(Res.string.meals_suggestions_empty), style = MaterialTheme.typography.bodyMedium)
        }
        sheet.suggestions.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider()
            SuggestionRow(s, onClick = { onPlanRecipe(s.recipe) })
        }
    }
}

@Composable
private fun SuggestionRow(s: MealSuggestion, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).clickable(onClick = onClick).padding(vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.recipe.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            s.recipe.totalMinutes?.let {
                Text(
                    stringResource(Res.string.recipe_minutes, it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Why it is suggested, in plain words – suggestions are never a black box.
        if (s.reasons.isNotEmpty()) {
            Text(
                s.reasons.map { stringResource(Labels.reason(it)) }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun RecipeSearch(sheet: DaySheet, onPlanRecipe: (Recipe) -> Unit) {
    if (sheet.recipes.isEmpty()) return
    var query by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(stringResource(Res.string.meals_search_recipes)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (query.isNotBlank()) {
        val q = query.trim().lowercase()
        val matches = sheet.recipes.filter { q in it.title.lowercase() }.take(MAX_SEARCH_RESULTS)
        if (matches.isEmpty()) {
            Text(stringResource(Res.string.recipes_no_match), style = MaterialTheme.typography.bodyMedium)
        }
        matches.forEach { r ->
            Column(
                Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).clickable {
                    onPlanRecipe(r)
                }.padding(vertical = Spacing.sm),
            ) {
                Text(r.title, style = MaterialTheme.typography.bodyLarge)
                sheet.conflicts[r.id]?.let { WarningLine(conflictText(it)) }
            }
        }
    }
}

@Composable
private fun LunchSection(sheet: DaySheet, canEdit: Boolean, onSetLunch: (String, String) -> Unit) {
    SectionCard(stringResource(Res.string.meals_school_lunch)) {
        sheet.lunches.forEach { l ->
            if (l.manual && canEdit) {
                var text by rememberSaveable(l.child.id, sheet.date.toString()) { mutableStateOf(l.dishes.joinToString("\n")) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(l.child.name) },
                    placeholder = { Text(stringResource(Res.string.meals_lunch_edit_hint)) },
                    minLines = 1,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = if (text.trim() != l.dishes.joinToString("\n")) {
                        { TextButton(onClick = { onSetLunch(l.child.id, text) }) { Text(stringResource(Res.string.action_save)) } }
                    } else {
                        null
                    },
                )
            } else {
                Column {
                    Text(l.child.name, style = MaterialTheme.typography.labelLarge)
                    Text(
                        l.dishes.joinToString("\n").ifEmpty { stringResource(Res.string.meals_lunch_missing) },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private const val MAX_SEARCH_RESULTS = 8
