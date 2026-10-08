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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.quickadd.ActionKind
import se.pusslet.core.quickadd.QuickAddAction
import se.pusslet.core.quickadd.QuickAddEdits
import se.pusslet.core.quickadd.UncertainField
import se.pusslet.core.units.Units
import se.pusslet.data.usecase.QuickAddOutcome
import se.pusslet.ui.components.DateField
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.PersonChips
import se.pusslet.ui.components.TimeField
import se.pusslet.ui.components.ValueField
import se.pusslet.ui.format.describe
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    viewModel: QuickAddViewModel,
    onDismiss: () -> Unit,
    onSaved: (QuickAddOutcome) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        QuickAddContent(
            state = state,
            onTextChange = viewModel::onTextChange,
            onConvert = viewModel::convert,
            onTitle = viewModel::setTitle,
            onDate = viewModel::setDate,
            onStart = viewModel::setStart,
            onEnd = viewModel::setEnd,
            onTogglePerson = viewModel::togglePerson,
            onRemove = viewModel::removeAction,
            onSave = { viewModel.save(onSaved) },
            onClose = onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddContent(
    state: QuickAddUiState,
    onTextChange: (String) -> Unit,
    onConvert: (Int, ActionKind) -> Unit,
    onTitle: (Int, String) -> Unit,
    onDate: (Int, kotlinx.datetime.LocalDate) -> Unit,
    onStart: (Int, LocalTime?) -> Unit,
    onEnd: (Int, LocalTime) -> Unit,
    onTogglePerson: (Int, String) -> Unit,
    onRemove: (Int) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
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
        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = { Text(stringResource(Res.string.quickadd_hint)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            minLines = 1,
            maxLines = 4,
        )
        if (state.text.isBlank()) {
            Text(
                stringResource(Res.string.quickadd_examples),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val examples = listOf(
                stringResource(Res.string.quickadd_example_1),
                stringResource(Res.string.quickadd_example_2),
                stringResource(Res.string.quickadd_example_3),
                stringResource(Res.string.quickadd_example_4),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                (state.recent + examples).distinct().take(MAX_SUGGESTIONS).forEach { ex ->
                    SuggestionChip(onClick = { onTextChange(ex) }, label = { Text(ex, maxLines = 1) })
                }
            }
        } else if (state.actions.isNotEmpty()) {
            Text(
                stringResource(Res.string.quickadd_interpreted),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.uncertain.isNotEmpty()) {
                Text(
                    stringResource(Res.string.quickadd_uncertain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            state.actions.forEachIndexed { index, action ->
                ActionCard(
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
        Button(
            onClick = onSave,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
        ) {
            Text(
                if (state.actions.size >
                    1
                ) {
                    stringResource(Res.string.quickadd_save_all, state.actions.size)
                } else {
                    stringResource(Res.string.quickadd_save)
                },
            )
        }
    }
}

@Composable
private fun ActionCard(
    action: QuickAddAction,
    index: Int,
    primary: Boolean,
    uncertain: Set<UncertainField>,
    people: List<FamilyPerson>,
    onConvert: (Int, ActionKind) -> Unit,
    onTitle: (Int, String) -> Unit,
    onDate: (Int, kotlinx.datetime.LocalDate) -> Unit,
    onStart: (Int, LocalTime?) -> Unit,
    onEnd: (Int, LocalTime) -> Unit,
    onTogglePerson: (Int, String) -> Unit,
    onRemove: (() -> Unit)?,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (primary && action !is QuickAddAction.AssignHandoff) {
                    KindChips(QuickAddEdits.kindOf(action), highlight = UncertainField.Kind in uncertain, modifier = Modifier.weight(1f)) {
                        onConvert(index, it)
                    }
                } else {
                    Text(
                        kindLabel(QuickAddEdits.kindOf(action)),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (onRemove != null) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_delete))
                    }
                }
            }
            when (action) {
                is QuickAddAction.CreateEvent -> {
                    TitleField(action.title, highlight = UncertainField.Title in uncertain) { onTitle(index, it) }
                    DateField(
                        stringResource(Res.string.field_date),
                        action.date,
                        { onDate(index, it) },
                        highlight =
                            UncertainField.Date in uncertain,
                    )
                    val start = action.startTime
                    val end = action.endTime
                    if (start != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                            TimeField(
                                stringResource(Res.string.field_start),
                                start,
                                { onStart(index, it) },
                                Modifier.weight(1f),
                                UncertainField.Time in uncertain,
                            )
                            if (end != null) {
                                TimeField(
                                    stringResource(Res.string.field_end),
                                    end,
                                    { onEnd(index, it) },
                                    Modifier.weight(1f),
                                    UncertainField.EndTime in uncertain,
                                )
                            }
                        }
                    } else {
                        ValueField(stringResource(Res.string.field_time), stringResource(Res.string.all_day), onClick = {
                            onStart(index, LocalTime(DEFAULT_HOUR, 0))
                        })
                    }
                    if (action.recurrence != null) {
                        ValueField(
                            stringResource(Res.string.field_repeat),
                            describe(action.recurrence),
                            onClick = {},
                            highlight =
                                UncertainField.Recurrence in uncertain,
                        )
                    }
                    if (action.location.isNotBlank()) ValueField(stringResource(Res.string.field_location), action.location, onClick = {})
                    if (action.tags.isNotEmpty()) {
                        Text(
                            action.tags.map { t ->
                                Labels.tag(t)?.let { stringResource(it) } ?: t
                            }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    PeopleField(people, action.participantIds.toSet(), UncertainField.Person in uncertain) { onTogglePerson(index, it) }
                }
                is QuickAddAction.CreateTask -> {
                    TitleField(action.title, highlight = UncertainField.Title in uncertain) { onTitle(index, it) }
                    val due = action.due
                    if (due != null) {
                        DateField(
                            stringResource(Res.string.field_due),
                            due,
                            { onDate(index, it) },
                            highlight =
                                UncertainField.Date in uncertain,
                        )
                    }
                    if (action.linkedToEvent) {
                        Text(
                            stringResource(Res.string.quickadd_linked_task),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    PeopleField(
                        people.filter {
                            !it.isChild
                        },
                        setOfNotNull(action.assigneeId),
                        UncertainField.Person in uncertain,
                        stringResource(Res.string.field_assignee),
                    ) {
                        onTogglePerson(index, it)
                    }
                }
                is QuickAddAction.AddShoppingItems -> {
                    action.items.forEach { item ->
                        val q = item.quantity?.let { Units.format(it) + " " }.orEmpty()
                        Text("• $q${item.name}", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                is QuickAddAction.PlanMeal -> {
                    TitleField(action.title, highlight = UncertainField.Title in uncertain) { onTitle(index, it) }
                    DateField(
                        stringResource(Labels.mealSlot(action.slot)),
                        action.date,
                        { onDate(index, it) },
                        highlight =
                            UncertainField.Date in uncertain,
                    )
                }
                is QuickAddAction.AssignHandoff -> {
                    val person = people.firstOrNull { it.id == action.personId }?.name.orEmpty()
                    val children = action.childIds.mapNotNull { id -> people.firstOrNull { it.id == id }?.name }
                    val childText = if (children.isEmpty()) {
                        stringResource(
                            Res.string.quickadd_handoff_all_children,
                        )
                    } else {
                        joinNames(children)
                    }
                    Text(
                        stringResource(
                            if (action.kind ==
                                se.pusslet.core.model.HandoffKind.PickUp
                            ) {
                                Res.string.quickadd_handoff_pickup
                            } else {
                                Res.string.quickadd_handoff_dropoff
                            },
                            person,
                            childText,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    DateField(
                        stringResource(Res.string.field_date),
                        action.date,
                        { onDate(index, it) },
                        highlight =
                            UncertainField.Date in uncertain,
                    )
                    PeopleField(people.filter { it.isChild }, action.childIds.toSet(), false) { onTogglePerson(index, it) }
                }
            }
        }
    }
}

@Composable
private fun TitleField(value: String, highlight: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(Res.string.field_title)) },
        isError = highlight,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
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
                    Text(
                        kindLabel(kind),
                        color = if (highlight &&
                            kind == current
                        ) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            androidx.compose.ui.graphics.Color.Unspecified
                        },
                    )
                },
                modifier = Modifier.heightIn(min = 40.dp),
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

private const val MAX_SUGGESTIONS = 6
private const val DEFAULT_HOUR = 12
