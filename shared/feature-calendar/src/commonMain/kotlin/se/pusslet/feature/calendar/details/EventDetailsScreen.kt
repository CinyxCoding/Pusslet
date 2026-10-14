package se.pusslet.feature.calendar.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.model.Role
import se.pusslet.core.time.plusDays
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.HandoffAssigner
import se.pusslet.feature.calendar.editor.PersonPicker
import se.pusslet.feature.calendar.editor.reminderLabel
import se.pusslet.ui.components.AvatarStack
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.InfoPill
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.format.describe
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.formatTimeRange
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import kotlin.time.Clock

data class EventDetailsState(
    val loading: Boolean = true,
    val occurrence: EventOccurrence? = null,
    val people: List<FamilyPerson> = emptyList(),
    val myMemberId: String? = null,
    val role: Role = Role.Parent,
    val requests: List<HandoffRequest> = emptyList(),
) {
    val editable: Boolean get() = occurrence?.source == EventSource.Family && role == Role.Parent
    val adults: List<FamilyPerson> get() = people.filter { !it.isChild }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailsViewModel(
    private val calendar: CalendarRepository,
    family: FamilyRepository,
    private val assigner: HandoffAssigner,
    private val sessions: SessionProvider,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val target = MutableStateFlow<Pair<String, LocalDate>?>(null)
    private val newId: () -> String = family::newId

    private val occurrence = target.filterNotNull().flatMapLatest { (id, date) ->
        // Overrides may move an occurrence a few days, so look around the original date.
        calendar.occurrences(date.plusDays(-SEARCH_DAYS), date.plusDays(SEARCH_DAYS), zone).combine(target) { list, _ ->
            list.firstOrNull { it.event.id == id && it.occurrenceDate == date }
                ?: list.firstOrNull { it.event.seriesId == id && it.occurrenceDate == date }
                ?: list.filter { it.event.id == id }.minByOrNull { kotlin.math.abs(it.occurrenceDate.toEpochDays() - date.toEpochDays()) }
        }
    }

    val state: StateFlow<EventDetailsState> = combine(occurrence, family.people, sessions.session, calendar.handoffRequests) {
        o,
        people,
        session,
        requests,
        ->
        EventDetailsState(
            loading = false,
            occurrence = o,
            people = people,
            myMemberId = session.memberId,
            role = session.role,
            requests = requests.filter {
                o != null && it.eventId == o.event.id && it.occurrenceDate == o.occurrenceDate &&
                    it.status == RequestStatus.Pending
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventDetailsState())

    fun load(eventId: String, occurrenceDate: LocalDate) {
        target.value = eventId to occurrenceDate
    }

    fun assign(kind: HandoffKind, personId: String?) {
        val o = state.value.occurrence ?: return
        viewModelScope.launch { assigner.assign(o, kind, personId) }
    }

    fun take(kind: HandoffKind) {
        val me = sessions.session.value.memberId ?: return
        val o = state.value.occurrence ?: return
        viewModelScope.launch {
            assigner.assign(o, kind, me)
            // Taking it also answers any open request for the same hand-off.
            state.value.requests.filter { it.kind == kind }.forEach {
                calendar.saveHandoffRequest(it.copy(status = RequestStatus.Accepted, respondedBy = me, respondedAt = clock.now()))
            }
        }
    }

    fun requestHelp(kind: HandoffKind) {
        val me = sessions.session.value.memberId ?: return
        val o = state.value.occurrence ?: return
        if (state.value.requests.any { it.kind == kind }) return
        viewModelScope.launch {
            calendar.saveHandoffRequest(
                HandoffRequest(
                    id = newId(),
                    eventId = o.event.id,
                    occurrenceDate = o.occurrenceDate,
                    kind = kind,
                    fromMemberId = me,
                    createdAt = clock.now(),
                ),
            )
        }
    }

    private companion object {
        const val SEARCH_DAYS = 14
    }
}

@Composable
fun EventDetailsRoute(
    eventId: String,
    occurrenceDate: LocalDate,
    onBack: () -> Unit,
    onEdit: (eventId: String, occurrenceDate: LocalDate?) -> Unit,
    viewModel: EventDetailsViewModel = koinViewModel(),
) {
    LaunchedEffect(eventId, occurrenceDate) { viewModel.load(eventId, occurrenceDate) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Leave when the event disappears (deleted here or on another device).
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(state.occurrence, state.loading) {
        if (state.occurrence != null) {
            seen = true
        } else if (seen && !state.loading) {
            onBack()
        }
    }
    EventDetailsScreen(state, onBack, onEdit, viewModel::take, viewModel::assign, viewModel::requestHelp)
}

@Composable
fun EventDetailsScreen(
    state: EventDetailsState,
    onBack: () -> Unit,
    onEdit: (eventId: String, occurrenceDate: LocalDate?) -> Unit,
    onTake: (HandoffKind) -> Unit,
    onAssign: (HandoffKind, String?) -> Unit,
    onRequest: (HandoffKind) -> Unit,
) {
    val o = state.occurrence
    var askScope by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            PussletTopBar(
                // The title is the headline below; the bar only carries navigation and actions.
                title = "",
                onBack = onBack,
                actions = {
                    if (o != null && state.editable) {
                        IconButton(onClick = { if (o.event.isRecurring) askScope = true else onEdit(o.event.id, null) }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(Res.string.action_edit))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (o == null) {
            if (state.loading) {
                LoadingBox(
                    Modifier.padding(padding),
                )
            } else {
                Text(stringResource(Res.string.empty_generic), Modifier.padding(padding).padding(Spacing.screenHorizontal))
            }
            return@Scaffold
        }
        val people = state.people.associateBy { it.id }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(o.event.title, style = MaterialTheme.typography.headlineSmall)
            val whenText = buildString {
                append(formatDateLong(o.start.date).replaceFirstChar { it.uppercase() })
                if (!o.allDay) append(" · ").append(formatTimeRange(o.start.time, o.end.time))
                if (o.end.date > o.start.date && !o.allDay) append(" – ").append(formatDateLong(o.end.date))
            }
            Text(whenText, style = MaterialTheme.typography.titleMedium)
            if (o.event.isRecurring || o.event.isOverride) {
                Text(
                    describe(o.event.rrule),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (o.event.location.isNotBlank()) Text(o.event.location, style = MaterialTheme.typography.bodyLarge)
            when (o.source) {
                EventSource.Subscription -> Banner(
                    stringResource(Res.string.event_source_subscription, o.sourceName.orEmpty()) + "\n" +
                        stringResource(Res.string.event_read_only),
                )
                EventSource.Device -> Banner(
                    stringResource(Res.string.event_source_device) + "\n" + stringResource(Res.string.event_read_only),
                )
                EventSource.Holiday -> Banner(stringResource(Res.string.event_source_holiday))
                EventSource.Family -> Unit
            }
            val participants = o.event.participantIds.mapNotNull { people[it] }
            if (participants.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    AvatarStack(participants, size = 32.dp)
                    Text(joinNames(participants.map { it.name }), style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (o.source == EventSource.Family && !o.allDay && participants.any { it.isChild }) {
                HandoffCard(state, o, people, onTake, onAssign, onRequest)
            }
            if (o.event.tags.isNotEmpty()) {
                // Tags inform; they are not buttons (UX plan 1.6).
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    o.event.tags.forEach { tag ->
                        InfoPill(Labels.tag(tag)?.let { stringResource(it) } ?: tag.replaceFirstChar { it.uppercase() })
                    }
                }
            }
            if (o.event.packingItems.isNotEmpty()) {
                SectionCard(stringResource(Res.string.event_packing)) {
                    o.event.packingItems.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
                }
            }
            o.event.reminders.firstOrNull()?.let {
                Text(stringResource(Res.string.event_reminder) + ": " + reminderLabel(it), style = MaterialTheme.typography.bodyMedium)
            }
            if (o.event.note.isNotBlank()) {
                SectionCard(stringResource(Res.string.event_note)) { Text(o.event.note, style = MaterialTheme.typography.bodyLarge) }
            }
            if (o.source == EventSource.Family && !o.event.sharedWithCaregivers) {
                Text(
                    stringResource(Res.string.event_private_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (askScope && o != null) {
        AlertDialog(
            onDismissRequest = { askScope = false },
            text = { Text(stringResource(Res.string.event_edit_scope)) },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        askScope = false
                        onEdit(o.event.id, o.occurrenceDate)
                    }) { Text(stringResource(Res.string.event_delete_occurrence)) }
                    TextButton(onClick = {
                        askScope = false
                        onEdit(o.event.id, null)
                    }) { Text(stringResource(Res.string.event_delete_series)) }
                }
            },
            dismissButton = { TextButton(onClick = { askScope = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@Composable
private fun HandoffCard(
    state: EventDetailsState,
    o: EventOccurrence,
    people: Map<String, FamilyPerson>,
    onTake: (HandoffKind) -> Unit,
    onAssign: (HandoffKind, String?) -> Unit,
    onRequest: (HandoffKind) -> Unit,
) {
    SectionCard(stringResource(Res.string.event_handoffs)) {
        listOf(HandoffKind.DropOff, HandoffKind.PickUp).forEach { kind ->
            val handoff = if (kind == HandoffKind.PickUp) o.event.pickUp else o.event.dropOff
            val time = if (kind == HandoffKind.PickUp) o.pickUpTime() else o.dropOffTime()
            val label = stringResource(if (kind == HandoffKind.PickUp) Res.string.event_pickup else Res.string.event_dropoff) +
                (time?.let { " " + formatTime(it) } ?: "")
            val who = handoff?.personId
            if (state.role == Role.Parent) {
                PersonPicker(label, state.adults, who) { onAssign(kind, it) }
            } else {
                Text(
                    label + ": " + (
                        who?.let {
                            people[it]?.name
                        } ?: stringResource(Res.string.today_handoff_nobody)
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            val pending = state.requests.firstOrNull { it.kind == kind }
            if (pending != null) {
                val from = people[pending.fromMemberId]?.name.orEmpty()
                Banner(stringResource(Res.string.event_request_from, from), kind = BannerKind.Warning)
            }
            if (state.role == Role.Parent) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (who != state.myMemberId) {
                        Button(onClick = {
                            onTake(kind)
                        }, modifier = Modifier.heightIn(min = Spacing.touchTarget)) { Text(stringResource(Res.string.card_action_take)) }
                    }
                    if (pending == null && kind == HandoffKind.PickUp) {
                        OutlinedButton(onClick = {
                            onRequest(kind)
                        }, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
                            Text(stringResource(Res.string.event_request_pickup))
                        }
                    }
                }
            }
        }
    }
}
