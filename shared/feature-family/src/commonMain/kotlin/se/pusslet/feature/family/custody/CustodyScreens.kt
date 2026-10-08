package se.pusslet.feature.family.custody

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.custody.CustodyCalculator
import se.pusslet.core.custody.HandoverCard
import se.pusslet.core.custody.HandoverCardBuilder
import se.pusslet.core.model.Child
import se.pusslet.core.model.CustodyException
import se.pusslet.core.model.CustodyPatternType
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.Household
import se.pusslet.core.model.MemberColor
import se.pusslet.core.time.plusDays
import se.pusslet.core.time.startOfWeek
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PackingRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.ui.components.ColorBadge
import se.pusslet.ui.components.DateField
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.TimeField
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.weekShort
import se.pusslet.ui.format.weekdayName
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import kotlin.time.Clock

// region editor

data class CustodyEditorState(
    val schedule: CustodySchedule? = null,
    val children: List<Child> = emptyList(),
    val isNew: Boolean = true,
    val today: LocalDate? = null,
    val done: Boolean = false,
)

class CustodyEditorViewModel(private val family: FamilyRepository, private val clock: Clock, private val zone: TimeZone) : ViewModel() {
    private val _state = MutableStateFlow(CustodyEditorState())
    val state: StateFlow<CustodyEditorState> = _state.asStateFlow()

    fun load(scheduleId: String?, defaultA: String, defaultB: String) {
        if (_state.value.schedule != null) return
        viewModelScope.launch {
            val children = family.children.first()
            val existing = scheduleId?.let { id -> family.custody.first().firstOrNull { it.id == id } }
            val today = clock.todayIn(zone)
            _state.value = CustodyEditorState(
                schedule = existing ?: CustodySchedule(
                    id = family.newId(),
                    childIds = children.map { it.id },
                    households = listOf(
                        Household(family.newId(), defaultA, color = MemberColor.Sage),
                        Household(family.newId(), defaultB, color = MemberColor.Ocean),
                    ),
                    anchorDate = today.startOfWeek(),
                ),
                children = children,
                isNew = existing == null,
                today = today,
            )
        }
    }

    fun edit(transform: (CustodySchedule) -> CustodySchedule) = _state.update { s -> s.copy(schedule = s.schedule?.let(transform)) }

    fun renameHousehold(index: Int, name: String) = edit { s ->
        s.copy(
            households = s.households.mapIndexed { i, h ->
                if (i ==
                    index
                ) {
                    h.copy(name = name)
                } else {
                    h
                }
            },
        )
    }

    fun save() {
        val s = _state.value.schedule ?: return
        viewModelScope.launch {
            family.saveCustody(s)
            _state.update { it.copy(done = true) }
        }
    }

    fun delete() {
        val s = _state.value.schedule ?: return
        viewModelScope.launch {
            family.deleteCustody(s.id)
            _state.update { it.copy(done = true) }
        }
    }
}

