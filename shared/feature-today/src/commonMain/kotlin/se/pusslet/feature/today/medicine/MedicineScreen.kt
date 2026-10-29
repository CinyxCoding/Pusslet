package se.pusslet.feature.today.medicine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.medicine.HealthLinks
import se.pusslet.core.medicine.MedicineRules
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.MedicineCourse
import se.pusslet.core.model.MedicineEntry
import se.pusslet.core.model.Role
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.MedicineRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.ui.components.DateField
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.PersonChips
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.components.SheetDragHandle
import se.pusslet.ui.components.TimeField
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.relativeDay
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

data class MedicineUiState(
    val loading: Boolean = true,
    /** Only parents keep the log (ADR-050). */
    val canEdit: Boolean = false,
    val children: List<FamilyPerson> = emptyList(),
    val childId: String? = null,
    val courses: List<CourseRow> = emptyList(),
    val log: List<LogRow> = emptyList(),
    val lastTemperature: LogRow? = null,
    /** Medicines given before, for one tap. */
    val usual: List<String> = emptyList(),
    val today: LocalDate? = null,
) {
    val child: FamilyPerson? get() = children.firstOrNull { it.id == childId }
}

/** "Medicin och feber" (ADR-050): the log of doses and temperatures per child, and courses with reminders. */
class MedicineViewModel(
    family: FamilyRepository,
    private val repo: MedicineRepository,
    private val sessions: SessionProvider,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val chosen = MutableStateFlow<String?>(null)
    private val log = MutableStateFlow<List<MedicineEntry>>(emptyList())

    val state: StateFlow<MedicineUiState> = combine(family.people, repo.entries, repo.courses, chosen, sessions.session) {
        people,
        entries,
        courses,
        pick,
        s,
        ->
        log.value = entries
        val children = people.filter { it.isChild }
        val childId = pick?.takeIf { id -> children.any { it.id == id } } ?: children.firstOrNull()?.id
        val today = clock.todayIn(zone)
        val byId = people.associateBy { it.id }
        MedicineUiState(
            loading = false,
            canEdit = s.role == Role.Parent,
            children = children,
            childId = childId,
            courses = childId?.let { MedicineModel.courses(it, today, courses, entries, byId) }.orEmpty(),
            log = childId?.let { id ->
                MedicineModel.log(id, entries, byId, zone).filter { it.entry.at >= clock.now() - LOG_DAYS }
            }.orEmpty(),
            lastTemperature = childId?.let { MedicineRules.lastTemperature(entries, it) }?.let { MedicineModel.row(it, byId, zone) },
            usual = MedicineRules.usual(entries, courses),
            today = today,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MedicineUiState())

    fun start(childId: String?) {
        if (childId != null) chosen.value = childId
    }

    fun choose(childId: String) {
        chosen.value = childId
    }

    /** The dose last given of [medicine], to fill in again. */
    fun usualDose(medicine: String): String = MedicineRules.usualDose(log.value, medicine).orEmpty()

    fun logDose(medicine: String, dose: String) = write { child -> repo.logDose(child, medicine, dose) }

    /** False when [text] is not a body temperature. */
    fun logTemperature(text: String): Boolean {
        val value = MedicineRules.parseTemperature(text) ?: return false
        write { child -> repo.logTemperature(child, value) }
        return true
    }

    fun giveDose(slot: MedicineRules.Slot) = write {
        repo.logDose(slot.course.childId, slot.course.medicine, slot.course.dose, courseId = slot.course.id, slot = slot.at)
    }

    fun delete(entry: MedicineEntry) = write { repo.delete(entry) }

    fun addCourse(medicine: String, dose: String, times: List<LocalTime>, start: LocalDate, days: Int) = write { child ->
        repo.saveCourse(
            MedicineCourse(
                id = repo.newCourseId(),
                childId = child,
                medicine = medicine.trim(),
                dose = dose.trim(),
                times = times.distinct().sorted().take(MedicineCourse.MAX_TIMES),
                start = start,
                days = days.coerceIn(1, MedicineCourse.MAX_DAYS),
                createdBy = sessions.session.value.memberId,
            ),
        )
    }

    fun stopCourse(course: MedicineCourse) = write { repo.stopCourse(course.id) }

    private fun write(block: suspend (childId: String) -> Unit) {
        val s = state.value
        val child = s.childId ?: return
        if (!s.canEdit) return
        viewModelScope.launch { block(child) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        val LOG_DAYS = 14.days
    }
}

@Composable
fun MedicineRoute(childId: String? = null, onBack: () -> Unit, viewModel: MedicineViewModel = koinViewModel()) {
    LaunchedEffect(childId) { viewModel.start(childId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val platform = LocalPlatformActions.current
    MedicineScreen(
        state,
        MedicineActions(
            onBack = onBack,
            onChoose = viewModel::choose,
            onLogDose = viewModel::logDose,
            onLogTemperature = viewModel::logTemperature,
            onGive = viewModel::giveDose,
            onDelete = viewModel::delete,
            onAddCourse = viewModel::addCourse,
            onStopCourse = viewModel::stopCourse,
            usualDose = viewModel::usualDose,
            onOpenLink = platform::openUrl,
            onCall = platform::dial,
        ),
    )
}

/** What the medicine view's controls do; the defaults do nothing, for previews and screenshot tests. */
data class MedicineActions(
    val onBack: () -> Unit = {},
    val onChoose: (String) -> Unit = {},
    val onLogDose: (medicine: String, dose: String) -> Unit = { _, _ -> },
    val onLogTemperature: (String) -> Boolean = { true },
    val onGive: (MedicineRules.Slot) -> Unit = {},
    val onDelete: (MedicineEntry) -> Unit = {},
    val onAddCourse: (medicine: String, dose: String, times: List<LocalTime>, start: LocalDate, days: Int) -> Unit = { _, _, _, _, _ -> },
    val onStopCourse: (MedicineCourse) -> Unit = {},
    val usualDose: (String) -> String = { "" },
    /** A page on FASS or 1177 (ADR-061). */
    val onOpenLink: (String) -> Unit = {},
    /** The phone app with a number filled in: 1177. */
    val onCall: (String) -> Unit = {},
)

private enum class MedicineSheet { Dose, Temperature, Course }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedicineScreen(state: MedicineUiState, actions: MedicineActions) {
    var sheet by rememberSaveable { mutableStateOf<MedicineSheet?>(null) }
    var deleting by remember { mutableStateOf<MedicineEntry?>(null) }
    var stopping by remember { mutableStateOf<MedicineCourse?>(null) }
    val separator = stringResource(Res.string.decimal_separator).first()
    val fass: (String) -> Unit = { medicine -> actions.onOpenLink(HealthLinks.fass(medicine)) }
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.medicine_title), onBack = actions.onBack) }) { padding ->
        val today = state.today
        if (state.loading || today == null) return@Scaffold
        if (state.children.isEmpty() || !state.canEdit) {
            EmptyState(
                message = stringResource(if (state.canEdit) Res.string.medicine_no_children else Res.string.medicine_parents_only),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (state.children.size > 1) {
                item(key = "children") {
                    PersonChips(state.children, setOfNotNull(state.childId), onToggle = actions.onChoose)
                }
            }
            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(onClick = { sheet = MedicineSheet.Dose }) { Text(stringResource(Res.string.medicine_give)) }
                    OutlinedButton(onClick = { sheet = MedicineSheet.Temperature }) { Text(stringResource(Res.string.medicine_temp)) }
                }
            }
            state.lastTemperature?.let { t ->
                item(key = "last-temp") {
                    val value = MedicineModel.temperature(t.entry.temperature ?: 0.0, separator)
                    val time = t.local
                    Text(
                        stringResource(Res.string.medicine_last_temp, value, relativeDay(time.date, today) + " " + formatTime(time.time)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "courses") {
                SectionCard(
                    stringResource(Res.string.medicine_courses),
                    action = {
                        TextButton(onClick = { sheet = MedicineSheet.Course }) { Text(stringResource(Res.string.medicine_new_course)) }
                    },
                ) {
                    if (state.courses.isEmpty()) {
                        Text(stringResource(Res.string.medicine_course_reminders), style = MaterialTheme.typography.bodySmall)
                    }
                    state.courses.forEach { row ->
                        CourseView(row, today, actions.onGive, onFass = { fass(row.course.medicine) }, onStop = { stopping = row.course })
                    }
                }
            }
            item(key = "help") { HelpCard(actions) }
            item(key = "log-title") {
                Text(
                    stringResource(Res.string.medicine_log),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (state.log.isEmpty()) {
                item(key = "log-empty") {
                    Text(stringResource(Res.string.medicine_log_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(state.log, key = { it.entry.id }) { row -> LogLine(row, today, separator) { deleting = row.entry } }
            item(key = "privacy") {
                Text(
                    stringResource(Res.string.medicine_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (sheet) {
            MedicineSheet.Dose -> DoseSheet(state.usual, actions.usualDose, fass, onDismiss = { sheet = null }) { m, d ->
                sheet = null
                actions.onLogDose(m, d)
            }
            MedicineSheet.Temperature -> TemperatureSheet(
                actions.onLogTemperature,
                onFeverAdvice = { actions.onOpenLink(HealthLinks.FEVER_IN_CHILDREN) },
                onDone = { sheet = null },
            )
            MedicineSheet.Course -> CourseSheet(today, state.usual, actions.usualDose, fass, onDismiss = {
                sheet = null
            }) { m, d, times, start, days ->
                sheet = null
                actions.onAddCourse(m, d, times, start, days)
            }
            null -> Unit
        }
    }
    deleting?.let { entry ->
        Confirm(stringResource(Res.string.medicine_delete_confirm), stringResource(Res.string.action_delete), onDismiss = {
            deleting = null
        }) {
            deleting = null
            actions.onDelete(entry)
        }
    }
    stopping?.let { course ->
        Confirm(stringResource(Res.string.medicine_course_stop_confirm), stringResource(Res.string.medicine_course_stop), onDismiss = {
            stopping =
                null
        }) {
            stopping = null
            actions.onStopCourse(course)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourseView(row: CourseRow, today: LocalDate, onGive: (MedicineRules.Slot) -> Unit, onFass: () -> Unit, onStop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    listOf(row.course.medicine, row.course.dose).filter {
                        it.isNotBlank()
                    }.joinToString(" "),
                    style = MaterialTheme.typography.bodyLarge,
                )
                val until = stringResource(Res.string.medicine_course_until, formatDateShort(row.lastDay))
                val progress = stringResource(Res.string.medicine_course_progress, row.given, row.total)
                val starts = if (row.course.start >
                    today
                ) {
                    stringResource(Res.string.medicine_course_starts, relativeDay(row.course.start, today)) + " · "
                } else {
                    ""
                }
                Text(
                    "$starts$progress · $until",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onStop) { Text(stringResource(Res.string.medicine_course_stop)) }
        }
        if (row.today.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                row.today.forEach { slot ->
                    FilterChip(
                        selected = slot.done,
                        enabled = !slot.done,
                        onClick = { onGive(slot) },
                        label = { Text(formatTime(slot.at.time)) },
                        colors = SelectionColors.filterChip(),
                    )
                }
            }
        }
        TextButton(onClick = onFass) { Text(stringResource(Res.string.medicine_fass)) }
    }
}

/** Where to turn when the log is not enough (ADR-061): 1177 by phone, and 1177's page on fever in children. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HelpCard(actions: MedicineActions) {
    SectionCard(stringResource(Res.string.medicine_help_title)) {
        Text(stringResource(Res.string.medicine_help_body), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(onClick = { actions.onCall(HealthLinks.PHONE_1177) }) { Text(stringResource(Res.string.medicine_call_1177)) }
            TextButton(onClick = { actions.onOpenLink(HealthLinks.FEVER_IN_CHILDREN) }) {
                Text(stringResource(Res.string.medicine_fever_1177))
            }
        }
    }
}

@Composable
private fun LogLine(row: LogRow, today: LocalDate, separator: Char, onDelete: () -> Unit) {
    val e = row.entry
    val local = row.local
    val what = e.temperature?.let { t ->
        val text = stringResource(Res.string.medicine_entry_temp, MedicineModel.temperature(t, separator))
        if (row.fever) text + " · " + stringResource(Res.string.medicine_fever) else text
    } ?: listOf(e.medicine, e.dose).filter { it.isNotBlank() }.joinToString(" ")
    val whenText = relativeDay(local.date, today) + " " + formatTime(local.time)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                what,
                style = MaterialTheme.typography.bodyLarge,
                color = if (row.fever) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                listOfNotNull(whenText, row.by?.name).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_delete)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DoseSheet(
    usual: List<String>,
    usualDose: (String) -> String,
    onFass: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var medicine by rememberSaveable { mutableStateOf("") }
    var dose by rememberSaveable { mutableStateOf("") }
    Sheet(stringResource(Res.string.medicine_give), onDismiss) {
        MedicineFields(medicine, dose, usual, onFass, onMedicine = { m ->
            medicine = m
            if (dose.isBlank()) dose = usualDose(m)
        }, onDose = { dose = it })
        Button(onClick = { onSave(medicine, dose) }, enabled = medicine.isNotBlank(), modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(Res.string.action_save))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MedicineFields(
    medicine: String,
    dose: String,
    usual: List<String>,
    onFass: (String) -> Unit,
    onMedicine: (String) -> Unit,
    onDose: (String) -> Unit,
) {
    if (usual.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            usual.forEach { m ->
                FilterChip(
                    selected = m.equals(medicine.trim(), ignoreCase = true),
                    onClick = { onMedicine(m) },
                    label = { Text(m) },
                    colors = SelectionColors.filterChip(),
                )
            }
        }
    }
    OutlinedTextField(
        value = medicine,
        onValueChange = onMedicine,
        label = { Text(stringResource(Res.string.medicine_field_name)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
    if (medicine.isNotBlank()) {
        TextButton(onClick = { onFass(medicine) }) { Text(stringResource(Res.string.medicine_fass)) }
    }
    OutlinedTextField(
        value = dose,
        onValueChange = onDose,
        label = { Text(stringResource(Res.string.medicine_field_dose)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TemperatureSheet(onLog: (String) -> Boolean, onFeverAdvice: () -> Unit, onDone: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    Sheet(stringResource(Res.string.medicine_temp), onDone) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                invalid = false
            },
            label = { Text(stringResource(Res.string.medicine_field_temp)) },
            isError = invalid,
            supportingText = if (invalid) ({ Text(stringResource(Res.string.medicine_temp_invalid)) }) else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onFeverAdvice) { Text(stringResource(Res.string.medicine_fever_1177)) }
        Button(onClick = {
            if (onLog(text)) onDone() else invalid = true
        }, enabled = text.isNotBlank(), modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(Res.string.action_save))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourseSheet(
    today: LocalDate,
    usual: List<String>,
    usualDose: (String) -> String,
    onFass: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, List<LocalTime>, LocalDate, Int) -> Unit,
) {
    var medicine by rememberSaveable { mutableStateOf("") }
    var dose by rememberSaveable { mutableStateOf("") }
    var perDay by rememberSaveable { mutableStateOf(2) }
    var times by remember { mutableStateOf(MedicineRules.defaultTimes(2)) }
    var start by remember { mutableStateOf(today) }
    var daysText by rememberSaveable { mutableStateOf("") }
    val days = daysText.toIntOrNull()?.takeIf { it in 1..MedicineCourse.MAX_DAYS }
    Sheet(stringResource(Res.string.medicine_new_course), onDismiss) {
        MedicineFields(medicine, dose, usual, onFass, onMedicine = { m ->
            medicine = m
            if (dose.isBlank()) dose = usualDose(m)
        }, onDose = { dose = it })
        Text(stringResource(Res.string.medicine_course_per_day), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PER_DAY_CHOICES.forEach { n ->
                FilterChip(
                    selected = perDay == n,
                    onClick = {
                        perDay = n
                        times = MedicineRules.defaultTimes(n)
                    },
                    label = { Text(n.toString()) },
                    colors = SelectionColors.filterChip(),
                )
            }
        }
        times.forEachIndexed { i, t ->
            TimeField(stringResource(Res.string.medicine_course_time, i + 1), t, { picked ->
                times = times.toMutableList().also { it[i] = picked }
            })
        }
        DateField(stringResource(Res.string.medicine_course_start), start, { start = it })
        OutlinedTextField(
            value = daysText,
            onValueChange = { v -> daysText = v.filter { it.isDigit() }.take(2) },
            label = { Text(stringResource(Res.string.medicine_course_days)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(Res.string.medicine_course_reminders), style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = { days?.let { onSave(medicine, dose, times, start, it) } },
            enabled = medicine.isNotBlank() && days != null,
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(Res.string.action_save)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { SheetDragHandle() }) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.screenHorizontal).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun Confirm(text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

private val PER_DAY_CHOICES = listOf(1, 2, 3, 4)
