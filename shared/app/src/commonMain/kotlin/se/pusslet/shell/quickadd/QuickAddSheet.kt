package se.pusslet.shell.quickadd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.MealSlot
import se.pusslet.core.quickadd.ActionKind
import se.pusslet.core.quickadd.EventPrefill
import se.pusslet.core.quickadd.QuickAddAction
import se.pusslet.core.quickadd.QuickAddEdits
import se.pusslet.core.quickadd.UncertainField
import se.pusslet.core.recurrence.Frequency
import se.pusslet.core.recurrence.RRule
import se.pusslet.core.recurrence.WeekdayNum
import se.pusslet.core.units.Units
import se.pusslet.data.usecase.QuickAddOutcome
import se.pusslet.ui.components.DateField
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.PersonChips
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.components.SheetDragHandle
import se.pusslet.ui.components.TimeField
import se.pusslet.ui.components.ValueField
import se.pusslet.ui.format.describe
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/**
 * The quick-add sheet. [onFillIn] is "Fyll i själv": it opens the full event editor with what was typed (UX plan 4.6);
 * without it the button is not shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    viewModel: QuickAddViewModel,
    onDismiss: () -> Unit,
    onSaved: (QuickAddOutcome) -> Unit,
    onFillIn: ((EventPrefill) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { SheetDragHandle() }) {
        QuickAddContent(
            state = state,
            onTextChange = viewModel::onTextChange,
            onToggleEditing = viewModel::toggleEditing,
            onConvert = viewModel::convert,
            onTitle = viewModel::setTitle,
            onDate = viewModel::setDate,
            onStart = viewModel::setStart,
            onEnd = viewModel::setEnd,
            onTogglePerson = viewModel::togglePerson,
            onRemove = viewModel::removeAction,
            onSave = { viewModel.save(onSaved) },
            onClose = onDismiss,
            onFillIn = onFillIn?.let { open -> { open(viewModel.prefill()) } },
        )
    }
}

/**
 * Stateless content of the sheet: the field, the example chips while the field is new (UX plan 4.6), one sentence per
 * parsed action with "Ändra", and "Spara N saker". Guessed parts are terracotta, never `isError`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddContent(
    state: QuickAddUiState,
    onTextChange: (String) -> Unit,
    onToggleEditing: (Int) -> Unit,
    onConvert: (Int, ActionKind) -> Unit,
    onTitle: (Int, String) -> Unit,
    onDate: (Int, LocalDate) -> Unit,
    onStart: (Int, LocalTime?) -> Unit,
    onEnd: (Int, LocalTime) -> Unit,
    onTogglePerson: (Int, String) -> Unit,
    onRemove: (Int) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onFillIn: (() -> Unit)? = null,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.quickadd_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_close)) }
        }
        QuickAddField(state.text, state.hintName, onTextChange, Modifier.focusRequester(focus), onDone = { keyboard?.hide() })
        val today = state.today
        if (state.text.isBlank()) {
            if (state.examples.isNotEmpty()) ExampleChips(state.examples, onTextChange)
        } else if (state.actions.isNotEmpty() && today != null) {
            if (state.uncertain.isNotEmpty()) UncertainLine()
            val words = sentenceWords()
            state.actions.forEachIndexed { index, action ->
                PreviewCard(
                    sentence = QuickAddSentence.build(
                        action = action,
                        uncertain = if (index == 0) state.uncertain else emptySet(),
                        input = state.text,
                        people = state.people,
                        today = today,
                        words = words,
                        repeat = repeatText(action),
                    ),
                    editing = index in state.editing,
                    onToggleEditing = { onToggleEditing(index) },
                ) {
                    ActionFields(
                        action = action,
                        index = index,
                        primary = index == 0,
                        uncertain = if (index == 0) state.uncertain else emptySet(),
                        people = state.people,
                        onConvert = onConvert,
                        onTitle = onTitle,
                        onDate = onDate,
                        onStart = onStart,
                        onEnd = onEnd,
                        onTogglePerson = onTogglePerson,
                        onRemove = if (state.actions.size > 1) ({ onRemove(index) }) else null,
                    )
                }
            }
        }
        Button(
            onClick = onSave,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
        ) {
            Text(
                if (state.actions.size > 1) {
                    stringResource(Res.string.quickadd_save_all, state.actions.size)
                } else {
                    stringResource(Res.string.quickadd_save)
                },
            )
        }
        if (onFillIn != null && state.text.isNotBlank()) {
            TextButton(onClick = onFillIn, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)) {
                Text(stringResource(Res.string.quickadd_fill_in))
            }
        }
    }
}

/**
 * The field keeps its own cursor, so text filled in from outside (a chip, shared text) puts the cursor at the end.
 *
 * [text] reaches the field one collection later than the keystroke. A [text] that is only an older echo of what the field
 * sent itself must not replace what is typed: that threw away the keyboard's composing word (where å, ä and ö are often
 * typed) and moved the cursor to the end mid-sentence.
 */