@Composable
fun CustodyEditorRoute(scheduleId: String?, onDone: () -> Unit, viewModel: CustodyEditorViewModel = koinViewModel()) {
    val a = stringResource(Res.string.custody_household_a)
    val b = stringResource(Res.string.custody_household_b)
    LaunchedEffect(scheduleId) { viewModel.load(scheduleId, a, b) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    if (state.done) return
    val s = state.schedule ?: return
    Scaffold(
        topBar = {
            PussletTopBar(stringResource(Res.string.custody_title), onBack = onDone, actions = {
                TextButton(onClick = viewModel::save, enabled = s.childIds.isNotEmpty()) { Text(stringResource(Res.string.action_save)) }
            })
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(Res.string.custody_children), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                state.children.forEach { c ->
                    FilterChip(
                        selected = c.id in s.childIds,
                        onClick = {
                            viewModel.edit { cs ->
                                cs.copy(
                                    childIds = if (c.id in
                                        cs.childIds
                                    ) {
                                        cs.childIds - c.id
                                    } else {
                                        cs.childIds + c.id
                                    },
                                )
                            }
                        },
                        label = { Text(c.firstName) },
                    )
                }
            }
            Text(stringResource(Res.string.custody_pattern), style = MaterialTheme.typography.labelLarge)
            listOf(
                CustodyPatternType.AlternatingWeeks to Res.string.custody_pattern_alternating,
                CustodyPatternType.TwoTwoFiveFive to Res.string.custody_pattern_2255,
                CustodyPatternType.TwoTwoThree to Res.string.custody_pattern_223,
            ).forEach { (type, label) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = s.pattern == type, role = Role.RadioButton) {
                        viewModel.edit { it.copy(pattern = type) }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.pattern == type, onClick = null)
                    Text(stringResource(label), modifier = Modifier.padding(start = Spacing.sm))
                }
            }
            Text(stringResource(Res.string.custody_households), style = MaterialTheme.typography.labelLarge)
            s.households.forEachIndexed { i, h ->
                OutlinedTextField(
                    value = h.name,
                    onValueChange = { viewModel.renameHousehold(i, it) },
                    label = { Text(stringResource(if (i == 0) Res.string.custody_household_a else Res.string.custody_household_b)) },
                    placeholder = { Text(stringResource(Res.string.custody_household_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
            DateField(stringResource(Res.string.custody_anchor), s.anchorDate, { d -> viewModel.edit { it.copy(anchorDate = d) } })
            TimeField(stringResource(Res.string.custody_handover_time), s.handoverTime, { t ->
                viewModel.edit { it.copy(handoverTime = t) }
            })
            SectionCard(stringResource(Res.string.custody_exceptions)) {
                s.exceptions.forEachIndexed { i, ex ->
                    val household = s.households.firstOrNull { it.id == ex.householdId }?.name.orEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(
                                Res.string.custody_exception_line,
                                formatDateShort(ex.start),
                                formatDateShort(ex.end),
                                household,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            viewModel.edit { cs ->
                                cs.copy(exceptions = cs.exceptions.filterIndexed { j, _ -> j != i })
                            }
                        }) {
                            Text(stringResource(Res.string.action_delete))
                        }
                    }
                }
                val today = state.today
                if (today != null) {
                    OutlinedButton(onClick = {
                        viewModel.edit { cs ->
                            cs.copy(
                                exceptions =
                                    cs.exceptions + CustodyException(today.plusDays(7), today.plusDays(13), cs.households.first().id),
                            )
                        }
                    }) { Text(stringResource(Res.string.custody_exception_add)) }
                }
                s.exceptions.forEachIndexed { i, ex ->
                    Column {
                        DateField(stringResource(Res.string.field_start), ex.start, { d ->
                            viewModel.edit { cs ->
                                cs.copy(
                                    exceptions = cs.exceptions.mapIndexed { j, e ->
                                        if (j ==
                                            i
                                        ) {
                                            e.copy(start = d)
                                        } else {
                                            e
                                        }
                                    },
                                )
                            }
                        })
                        DateField(stringResource(Res.string.field_end), ex.end, { d ->
                            viewModel.edit { cs ->
                                cs.copy(
                                    exceptions = cs.exceptions.mapIndexed { j, e ->
                                        if (j ==
                                            i
                                        ) {
                                            e.copy(end = d)
                                        } else {
                                            e
                                        }
                                    },
                                )
                            }
                        })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            s.households.forEach { h ->
                                FilterChip(
                                    selected = ex.householdId == h.id,
                                    onClick = {
                                        viewModel.edit { cs ->
                                            cs.copy(
                                                exceptions = cs.exceptions.mapIndexed { j, e ->
                                                    if (j ==
                                                        i
                                                    ) {
                                                        e.copy(householdId = h.id)
                                                    } else {
                                                        e
                                                    }
                                                },
                                            )
                                        }
                                    },
                                    label = { Text(h.name) },
                                )
                            }
                        }
                    }
                }
            }
            state.today?.let { today -> CustodyPreview(s, today) }
            if (!state.isNew) {
                OutlinedButton(onClick = viewModel::delete, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.action_delete))
                }
            }
            Button(
                onClick = viewModel::save,
                enabled = s.childIds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
            ) {
                Text(stringResource(Res.string.action_save))
            }
        }
    }
}

/** Four weeks as rows of seven coloured days with the household's initial, so the pattern is easy to check. */
@Composable
private fun CustodyPreview(s: CustodySchedule, today: LocalDate) {
    SectionCard(stringResource(Res.string.custody_preview)) {
        val monday = today.startOfWeek()
        for (w in 0 until PREVIEW_WEEKS) {
            val weekStart = monday.plusDays(w * 7)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(weekShort(weekStart), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = Spacing.xs))
                for (d in 0 until 7) {
                    val date = weekStart.plusDays(d)
                    val h = CustodyCalculator.householdOn(s, date)
                    if (h != null) ColorBadge(h.color, h.name.take(1), weekdayName(date.dayOfWeek) + ": " + h.name, size = 32.dp)
                }
            }
        }
    }
}

// endregion

// region handover

data class HandoverUiState(val card: HandoverCard? = null, val note: String = "")

