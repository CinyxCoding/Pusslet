package se.pusslet.feature.tasks.kid

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.tasks.KidChore
import se.pusslet.core.tasks.KidChoreStatus
import se.pusslet.core.tasks.KidChores
import se.pusslet.core.tasks.StarWeek
import se.pusslet.core.time.isoWeek
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.usecase.AllowanceRepository
import se.pusslet.data.usecase.KidChoreActions
import se.pusslet.ui.components.ListRow
import se.pusslet.ui.components.PersonAvatar
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.relativeDay
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.kid_allowance
import se.pusslet.ui.resources.kid_chores
import se.pusslet.ui.resources.kid_done
import se.pusslet.ui.resources.kid_exit
import se.pusslet.ui.resources.kid_exit_hint
import se.pusslet.ui.resources.kid_hello
import se.pusslet.ui.resources.kid_mode
import se.pusslet.ui.resources.kid_mode_pick
import se.pusslet.ui.resources.kid_no_chores
import se.pusslet.ui.resources.kid_nothing_today
import se.pusslet.ui.resources.kid_picks_up
import se.pusslet.ui.resources.kid_stars_week
import se.pusslet.ui.resources.kid_today
import se.pusslet.ui.resources.kid_undo
import se.pusslet.ui.resources.kid_waiting
import se.pusslet.ui.resources.kid_well_done
import se.pusslet.ui.resources.stars_count
import se.pusslet.ui.theme.Spacing
import kotlin.time.Clock

/** One line of the child's day: "15:30 Simskola" or "15:00 Mamma hämtar". */
data class KidDayItem(val time: LocalTime?, val title: String, val pickUp: FamilyPerson? = null)

data class KidModeUiState(
    val loading: Boolean = true,
    val child: FamilyPerson? = null,
    val today: LocalDate? = null,
    val day: List<KidDayItem> = emptyList(),
    val chores: List<KidChore> = emptyList(),
    val stars: StarWeek? = null,
)