@Composable
private fun QuickAddField(text: String, hintName: String?, onTextChange: (String) -> Unit, modifier: Modifier, onDone: () -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    // Plain set, not state: the text both sides last agreed on plus everything sent since, i.e. every [text] that can still
    // arrive as an echo. Starts over each time the view model has caught up.
    val echoes = remember { mutableSetOf(text) }
    val agreed = text == field.text || text !in echoes
    if (agreed) {
        echoes.clear()
        echoes += text
    }
    val shown = when {
        text == field.text -> field
        agreed -> TextFieldValue(text, TextRange(text.length))
        else -> field
    }
    OutlinedTextField(
        value = shown,
        onValueChange = { next ->
            field = next
            if (next.text != shown.text) {
                echoes += next.text
                onTextChange(next.text)
            }
        },
        modifier = modifier.fillMaxWidth(),
        placeholder = {
            Text(
                if (hintName != null) {
                    stringResource(Res.string.quickadd_hint, hintName)
                } else {
                    stringResource(Res.string.quickadd_hint_generic)
                },
            )
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        // Done only closes the keyboard; saving is the button's job, after a look at the preview (UX plan 1.8).
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        minLines = 1,
        maxLines = 4,
    )
}

/** At most 3 chips with an icon each, with the family's own names; a tap fills the field (UX plan 4.6). */
@Composable
private fun ExampleChips(examples: List<QuickAddExample>, onPick: (String) -> Unit) {
    Text(
        stringResource(Res.string.quickadd_examples),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        examples.take(QuickAddExamples.MAX).forEach { example ->
            val text = exampleText(example)
            SuggestionChip(
                onClick = { onPick(text) },
                label = { Text(text, maxLines = 1) },
                icon = {
                    Icon(exampleIcon(example.kind), contentDescription = null, modifier = Modifier.size(SuggestionChipDefaults.IconSize))
                },
                modifier = Modifier.heightIn(min = Spacing.touchTarget),
            )
        }
    }
}

@Composable
private fun exampleText(example: QuickAddExample): String {
    val name = example.name
    return when (example.kind) {
        QuickAddExampleKind.Event -> if (name != null) {
            stringResource(Res.string.quickadd_example_event, name)
        } else {
            stringResource(Res.string.quickadd_example_event_generic)
        }
        QuickAddExampleKind.Shopping -> stringResource(Res.string.quickadd_example_shopping)
        // Hand-off examples are only made for a family with children, so the name is always there.
        QuickAddExampleKind.Handoff -> stringResource(Res.string.quickadd_example_handoff, name.orEmpty())
        QuickAddExampleKind.Meal -> stringResource(Res.string.quickadd_example_meal)
    }
}

private fun exampleIcon(kind: QuickAddExampleKind): ImageVector = when (kind) {
    QuickAddExampleKind.Event -> PussletIcons.CalendarMonth
    QuickAddExampleKind.Shopping -> Icons.Filled.ShoppingCart
    QuickAddExampleKind.Handoff -> PussletIcons.Swap
    QuickAddExampleKind.Meal -> PussletIcons.Restaurant
}

/** "Kolla det markerade", with the terracotta icon of an uncertain reading. */
@Composable
private fun UncertainLine() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
        Text(
            stringResource(Res.string.quickadd_uncertain),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One action as one sentence with "Ändra"; the fields open under it. */
@Composable
private fun PreviewCard(sentence: List<SentencePart>, editing: Boolean, onToggleEditing: () -> Unit, fields: @Composable () -> Unit) {
    val accent = MaterialTheme.colorScheme.tertiary
    val text = remember(sentence, accent) {
        buildAnnotatedString {
            sentence.forEach { part ->
                if (part.uncertain) {
                    // Colour and weight, so the guess does not rely on colour alone.
                    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(part.text) }
                } else {
                    append(part.text)
                }
            }
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(vertical = Spacing.sm))
                TextButton(onClick = onToggleEditing, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
                    Text(stringResource(if (editing) Res.string.action_done else Res.string.action_change))
                }
            }
            if (editing) {
                Column(Modifier.padding(end = Spacing.sm, bottom = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    fields()
                }
            }
        }
    }
}

