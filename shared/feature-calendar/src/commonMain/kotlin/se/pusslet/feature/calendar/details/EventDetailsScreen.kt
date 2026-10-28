package se.pusslet.feature.calendar.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.helper.HelperAnswer
import se.pusslet.core.model.AttachmentOwner
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.Role
import se.pusslet.core.party.PartyRules
import se.pusslet.feature.calendar.annotate
import se.pusslet.feature.calendar.attachments.AttachmentsRoute
import se.pusslet.feature.calendar.editor.reminderLabel
import se.pusslet.feature.calendar.handoffSegment
import se.pusslet.ui.components.AvatarStack
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.InfoPill
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.Section
import se.pusslet.ui.format.describe
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.formatTimeRange
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.HelperDimens
import se.pusslet.ui.theme.Spacing

/** What the hand-off rows can do: parents change, take and ask; a caregiver only answers (ADR-015, ADR-030). */
data class HandoffActions(
    val onTake: (HandoffKind) -> Unit = {},
    val onAssign: (HandoffKind, String?) -> Unit = { _, _ -> },
    val onRequest: (HandoffKind) -> Unit = {},
    val onAccept: (HandoffKind) -> Unit = {},
    val onDecline: (HandoffKind) -> Unit = {},
    val onChangeAnswer: (HandoffKind) -> Unit = {},
)

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
    EventDetailsScreen(
        state,
        onBack,
        onEdit,
        HandoffActions(
            onTake = viewModel::take,
            onAssign = viewModel::assign,
            onRequest = viewModel::requestHelp,
            onAccept = viewModel::accept,
            onDecline = viewModel::decline,
            onChangeAnswer = viewModel::changeAnswer,
        ),
        attachments = { o -> AttachmentsRoute(AttachmentOwner.Event, o.event.seriesId ?: o.event.id) },
        party = { o -> PartySection(o, editable = state.editable) },
    )
}

@Composable
fun EventDetailsScreen(
    state: EventDetailsState,
    onBack: () -> Unit,
    onEdit: (eventId: String, occurrenceDate: LocalDate?) -> Unit,
    actions: HandoffActions = HandoffActions(),
    /** Pictures kept with the event on this phone (ADR-049). */
    attachments: @Composable (EventOccurrence) -> Unit = {},
    /** The kalas card: OSA, present, or the family's own guests (ADR-054). */
    party: @Composable (EventOccurrence) -> Unit = {},
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
                HandoffSection(state, o, people, actions)
            }
            if (o.source == EventSource.Family && PartyRules.isParty(o.event)) party(o)
            if (o.event.tags.isNotEmpty()) {
                // Tags inform; they are not buttons (UX plan 1.6).
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    o.event.tags.forEach { tag ->
                        InfoPill(Labels.tag(tag)?.let { stringResource(it) } ?: tag.replaceFirstChar { it.uppercase() })
                    }
                }
            }
            if (o.event.packingItems.isNotEmpty()) {
                Section(stringResource(Res.string.event_packing)) {
                    o.event.packingItems.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
                }
            }
            o.event.reminders.firstOrNull()?.let {
                Text(stringResource(Res.string.event_reminder) + ": " + reminderLabel(it), style = MaterialTheme.typography.bodyMedium)
            }
            if (o.event.note.isNotBlank()) {
                Section(stringResource(Res.string.event_note)) { Text(o.event.note, style = MaterialTheme.typography.bodyLarge) }
            }
            attachments(o)
            if (o.source == EventSource.Family && !o.event.sharedWithCaregivers) {
                val caregivers = state.caregivers.map { it.name }
                Text(
                    // "Bengt ser inte den här händelsen." when the family has caregivers (UX plan Steg 3, §9).
                    if (caregivers.isEmpty()) {
                        stringResource(Res.string.event_private_hint)
                    } else {
                        stringResource(Res.string.event_private_hint_named, joinNames(caregivers))
                    },
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

/**
 * "Lämning och hämtning" (UX plan, Kalender 6): one sentence per hand-off, "Johan lämnar 08:05 · Ändra". "Jag tar det" is a
 * filled button only on the first hand-off nobody has taken, so the screen has at most one filled button. Under each
 * hand-off its latest request: a quiet line for parents ("Frågat Bengt · inget svar än", "Bengt svarade ja"), a Problem for
 * a caregiver's "Jag kan inte", and Ja/Nej for a caregiver it is asked of (UX plan Steg 3).
 */
@Composable
private fun HandoffSection(state: EventDetailsState, o: EventOccurrence, people: Map<String, FamilyPerson>, actions: HandoffActions) {
    val kinds = listOf(HandoffKind.DropOff, HandoffKind.PickUp)

    fun handoff(kind: HandoffKind) = if (kind == HandoffKind.PickUp) o.event.pickUp else o.event.dropOff

    // Planned but nobody has it (the calendar's "Inte bestämt" in red). A hand-off that was never planned stays quiet.
    fun isOpen(kind: HandoffKind) = handoff(kind)?.let { h -> h.personId?.let(people::get) == null } == true
    val firstOpen = kinds.firstOrNull(::isOpen)
    val parent = state.role == Role.Parent
    val children = joinNames(o.event.participantIds.mapNotNull(people::get).filter { it.isChild }.map { it.name })
    Section(stringResource(Res.string.event_handoffs)) {
        kinds.forEach { kind ->
            val planned = handoff(kind)
            val who = planned?.personId?.let(people::get)
            val open = isOpen(kind)
            val time = (if (kind == HandoffKind.PickUp) o.pickUpTime() else o.dropOffTime()) ?: return@forEach
            val segment = handoffSegment(kind, time, who, isMe = who != null && who.id == state.myMemberId, planned = planned != null)
            Row(Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget), verticalAlignment = Alignment.CenterVertically) {
                if (open) {
                    Icon(
                        PussletIcons.PriorityHigh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = Spacing.sm).size(20.dp),
                    )
                }
                Text(annotate(segment.text, listOf(segment)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (parent) ChangeHandoff(state.adults, who?.id) { actions.onAssign(kind, it) }
            }
            when (val request = state.requestState(kind)) {
                is RequestState.ForMe -> AnswerCard(request, kind, children, state.waitingForNetwork, actions)
                is RequestState.CannotMake -> CannotMakeLine(request, kind, children, time, parent) { actions.onTake(kind) }
                is RequestState.Asked, is RequestState.Accepted, is RequestState.Declined -> RequestPills(request)
                null -> Unit
            }
            val take = parent && (open || state.answersOpenRequest(kind))
            val canRequest = state.canRequestHelp(kind)
            if (take || canRequest) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    if (take) {
                        val label = stringResource(Res.string.card_action_take)
                        val modifier = Modifier.heightIn(min = Spacing.touchTarget)
                        if (kind == firstOpen) {
                            Button(onClick = { actions.onTake(kind) }, modifier = modifier) { Text(label) }
                        } else {
                            OutlinedButton(onClick = { actions.onTake(kind) }, modifier = modifier) { Text(label) }
                        }
                    }
                    if (canRequest) {
                        TextButton(onClick = { actions.onRequest(kind) }) { Text(stringResource(Res.string.event_request_pickup)) }
                    }
                }
            }
        }
    }
}