@OptIn(ExperimentalCoroutinesApi::class)
class HandoverViewModel(
    family: FamilyRepository,
    calendar: CalendarRepository,
    packing: PackingRepository,
    tasks: TaskRepository,
    private val sessions: SessionProvider,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val scheduleId = MutableStateFlow<String?>(null)
    private val note = MutableStateFlow("")

    val state: StateFlow<HandoverUiState> = scheduleId.flatMapLatest { id ->
        if (id == null) {
            flowOf(HandoverUiState())
        } else {
            val today = clock.todayIn(zone)
            combine(
                family.custody,
                family.children,
                packing.rules,
                tasks.tasks,
                calendar.occurrences(today, today.plusDays(LOOKAHEAD_DAYS), zone),
            ) {
                cus,
                kids,
                rules,
                t,
                occ,
                ->
                Triple(cus.firstOrNull { it.id == id }, kids, Triple(rules, t, occ))
            }.combine(note) { (schedule, kids, rest), n ->
                val handover = schedule?.let { CustodyCalculator.nextHandover(it, today) }
                if (schedule == null || handover == null) {
                    HandoverUiState(note = n)
                } else {
                    val nextBack = CustodyCalculator.nextHandover(schedule, handover.date.plusDays(1))
                    val periodEnd = nextBack?.date?.plusDays(-1) ?: handover.date.plusDays(DEFAULT_PERIOD_DAYS)
                    HandoverUiState(HandoverCardBuilder.build(handover, kids, rest.first, rest.second, rest.third, periodEnd, n), n)
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HandoverUiState())

    fun load(id: String) {
        scheduleId.value = id
    }

    fun setNote(text: String) {
        note.value = text
    }

    private companion object {
        const val LOOKAHEAD_DAYS = 21
        const val DEFAULT_PERIOD_DAYS = 6
    }
}

@Composable
fun HandoverRoute(scheduleId: String, onBack: () -> Unit, viewModel: HandoverViewModel = koinViewModel()) {
    LaunchedEffect(scheduleId) { viewModel.load(scheduleId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val platform = LocalPlatformActions.current
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.handover_title), onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val card = state.card
            if (card == null) {
                Text(stringResource(Res.string.handover_nothing), style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            val names = card.children.joinToString(", ") { it.firstName }
            val title =
                stringResource(
                    Res.string.handover_when,
                    formatDateShort(card.handover.date),
                    formatTime(card.handover.time),
                    card.handover.to.name,
                )
            Text(names, style = MaterialTheme.typography.titleLarge)
            Text(title, style = MaterialTheme.typography.bodyLarge)
            val bringTitle = stringResource(Res.string.handover_bring)
            val homeworkTitle = stringResource(Res.string.handover_homework)
            val upcomingTitle = stringResource(Res.string.handover_upcoming)
            val nothing = stringResource(Res.string.handover_nothing)
            SectionCard(bringTitle) { if (card.bring.isEmpty()) Text(nothing) else card.bring.forEach { Text("• $it") } }
            SectionCard(homeworkTitle) { if (card.homework.isEmpty()) Text(nothing) else card.homework.forEach { Text("• $it") } }
            val upcomingLines = card.upcoming.map {
                "${formatDateShort(it.date)}: ${it.title}" +
                    if (it.childNames.size < card.children.size) " (${it.childNames.joinToString()})" else ""
            }
            SectionCard(upcomingTitle) { if (upcomingLines.isEmpty()) Text(nothing) else upcomingLines.forEach { Text("• $it") } }
            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::setNote,
                label = { Text(stringResource(Res.string.handover_note)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Text(
                stringResource(Res.string.handover_share_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val text = buildString {
                appendLine("$names – $title")
                if (card.bring.isNotEmpty()) appendLine("\n$bringTitle:\n" + card.bring.joinToString("\n") { "• $it" })
                if (card.homework.isNotEmpty()) appendLine("\n$homeworkTitle:\n" + card.homework.joinToString("\n") { "• $it" })
                if (upcomingLines.isNotEmpty()) appendLine("\n$upcomingTitle:\n" + upcomingLines.joinToString("\n") { "• $it" })
                if (state.note.isNotBlank()) appendLine("\n${state.note.trim()}")
            }
            Button(onClick = {
                platform.shareText(text.trim())
            }, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget)) {
                Text(stringResource(Res.string.handover_share))
            }
        }
    }
}

/** Serialisable snapshot for the read-only share link (handoverShares/{token}). */
fun HandoverCard.toSharePayload(note: String) = buildJsonObject {
    put("date", handover.date.toString())
    put("time", formatTime(handover.time))
    put("to", handover.to.name)
    putJsonArray("children") { children.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.firstName)) } }
    putJsonArray("bring") { bring.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
    putJsonArray("homework") { homework.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
    put("note", note)
}

// endregion

private const val PREVIEW_WEEKS = 4