/** The sentence's words from the string resources: templates keep their `%1$s` so each part can be marked. */
@Composable
private fun sentenceWords(): SentenceWords = SentenceWords(
    who = stringResource(Res.string.quickadd_preview_who),
    labeled = stringResource(Res.string.quickadd_preview_labeled),
    due = stringResource(Res.string.quickadd_preview_due),
    pickUp = stringResource(Res.string.quickadd_handoff_pickup),
    dropOff = stringResource(Res.string.quickadd_handoff_dropoff),
    range = stringResource(Res.string.time_range),
    allChildren = stringResource(Res.string.quickadd_handoff_all_children),
    today = stringResource(Res.string.today),
    tomorrow = stringResource(Res.string.tomorrow),
    weekdays = stringArrayResource(Res.array.weekdays),
    monthsShort = stringArrayResource(Res.array.months_short),
    dateShort = stringResource(Res.string.date_short),
    listSeparator = stringResource(Res.string.list_separator),
    andSeparator = stringResource(Res.string.and_separator),
    kinds = ActionKind.entries.associateWith { kindLabel(it) },
    mealSlots = MealSlot.entries.associateWith { stringResource(Labels.mealSlot(it)) },
)

/** "Varje tisdag" for a repeating event or a hand-off on set weekdays; null when it does not repeat. */
@Composable
private fun repeatText(action: QuickAddAction): String? = when (action) {
    is QuickAddAction.CreateEvent -> action.recurrence?.let { describe(it) }
    is QuickAddAction.AssignHandoff -> action.recurringDays.takeIf { it.isNotEmpty() }?.let { days ->
        describe(RRule(Frequency.WEEKLY, byDay = days.sortedBy { it.isoDayNumber }.map { WeekdayNum(it) }))
    }
    else -> null
}

@Composable
private fun ActionFields(
    action: QuickAddAction,
    index: Int,
    primary: Boolean,
    uncertain: Set<UncertainField>,
    people: List<FamilyPerson>,
    onConvert: (Int, ActionKind) -> Unit,
    onTitle: (Int, String) -> Unit,
    onDate: (Int, LocalDate) -> Unit,
    onStart: (Int, LocalTime?) -> Unit,
    onEnd: (Int, LocalTime) -> Unit,
    onTogglePerson: (Int, String) -> Unit,
    onRemove: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (primary && action !is QuickAddAction.AssignHandoff) {
            KindChips(QuickAddEdits.kindOf(action), highlight = UncertainField.Kind in uncertain, modifier = Modifier.weight(1f)) {
                onConvert(index, it)
            }
        } else {
            Text(kindLabel(QuickAddEdits.kindOf(action)), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_delete))
            }
        }
    }
    when (action) {
        is QuickAddAction.CreateEvent -> EventFields(action, index, uncertain, people, onTitle, onDate, onStart, onEnd, onTogglePerson)
        is QuickAddAction.CreateTask -> {
            TitleField(action.title, highlight = UncertainField.Title in uncertain) { onTitle(index, it) }
            val due = action.due
            if (due != null) {
                DateField(stringResource(Res.string.field_due), due, { onDate(index, it) }, highlight = UncertainField.Date in uncertain)
            }
            if (action.linkedToEvent) {
                Text(stringResource(Res.string.quickadd_linked_task), style = MaterialTheme.typography.bodySmall)
            }
            PeopleField(
                people.filter { !it.isChild },
                setOfNotNull(action.assigneeId),
                UncertainField.Person in uncertain,
                stringResource(Res.string.field_assignee),
            ) { onTogglePerson(index, it) }
        }
        is QuickAddAction.AddShoppingItems -> action.items.forEach { item ->
            val q = item.quantity?.let { Units.format(it) + " " }.orEmpty()
            Text("• $q${item.name}", style = MaterialTheme.typography.bodyLarge)
        }
        is QuickAddAction.PlanMeal -> {
            TitleField(action.title, highlight = UncertainField.Title in uncertain) { onTitle(index, it) }
            DateField(
                stringResource(Labels.mealSlot(action.slot)),
                action.date,
                { onDate(index, it) },
                highlight = UncertainField.Date in uncertain,
            )
        }
        is QuickAddAction.AssignHandoff -> {
            val person = people.firstOrNull { it.id == action.personId }?.name.orEmpty()
            val children = action.childIds.mapNotNull { id -> people.firstOrNull { it.id == id }?.name }
            val childText = if (children.isEmpty()) stringResource(Res.string.quickadd_handoff_all_children) else joinNames(children)
            val template = when (action.kind) {
                HandoffKind.PickUp -> Res.string.quickadd_handoff_pickup
                HandoffKind.DropOff -> Res.string.quickadd_handoff_dropoff
            }
            Text(
                stringResource(template, person, childText),
                style = MaterialTheme.typography.bodyLarge,
                color = if (UncertainField.Person in uncertain) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
            )
            val dateUncertain = UncertainField.Date in uncertain
            DateField(stringResource(Res.string.field_date), action.date, { onDate(index, it) }, highlight = dateUncertain)
            PeopleField(people.filter { it.isChild }, action.childIds.toSet(), false) { onTogglePerson(index, it) }
        }
    }
}