/** Parents: the request's state as quiet pills, "Frågat alla · inget svar än", "Bengt kan inte", "Bengt svarade ja". */
@Composable
private fun RequestPills(request: RequestState) {
    val someone = stringResource(Res.string.today_request_someone)
    val pills: List<Pair<String, Boolean>> = when (request) {
        is RequestState.Asked -> {
            val asked = if (request.toEveryone) {
                stringResource(Res.string.request_asked_all)
            } else {
                stringResource(Res.string.request_asked_member, request.to?.name ?: someone)
            }
            listOf(asked to false) + request.declinedBy.map { stringResource(Res.string.request_answer_no, it.name) to false }
        }
        is RequestState.Accepted -> listOf(stringResource(Res.string.request_answer_yes, request.by?.name ?: someone) to true)
        is RequestState.Declined -> listOf(stringResource(Res.string.request_answer_no, request.by?.name ?: someone) to false)
        is RequestState.CannotMake, is RequestState.ForMe -> emptyList()
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        pills.forEach { (text, yes) -> InfoPill(text, selected = yes) }
    }
}

/**
 * A caregiver's "Jag kan inte": for parents a Problem, "Bengt kan inte hämta Simon 15:30 · Jag tar det"; on his own phone
 * the quiet "Du har sagt att du inte kan. Väntar på svar."
 */
@Composable
private fun CannotMakeLine(
    request: RequestState.CannotMake,
    kind: HandoffKind,
    children: String,
    time: LocalTime,
    parent: Boolean,
    onTake: () -> Unit,
) {
    if (request.mine) {
        Text(
            stringResource(Res.string.helper_cannot_waiting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val from = request.from?.name ?: stringResource(Res.string.today_request_someone)
    val resource = if (kind == HandoffKind.PickUp) Res.string.card_helper_cannot_pickup else Res.string.card_helper_cannot_dropoff
    Banner(
        stringResource(resource, from, children, formatTime(time)),
        kind = BannerKind.Problem,
        actions = if (parent) ({ TextButton(onClick = onTake) { Text(stringResource(Res.string.card_action_take)) } }) else null,
    )
}

/**
 * A caregiver's answer, read-only otherwise (ADR-015): "Sara undrar" with Ja/Nej stacked at full width and at least 64 dp,
 * then what he said and "Ändra svar" until a parent's phone has written the answer in.
 */
@Composable
private fun AnswerCard(
    request: RequestState.ForMe,
    kind: HandoffKind,
    children: String,
    waitingForNetwork: Boolean,
    actions: HandoffActions,
) {
    val from = request.from?.name ?: stringResource(Res.string.today_request_someone)
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                stringResource(Res.string.helper_request_from, from),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            when (request.answer) {
                null -> {
                    val yes = if (kind == HandoffKind.PickUp) Res.string.helper_yes_pickup else Res.string.helper_yes_dropoff
                    Button(
                        onClick = { actions.onAccept(kind) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = HelperDimens.button),
                    ) { Text(stringResource(yes, children)) }
                    OutlinedButton(
                        onClick = { actions.onDecline(kind) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = HelperDimens.button),
                    ) { Text(stringResource(Res.string.helper_no)) }
                }
                else -> {
                    val said = if (request.answer == HelperAnswer.Yes) Res.string.helper_answered_yes else Res.string.helper_answered_no
                    Text(stringResource(said, from), style = MaterialTheme.typography.bodyLarge)
                    if (waitingForNetwork) {
                        Text(
                            stringResource(Res.string.helper_offline_pending),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (request.canChangeAnswer) {
                        TextButton(onClick = { actions.onChangeAnswer(kind) }, modifier = Modifier.heightIn(min = HelperDimens.button)) {
                            Text(stringResource(Res.string.helper_change_answer))
                        }
                    }
                }
            }
        }
    }
}

/** "Ändra": a menu with "Inte bestämt" and the adults. */
@Composable
private fun ChangeHandoff(adults: List<FamilyPerson>, selected: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(Res.string.action_change)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.event_handoff_none)) }, onClick = {
                open = false
                if (selected != null) onPick(null)
            })
            adults.forEach { p ->
                DropdownMenuItem(text = { Text(p.name) }, onClick = {
                    open = false
                    if (p.id != selected) onPick(p.id)
                })
            }
        }
    }
}
