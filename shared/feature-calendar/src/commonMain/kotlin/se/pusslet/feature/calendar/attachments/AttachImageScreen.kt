package se.pusslet.feature.calendar.attachments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.AttachmentOwner
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.time.plusDays
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.AttachmentRepository
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.ui.components.AvatarStack
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.relativeDay
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.action_save
import se.pusslet.ui.resources.all_day
import se.pusslet.ui.resources.attach_event_when
import se.pusslet.ui.resources.attach_no_events
import se.pusslet.ui.resources.attach_note_default
import se.pusslet.ui.resources.attach_note_hint
import se.pusslet.ui.resources.attach_note_title
import se.pusslet.ui.resources.attach_title
import se.pusslet.ui.resources.attach_to_event
import se.pusslet.ui.resources.attachments_only_here
import se.pusslet.ui.theme.Spacing
import kotlin.time.Clock

data class AttachImageState(
    val loading: Boolean = true,
    /** The next occurrence of every family event in the coming weeks (a daily school day is listed once). */
    val events: List<EventOccurrence> = emptyList(),
    val people: Map<String, FamilyPerson> = emptyMap(),
    val today: LocalDate? = null,
)

sealed interface AttachOutcome {
    data class ToEvent(val eventId: String, val occurrenceDate: LocalDate) : AttachOutcome

    data class ToNote(val taskId: String) : AttachOutcome
}

/** A picture shared to Pusslet: attach it to an event or keep it as a note. Leaving without choosing deletes it. */
class AttachImageViewModel(
    calendar: CalendarRepository,
    family: FamilyRepository,
    private val tasks: TaskRepository,
    private val attachments: AttachmentRepository,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val zone: TimeZone,
    /** Outlives this screen, so an unused picture is still deleted after the screen is gone. */
    private val appScope: CoroutineScope,
) : ViewModel() {
    private var fileName: String? = null
    private var kept = false
    private val outcomeChannel = Channel<AttachOutcome>(Channel.BUFFERED)
    val outcomes: Flow<AttachOutcome> = outcomeChannel.receiveAsFlow()

    private val today = clock.todayIn(zone)

    val state: StateFlow<AttachImageState> = combine(
        calendar.occurrences(today, today.plusDays(DAYS_AHEAD), zone),
        family.people,
    ) { list, people ->
        val upcoming = list.filter { it.source == EventSource.Family }
        AttachImageState(
            loading = false,
            events = upcoming.groupBy { seriesOf(it) }.values.map { o -> o.minBy { it.start } }.sortedBy { it.start },
            people = people.associateBy { it.id },
            today = today,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AttachImageState())

    fun start(fileName: String) {
        this.fileName = fileName
    }

    fun attachToEvent(occurrence: EventOccurrence) {
        val file = fileName ?: return
        kept = true
        viewModelScope.launch {
            val eventId = seriesOf(occurrence)
            attachments.attach(AttachmentOwner.Event, eventId, file)
            outcomeChannel.send(AttachOutcome.ToEvent(eventId, occurrence.occurrenceDate))
        }
    }

    /** A to-do without a date ("Veckobrev v. 41") that carries the picture. */
    fun saveAsNote(title: String) {
        val file = fileName ?: return
        kept = true
        viewModelScope.launch {
            val id = ids.newId()
            val me = sessions.session.value.memberId
            tasks.save(FamilyTask(id, title.trim(), createdBy = me, createdAt = clock.now()))
            attachments.attach(AttachmentOwner.Task, id, file)
            outcomeChannel.send(AttachOutcome.ToNote(id))
        }
    }

    override fun onCleared() {
        val file = fileName
        if (!kept && file != null) appScope.launch { attachments.discard(file) }
    }

    private fun seriesOf(o: EventOccurrence): String = o.event.seriesId ?: o.event.id

    private companion object {
        const val DAYS_AHEAD = 30
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun AttachImageRoute(
    fileName: String,
    onBack: () -> Unit,
    onDone: (AttachOutcome) -> Unit,
    viewModel: AttachImageViewModel = koinViewModel(),
) {
    LaunchedEffect(fileName) { viewModel.start(fileName) }
    LaunchedEffect(viewModel) { viewModel.outcomes.collect(onDone) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    AttachImageScreen(fileName, state, onBack, viewModel::attachToEvent, viewModel::saveAsNote)
}

@Composable
fun AttachImageScreen(
    fileName: String,
    state: AttachImageState,
    onBack: () -> Unit,
    onAttach: (EventOccurrence) -> Unit,
    onSaveNote: (String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    val defaultTitle = stringResource(Res.string.attach_note_default)
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.attach_title), onBack = onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                // The full picture (at most 2048 px): the thumbnail is too small to fill the width.
                AttachmentImage(
                    fileName,
                    thumbnail = false,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(PREVIEW.dp).clip(MaterialTheme.shapes.large),
                )
            }
            item {
                SectionCard(stringResource(Res.string.attach_note_title)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = { Text(stringResource(Res.string.attach_note_hint)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { onSaveNote(title.ifBlank { defaultTitle }) }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(Res.string.action_save))
                    }
                }
            }
            item { Text(stringResource(Res.string.attach_to_event), style = MaterialTheme.typography.titleMedium) }
            if (!state.loading && state.events.isEmpty()) {
                item { Text(stringResource(Res.string.attach_no_events), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.events, key = { it.key }) { o -> EventRow(o, state, onAttach) }
            item {
                Text(
                    stringResource(Res.string.attachments_only_here),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EventRow(o: EventOccurrence, state: AttachImageState, onAttach: (EventOccurrence) -> Unit) {
    val today = state.today ?: return
    val day = relativeDay(o.start.date, today)
    val time = if (o.allDay) stringResource(Res.string.all_day) else formatTime(o.start.time)
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onAttach(o) }.padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(o.event.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(Res.string.attach_event_when, day, time),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val people = o.event.participantIds.mapNotNull { state.people[it] }
        if (people.isNotEmpty()) AvatarStack(people)
    }
}

private const val PREVIEW = 200