/**
 * Barnläge (BRIEF §4.1, §4.7): a simple screen for one child on a parent's phone or a family tablet – my day, my chores,
 * my stars. "Klar!" waits for a parent; "köpt" on wishlists is never shown here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KidModeViewModel(
    private val tasks: TaskRepository,
    family: FamilyRepository,
    calendar: CalendarRepository,
    allowances: AllowanceRepository,
    private val actions: KidChoreActions,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val childId = MutableStateFlow<String?>(null)
    private val today = clock.todayIn(zone)

    val state: StateFlow<KidModeUiState> = combine(
        childId.filterNotNull(),
        tasks.tasks,
        family.people,
        calendar.occurrences(today, today, zone),
        allowances.allowances,
    ) { id, all, people, occurrences, money ->
        val byId = people.associateBy { it.id }
        KidModeUiState(
            loading = false,
            child = byId[id],
            today = today,
            day = dayOf(id, occurrences, byId),
            chores = KidChores.forChild(id, all, today),
            stars = KidChores.week(id, all, today.isoWeek, money.firstOrNull { it.childId == id }, zone),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), KidModeUiState())

    fun load(id: String) {
        childId.value = id
    }

    fun toggle(chore: KidChore) {
        val id = childId.value ?: return
        viewModelScope.launch {
            when (chore.status) {
                KidChoreStatus.Todo -> actions.childDone(chore.occurrence, id)
                KidChoreStatus.Waiting -> actions.childUndo(chore.occurrence)
                KidChoreStatus.Approved -> Unit
            }
        }
    }

    private fun dayOf(id: String, occurrences: List<EventOccurrence>, people: Map<String, FamilyPerson>): List<KidDayItem> =
        occurrences.filter { id in it.event.participantIds && it.start.date == today }
            .sortedWith(compareBy({ !it.allDay }, { it.start }))
            .flatMap { o ->
                val pickUp = o.event.pickUp?.personId?.let { people[it] }
                listOfNotNull(
                    KidDayItem(if (o.allDay) null else o.start.time, o.event.title),
                    pickUp?.let { p -> o.pickUpTime()?.let { KidDayItem(it, o.event.title, p) } },
                )
            }
            .sortedWith(compareBy({ it.time != null }, { it.time }))

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun KidModeRoute(childId: String, onExit: () -> Unit, viewModel: KidModeViewModel = koinViewModel()) {
    LaunchedEffect(childId) { viewModel.load(childId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    KidModeScreen(state, viewModel::toggle, onExit)
}

@Composable
fun KidModeScreen(state: KidModeUiState, onToggle: (KidChore) -> Unit, onExit: () -> Unit) {
    val child = state.child ?: return
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "hello") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    PersonAvatar(child, size = AVATAR.dp)
                    Text(
                        stringResource(Res.string.kid_hello, child.name),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                }
            }
            state.stars?.let { s -> item(key = "stars") { StarsCard(s) } }
            item(key = "today") { Heading(stringResource(Res.string.kid_today)) }
            if (state.day.isEmpty()) {
                item(key = "quiet") { Text(stringResource(Res.string.kid_nothing_today), style = MaterialTheme.typography.bodyLarge) }
            }
            items(state.day, key = { "${it.time}-${it.title}-${it.pickUp?.id}" }) { DayRow(it) }
            item(key = "chores") { Heading(stringResource(Res.string.kid_chores)) }
            if (state.chores.isEmpty()) {
                item(key = "no-chores") { Text(stringResource(Res.string.kid_no_chores), style = MaterialTheme.typography.bodyLarge) }
            }
            items(state.chores, key = { it.occurrence.task.id + "@" + it.occurrence.key }) { chore ->
                ChoreCard(chore, state.today, onToggle)
            }
            item(key = "exit") { ExitButton(onExit) }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = Spacing.sm).semantics { heading() })
}

@Composable
private fun StarsCard(s: StarWeek) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(Res.string.kid_stars_week, s.approved), style = MaterialTheme.typography.titleLarge)
            if (s.waiting > 0) Text(stringResource(Res.string.kid_waiting, s.waiting), style = MaterialTheme.typography.bodyLarge)
            s.amount?.let { Text(stringResource(Res.string.kid_allowance, it), style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun DayRow(item: KidDayItem) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(item.time?.let { formatTime(it) } ?: "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(TIME_WEIGHT))
        Text(
            item.pickUp?.let { stringResource(Res.string.kid_picks_up, it.name) } ?: item.title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ChoreCard(chore: KidChore, today: LocalDate?, onToggle: (KidChore) -> Unit) {
    val o = chore.occurrence
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(o.task.title, style = MaterialTheme.typography.titleMedium)
                val sub = listOfNotNull(
                    o.date?.takeIf { it != today }?.let { d -> today?.let { relativeDay(d, it) } },
                    chore.stars.takeIf { it > 0 }?.let { stringResource(Res.string.stars_count, it) },
                ).joinToString("  ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium)
            }
            when (chore.status) {
                KidChoreStatus.Todo -> Button(onClick = {
                    onToggle(chore)
                }, modifier = Modifier.heightIn(min = Spacing.primaryTouchTarget)) {
                    Text(stringResource(Res.string.kid_done))
                }
                KidChoreStatus.Waiting -> OutlinedButton(onClick = { onToggle(chore) }) { Text(stringResource(Res.string.kid_undo)) }
                KidChoreStatus.Approved -> Text(stringResource(Res.string.kid_well_done), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Held down for a moment (or a long press with TalkBack), so a child does not leave by accident. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExitButton(onExit: () -> Unit) {
    val label = stringResource(Res.string.kid_exit)
    Column(Modifier.fillMaxWidth().padding(top = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .heightIn(min = Spacing.touchTarget)
                .combinedClickable(onClick = {}, onLongClick = onExit, onLongClickLabel = label)
                .semantics {
                    onLongClick(label) {
                        onExit()
                        true
                    }
                },
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(Spacing.md))
        }
        Text(
            stringResource(Res.string.kid_exit_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private const val AVATAR = 56
private const val TIME_WEIGHT = 0.25f

class KidPickerViewModel(family: FamilyRepository) : ViewModel() {
    val children: StateFlow<List<FamilyPerson>> = family.people
        .map { people -> people.filter { it.isChild } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** "Vem ska använda telefonen?" before barnläge. */
@Composable
fun KidPickerRoute(onBack: () -> Unit, onPick: (String) -> Unit, viewModel: KidPickerViewModel = koinViewModel()) {
    val children by viewModel.children.collectAsStateWithLifecycle()
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.kid_mode), onBack = onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "q") { Text(stringResource(Res.string.kid_mode_pick), style = MaterialTheme.typography.titleMedium) }
            items(children, key = { it.id }) { c ->
                ListRow(
                    title = c.name,
                    leading = { PersonAvatar(c, size = 40.dp) },
                    modifier = Modifier.clickable { onPick(c.id) },
                )
            }
        }
    }
}
