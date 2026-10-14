package se.pusslet.feature.meals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.meals.MealSuggestion
import se.pusslet.core.meals.SuggestionReason
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Recipe
import se.pusslet.ui.components.InfoPill
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.SwitchRow
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.weekdayName
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/**
 * Planning one meal: suggestions with their reasons ("snabbt – fotboll 17:30", "inte samma som skollunchen"),
 * the recipe library filtered as you type, or just free text ("tacos"). "Gör dubbelt" plans leftovers automatically.
 */
@Composable
internal fun DayPlanSheet(
    state: DayEditorState,
    canEdit: Boolean,
    onDismiss: () -> Unit,
    onSave: (title: String, recipeId: String?, cookDouble: Boolean, servings: Int?, note: String) -> Unit,
    onClear: () -> Unit,
    onMove: (LocalDate) -> Unit,
    onOpenRecipe: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val key = "${state.date}-${state.slot}"
    val entry = state.entry
    var title by rememberSaveable(key) { mutableStateOf(entry?.title.orEmpty()) }
    var recipeId by rememberSaveable(key) { mutableStateOf(entry?.recipeId) }
    var cookDouble by rememberSaveable(key) { mutableStateOf(entry?.cookDouble ?: false) }
    var note by rememberSaveable(key) { mutableStateOf(entry?.note.orEmpty()) }
    val selected = recipeId?.let { id -> state.recipes.firstOrNull { it.id == id } }

    fun pick(recipe: Recipe) {
        recipeId = recipe.id
        title = recipe.title
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                stringResource(Res.string.meals_sheet_title, stringResource(Labels.mealSlot(state.slot)), formatDateLong(state.date)),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            DayContext(state)
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    // Typing something else than the chosen recipe makes it free text again.
                    if (selected != null && it != selected.title) recipeId = null
                },
                label = { Text(stringResource(Res.string.meals_what_hint)) },
                singleLine = true,
                enabled = canEdit,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = if (selected != null) {
                    { Icon(Icons.Filled.Check, contentDescription = stringResource(Res.string.meals_recipe_chosen)) }
                } else {
                    null
                },
            )
            if (selected != null) {
                TextButton(onClick = { onOpenRecipe(selected.id) }) { Text(stringResource(Res.string.meals_open_recipe)) }
            }
            if (canEdit) {
                val query = title.trim().lowercase()
                if (query.isEmpty() || selected != null) {
                    Suggestions(state.suggestions, chosen = recipeId, onPick = ::pick)
                } else {
                    Matches(state.recipes.filter { query in it.title.lowercase() }.take(MAX_MATCHES), onPick = ::pick)
                }
                if (state.slot == MealSlot.Dinner) {
                    SwitchRow(
                        title = stringResource(Res.string.meals_cook_double),
                        supporting = stringResource(Res.string.meals_cook_double_hint),
                        checked = cookDouble,
                        onCheckedChange = { cookDouble = it },
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(Res.string.meals_note)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Actions(
                    state = state,
                    canSave = title.isNotBlank(),
                    onSave = { onSave(title, recipeId, cookDouble, entry?.servings, note) },
                    onCancel = onDismiss,
                    onClear = onClear,
                    onMove = onMove,
                )
            }
        }
    }
}

@Composable
private fun DayContext(state: DayEditorState) {
    state.busy.firstOrNull()?.let { busy ->
        Text(
            stringResource(Res.string.meals_busy_evening, busy.event.title, formatTime(busy.start.time)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    groupLunches(state.lunches, wholeMenu = true).forEach { lunch ->
        Text(
            stringResource(Res.string.today_lunch, lunch.children.joinToString(", ") { it.firstName }, lunch.dishes.joinToString(", ")),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Suggestions(suggestions: List<MealSuggestion>, chosen: String?, onPick: (Recipe) -> Unit) {
    if (suggestions.isEmpty()) {
        Text(
            stringResource(Res.string.meals_no_suggestions),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text(
        stringResource(Res.string.meals_suggestions),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() },
    )
    suggestions.forEach { s ->
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchTarget)
                .clickable(role = Role.Button) { onPick(s.recipe) }
                .padding(vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (s.recipe.favorite) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Text(s.recipe.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (s.recipe.id == chosen) Icon(Icons.Filled.Check, contentDescription = stringResource(Res.string.meals_recipe_chosen))
            }
            if (s.reasons.isNotEmpty()) {
                // At most two reasons, on one line (they inform; the whole row picks).
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    s.reasons.sortedBy { REASON_PRIORITY.indexOf(it) }.take(MAX_REASONS).forEach { reason ->
                        InfoPill(stringResource(reasonLabel(reason)))
                    }
                }
            }
        }
    }
}

@Composable
private fun Matches(recipes: List<Recipe>, onPick: (Recipe) -> Unit) {
    if (recipes.isEmpty()) {
        Text(
            stringResource(Res.string.meals_free_text_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    recipes.forEach { r ->
        Text(
            r.title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchTarget)
                .clickable(role = Role.Button) { onPick(r) }
                .padding(vertical = Spacing.sm),
        )
    }
}

@Composable
private fun Actions(
    state: DayEditorState,
    canSave: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onMove: (LocalDate) -> Unit,
) {
    if (state.entry != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MoveButton(state, onMove)
            TextButton(onClick = onClear) { Text(stringResource(Res.string.action_delete)) }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.action_cancel)) }
        Button(onClick = onSave, enabled = canSave, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.action_save)) }
    }
}

@Composable
private fun MoveButton(state: DayEditorState, onMove: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(Res.string.meals_move)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.weekDays.filter { it != state.date }.forEach { day ->
                DropdownMenuItem(
                    text = { Text(weekdayName(day.dayOfWeek).replaceFirstChar { it.uppercase() }) },
                    onClick = {
                        open = false
                        onMove(day)
                    },
                )
            }
        }
    }
}

internal fun reasonLabel(reason: SuggestionReason): StringResource = when (reason) {
    SuggestionReason.Favorite -> Res.string.reason_favorite
    SuggestionReason.NotRecently -> Res.string.reason_not_recently
    SuggestionReason.QuickForBusyEvening -> Res.string.reason_quick
    SuggestionReason.UsesWhatYouHave -> Res.string.reason_uses_pantry
    SuggestionReason.VariesTheWeek -> Res.string.reason_varies
    SuggestionReason.DiffersFromSchoolLunch -> Res.string.reason_not_lunch
}

private const val MAX_MATCHES = 8
private const val MAX_REASONS = 2

/** The reasons about this very day first; "Favorit" last, since the star already says it. */
private val REASON_PRIORITY = listOf(
    SuggestionReason.QuickForBusyEvening,
    SuggestionReason.DiffersFromSchoolLunch,
    SuggestionReason.UsesWhatYouHave,
    SuggestionReason.VariesTheWeek,
    SuggestionReason.NotRecently,
    SuggestionReason.Favorite,
)
