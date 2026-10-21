package se.pusslet.feature.meals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.meals.MealSuggester
import se.pusslet.core.meals.MealSuggestion
import se.pusslet.core.meals.SuggestionReason
import se.pusslet.core.model.MealKind
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Recipe
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.SheetDragHandle
import se.pusslet.ui.components.SwitchRow
import se.pusslet.ui.components.WeekStripPicker
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.weekdayName
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/** What "Spara" in the planning sheet hands over; [kindLabel] ("Hämtmat") is the title when nothing is typed. */
internal data class DayChoice(
    val title: String,
    val recipeId: String?,
    val kind: MealKind,
    val cookId: String?,
    val cookDouble: Boolean,
    val note: String,
    val kindLabel: String,
)

/**
 * Planning one meal (UX plan, Mat 4): the day (changeable with the week strip), "Laga mat | Hämtmat | Ingen matlagning",
 * what is going on that day, then one tap on a suggestion (three at first, "Visa fler" for the rest) or a recipe that
 * matches what is typed plans it and closes the sheet. Free text ("tacos") is saved with "Spara", which never scrolls away.
 * Takeaway and no cooking need no text and get no suggestions. "Gör dubbelt", "Vem lagar", "Anteckning", "Flytta till…"
 * and "Ta bort" wait under "Fler val".
 */
@Composable
internal fun DayPlanSheet(
    state: DayEditorState,
    onDismiss: () -> Unit,
    onSave: (DayChoice) -> Unit,
    onPick: (recipe: Recipe, cookDouble: Boolean, note: String, cookId: String?) -> Unit,
    onClear: () -> Unit,
    onMove: (LocalDate) -> Unit,
    onChangeDay: (LocalDate) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entry = state.entry
    val labels = MealKind.entries.associateWith { k -> kindLabel(k)?.let { stringResource(it) }.orEmpty() }
    // Another empty day keeps what was typed; a planned day shows its own meal.
    val key = "${state.slot}-${entry?.key}"
    var kind by rememberSaveable(key) { mutableStateOf(entry?.kind ?: MealKind.Cooked) }
    // Takeaway saved without a text has the kind as its title ("Hämtmat"): the field starts empty again.
    var title by rememberSaveable(key) {
        mutableStateOf(entry?.let { e -> e.title.takeUnless { !e.kind.cooks && it == labels[e.kind] } }.orEmpty())
    }
    var recipeId by rememberSaveable(key) { mutableStateOf(entry?.recipeId) }
    var cookDouble by rememberSaveable(key) { mutableStateOf(entry?.cookDouble ?: false) }
    var cookId by rememberSaveable(key) { mutableStateOf(entry?.cookId) }
    var note by rememberSaveable(key) { mutableStateOf(entry?.note.orEmpty()) }
    var moreOpen by rememberSaveable(key) { mutableStateOf(false) }
    val cooks = kind.cooks
    val selected = recipeId?.takeIf { cooks }?.let { id -> state.recipes.firstOrNull { it.id == id } }
    val dinner = state.slot == MealSlot.Dinner

    fun pick(recipe: Recipe) = onPick(recipe, cookDouble, note, cookId.takeIf { dinner })

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { SheetDragHandle() }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                SheetTitle(
                    stringResource(Res.string.meals_sheet_title, stringResource(Labels.mealSlot(state.slot)), formatDateLong(state.date)),
                )
                WeekStripPicker(days = state.weekDays, selected = state.date, onSelect = onChangeDay, today = state.today)
                MealKindChoice(kind, onKind = { kind = it })
                DayContext(state, kind)
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        // Typing something else than the chosen recipe makes it free text again.
                        if (selected != null && it != selected.title) recipeId = null
                    },
                    label = { Text(stringResource(fieldLabel(kind))) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = if (selected != null) {
                        { Icon(Icons.Filled.Check, contentDescription = stringResource(Res.string.meals_recipe_chosen)) }
                    } else {
                        null
                    },
                )
                val query = title.trim().lowercase()
                when {
                    !cooks -> Unit
                    query.isEmpty() || selected != null -> Suggestions(state.suggestions, chosen = recipeId, onPick = ::pick)
                    else -> Matches(state.recipes.filter { query in it.title.lowercase() }.take(MAX_MATCHES), onPick = ::pick)
                }
                MoreChoices(
                    open = moreOpen,
                    onToggle = { moreOpen = !moreOpen },
                    state = state,
                    kind = kind,
                    cookDouble = cookDouble,
                    onCookDouble = { cookDouble = it },
                    cookId = cookId,
                    onCook = { cookId = it },
                    note = note,
                    onNote = { note = it },
                    onMove = onMove,
                    onClear = onClear,
                )
            }
            SheetActions(
                canSave = title.isNotBlank() || !cooks,
                onCancel = onDismiss,
                onSave = {
                    onSave(
                        DayChoice(
                            title = title,
                            recipeId = recipeId.takeIf { cooks },
                            kind = kind,
                            cookId = cookId.takeIf { cooks && dinner },
                            cookDouble = cookDouble && cooks && dinner,
                            note = note,
                            kindLabel = labels[kind].orEmpty(),
                        ),
                    )
                },
            )
        }
    }
}