@Composable
private fun EventFields(
    action: QuickAddAction.CreateEvent,
    index: Int,
    uncertain: Set<UncertainField>,
    people: List<FamilyPerson>,
    onTitle: (Int, String) -> Unit,
    onDate: (Int, LocalDate) -> Unit,
    onStart: (Int, LocalTime?) -> Unit,
    onEnd: (Int, LocalTime) -> Unit,
    onTogglePerson: (Int, String) -> Unit,
) {
    TitleField(action.title, highlight = UncertainField.Title in uncertain) { onTitle(index, it) }
    DateField(stringResource(Res.string.field_date), action.date, { onDate(index, it) }, highlight = UncertainField.Date in uncertain)
    val start = action.startTime
    val end = action.endTime
    if (start != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            val startUncertain = UncertainField.Time in uncertain
            TimeField(stringResource(Res.string.field_start), start, { onStart(index, it) }, Modifier.weight(1f), startUncertain)
            if (end != null) {
                val endUncertain = UncertainField.EndTime in uncertain
                TimeField(stringResource(Res.string.field_end), end, { onEnd(index, it) }, Modifier.weight(1f), endUncertain)
            }
        }
    } else {
        ValueField(stringResource(Res.string.field_time), stringResource(Res.string.all_day), onClick = {
            onStart(index, LocalTime(DEFAULT_HOUR, 0))
        })
    }
    val recurrence = action.recurrence
    if (recurrence != null) {
        ReadOnlyField(stringResource(Res.string.field_repeat), describe(recurrence), highlight = UncertainField.Recurrence in uncertain)
    }
    if (action.location.isNotBlank()) ReadOnlyField(stringResource(Res.string.field_location), action.location)
    if (action.tags.isNotEmpty()) {
        Text(
            action.tags.map { t -> Labels.tag(t)?.let { stringResource(it) } ?: t }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    PeopleField(people, action.participantIds.toSet(), UncertainField.Person in uncertain) { onTogglePerson(index, it) }
}

/** A guessed title gets the terracotta outline and label of an uncertain reading, never the error state. */
@Composable
private fun TitleField(value: String, highlight: Boolean, onChange: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.tertiary
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(Res.string.field_title)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = if (highlight) {
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = accent,
                focusedLabelColor = accent,
                unfocusedLabelColor = accent,
                cursorColor = accent,
            )
        } else {
            OutlinedTextFieldDefaults.colors()
        },
    )
}

/** The parser's reading as plain text, not a button: it cannot be changed here (UX plan 1.6). */
@Composable
private fun ReadOnlyField(label: String, value: String, highlight: Boolean = false) {
    val color = if (highlight) MaterialTheme.colorScheme.tertiary else Color.Unspecified
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs).semantics(mergeDescendants = true) {}) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (highlight) color else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

@Composable
private fun PeopleField(
    people: List<FamilyPerson>,
    selected: Set<String>,
    highlight: Boolean,
    label: String? = null,
    onToggle: (String) -> Unit,
) {
    if (people.isEmpty()) return
    Text(
        label ?: stringResource(Res.string.field_who),
        style = MaterialTheme.typography.labelMedium,
        color = if (highlight) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PersonChips(people, selected, onToggle)
}

@Composable
private fun KindChips(current: ActionKind, highlight: Boolean, modifier: Modifier = Modifier, onPick: (ActionKind) -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        listOf(ActionKind.Event, ActionKind.Task, ActionKind.Shopping, ActionKind.Meal).forEach { kind ->
            FilterChip(
                selected = kind == current,
                onClick = { onPick(kind) },
                label = {
                    val color = if (highlight && kind == current) MaterialTheme.colorScheme.tertiary else Color.Unspecified
                    Text(kindLabel(kind), color = color)
                },
                modifier = Modifier.heightIn(min = 40.dp),
                colors = SelectionColors.filterChip(),
            )
        }
    }
}

@Composable
private fun kindLabel(kind: ActionKind): String = stringResource(
    when (kind) {
        ActionKind.Event -> Res.string.quickadd_kind_event
        ActionKind.Task -> Res.string.quickadd_kind_task
        ActionKind.Shopping -> Res.string.quickadd_kind_shopping
        ActionKind.Meal -> Res.string.quickadd_kind_meal
        ActionKind.Handoff -> Res.string.quickadd_kind_handoff
    },
)

private const val DEFAULT_HOUR = 12