/** What the field asks: "Vad blir det?", "Från var? (valfritt)" or "Var? (valfritt)". */
private fun fieldLabel(kind: MealKind): StringResource = when (kind) {
    MealKind.Cooked -> Res.string.meals_what_hint
    MealKind.Takeaway -> Res.string.meals_takeaway_hint
    MealKind.NoCooking -> Res.string.meals_none_hint
}

/** The busy evening ("Fotboll 17:30 – något snabbt?") only when cooking; the school lunch always. */
@Composable
private fun DayContext(state: DayEditorState, kind: MealKind) {
    state.busy.firstOrNull()?.takeIf { MealSuggester.needsQuickDinner(busy = true, kind = kind) }?.let { busy ->
        Text(
            stringResource(Res.string.meals_busy_evening, busy.event.title, formatTime(busy.start.time)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    lunchLine(groupLunches(state.lunches, wholeMenu = true), wholeMenu = true)?.let { lunch ->
        Text(lunch, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Three suggestions, then "Visa fler". Each says at most two reasons on one line; the whole row plans it. */
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
    var showAll by remember(suggestions.map { it.recipe.id }) { mutableStateOf(false) }
    Text(
        stringResource(Res.string.meals_suggestions),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = Spacing.xs).semantics { heading() },
    )
    Column {
        val shown = if (showAll) suggestions else suggestions.take(FIRST_SUGGESTIONS)
        shown.forEach { s -> SuggestionRow(s, chosen = s.recipe.id == chosen, onPick = { onPick(s.recipe) }) }
        if (!showAll && suggestions.size > FIRST_SUGGESTIONS) {
            TextButton(onClick = { showAll = true }) { Text(stringResource(Res.string.meals_show_more)) }
        }
    }
}

@Composable
private fun SuggestionRow(suggestion: MealSuggestion, chosen: Boolean, onPick: () -> Unit) {
    val reasons = reasonTexts(suggestion)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.primaryTouchTarget)
            .clickable(role = Role.Button, onClick = onPick)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (suggestion.recipe.favorite) {
            Icon(
                Icons.Filled.Star,
                contentDescription = stringResource(Res.string.recipe_favorite),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(suggestion.recipe.title, style = MaterialTheme.typography.bodyLarge)
            if (reasons.isNotEmpty()) {
                Text(
                    reasons.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (chosen) Icon(Icons.Filled.Check, contentDescription = stringResource(Res.string.meals_recipe_chosen))
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

/**
 * "Fler val ⌄": for a cooked dinner "Gör dubbelt" and "Vem lagar", then "Anteckning" and, for a planned meal, "Flytta till…"
 * and "Ta bort". Folded at first.
 */
@Composable
private fun MoreChoices(
    open: Boolean,
    onToggle: () -> Unit,
    state: DayEditorState,
    kind: MealKind,
    cookDouble: Boolean,
    onCookDouble: (Boolean) -> Unit,
    cookId: String?,
    onCook: (String?) -> Unit,
    note: String,
    onNote: (String) -> Unit,
    onMove: (LocalDate) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .clickable(role = Role.Button, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.meals_more_choices),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!open) return
    if (state.slot == MealSlot.Dinner && kind.cooks) {
        SwitchRow(
            title = stringResource(Res.string.meals_cook_double),
            supporting = stringResource(Res.string.meals_cook_double_hint),
            checked = cookDouble,
            onCheckedChange = onCookDouble,
        )
        if (state.adults.isNotEmpty()) CookChooser(state.adults, cookId, onCook)
    }
    OutlinedTextField(
        value = note,
        onValueChange = onNote,
        label = { Text(stringResource(Res.string.meals_note)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.entry != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MoveButton(state, onMove)
            TextButton(onClick = onClear) { Text(stringResource(Res.string.action_delete)) }
        }
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
    SuggestionReason.FishThisWeek -> Res.string.reason_week_fish
    SuggestionReason.VegetarianThisWeek -> Res.string.reason_week_vegetarian
    SuggestionReason.LessRedMeat -> Res.string.reason_less_meat
}

/** A suggestion's two most telling reasons, as text ("Snabb ikväll · Fisk i veckan"); the star already says "Favorit". */
@Composable
internal fun reasonTexts(suggestion: MealSuggestion): List<String> = suggestion.reasons
    .filterNot { it == SuggestionReason.Favorite && suggestion.recipe.favorite }
    .sortedBy { REASON_PRIORITY.indexOf(it) }
    .take(MAX_REASONS)
    .map { stringResource(reasonLabel(it)) }

private const val MAX_MATCHES = 8
private const val MAX_REASONS = 2
private const val FIRST_SUGGESTIONS = 3

/** The reasons about this very day first, then the week's balance; "Favorit" last. */
private val REASON_PRIORITY = listOf(
    SuggestionReason.QuickForBusyEvening,
    SuggestionReason.FishThisWeek,
    SuggestionReason.VegetarianThisWeek,
    SuggestionReason.DiffersFromSchoolLunch,
    SuggestionReason.LessRedMeat,
    SuggestionReason.UsesWhatYouHave,
    SuggestionReason.VariesTheWeek,
    SuggestionReason.NotRecently,
    SuggestionReason.Favorite,
)
