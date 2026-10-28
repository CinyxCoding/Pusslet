package se.pusslet.feature.calendar.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.Guest
import se.pusslet.core.model.PartyDetails
import se.pusslet.core.model.Rsvp
import se.pusslet.core.party.PartyRules
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.ui.components.DatePickerSheet
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.SwitchRow
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.action_add
import se.pusslet.ui.resources.party_gift
import se.pusslet.ui.resources.party_gift_bought
import se.pusslet.ui.resources.party_gift_budget
import se.pusslet.ui.resources.party_gift_idea
import se.pusslet.ui.resources.party_gift_not_bought
import se.pusslet.ui.resources.party_guest_name
import se.pusslet.ui.resources.party_guest_remove
import se.pusslet.ui.resources.party_guest_status
import se.pusslet.ui.resources.party_guests
import se.pusslet.ui.resources.party_guests_summary
import se.pusslet.ui.resources.party_invitation
import se.pusslet.ui.resources.party_invitation_hint
import se.pusslet.ui.resources.party_invitation_share
import se.pusslet.ui.resources.party_invite_rsvp
import se.pusslet.ui.resources.party_invite_rsvp_plain
import se.pusslet.ui.resources.party_invite_title
import se.pusslet.ui.resources.party_invite_when
import se.pusslet.ui.resources.party_invite_where
import se.pusslet.ui.resources.party_own
import se.pusslet.ui.resources.party_rsvp
import se.pusslet.ui.resources.party_rsvp_by
import se.pusslet.ui.resources.party_rsvp_by_add
import se.pusslet.ui.resources.party_rsvp_by_clear
import se.pusslet.ui.resources.party_title
import se.pusslet.ui.resources.rsvp_no
import se.pusslet.ui.resources.rsvp_pending
import se.pusslet.ui.resources.rsvp_yes
import se.pusslet.ui.resources.search_line
import se.pusslet.ui.theme.Spacing
import kotlin.time.Clock

data class PartyUiState(
    val loaded: Boolean = false,
    val details: PartyDetails = PartyDetails(),
    val myName: String = "",
    val today: LocalDate? = null,
)

/**
 * The kalas card on an event: OSA and present for a party a child is invited to, or the guest list and invitation for
 * the family's own. Edits are written to the event (text fields shortly after typing stops); ticking "köpt" also
 * ticks off the present to-do quick add made.
 */
class PartyViewModel(
    private val calendar: CalendarRepository,
    private val tasks: TaskRepository,
    private val family: FamilyRepository,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val _state = MutableStateFlow(PartyUiState())
    val state: StateFlow<PartyUiState> = _state.asStateFlow()
    private var eventId: String? = null
    private var pendingSave: Job? = null

    fun load(id: String) {
        if (eventId == id) return
        eventId = id
        viewModelScope.launch {
            val event = calendar.event(id) ?: return@launch
            val me = sessions.session.value.memberId
            val name = family.people.first().firstOrNull { it.id == me }?.name.orEmpty()
            _state.value = PartyUiState(true, PartyRules.details(event), name, clock.todayIn(zone))
        }
    }

    /** Toggles and choices are saved at once; typing is saved half a second after the last key. */
    fun update(typing: Boolean = false, transform: (PartyDetails) -> PartyDetails) {
        _state.update { it.copy(details = transform(it.details)) }
        pendingSave?.cancel()
        pendingSave = viewModelScope.launch {
            if (typing) delay(TYPING_DELAY_MS)
            save()
        }
    }

    fun setBought(bought: Boolean) {
        update { it.copy(gift = it.gift.copy(bought = bought, boughtBy = if (bought) sessions.session.value.memberId else null)) }
        val id = eventId ?: return
        if (!bought) return
        viewModelScope.launch {
            tasks.tasks.first().filter { it.linkedEventId == id && !it.done }.forEach { t ->
                tasks.save(t.copy(done = true, doneAt = clock.now(), doneBy = sessions.session.value.memberId))
            }
        }
    }

    fun addGuest(name: String) {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return
        update { it.copy(guests = it.guests + Guest(ids.newId(), clean)) }
    }

    /** Tapping a guest's answer goes round: inte svarat → kommer → kommer inte. */
    fun cycleGuest(guestId: String) = update { p ->
        p.copy(
            guests = p.guests.map { g ->
                if (g.id != guestId) {
                    g
                } else {
                    g.copy(
                        rsvp = when (g.rsvp) {
                            Rsvp.Pending -> Rsvp.Yes
                            Rsvp.Yes -> Rsvp.No
                            Rsvp.No -> Rsvp.Pending
                        },
                    )
                }
            },
        )
    }

    fun removeGuest(guestId: String) = update { p -> p.copy(guests = p.guests.filter { it.id != guestId }) }

    private suspend fun save() {
        val id = eventId ?: return
        val event = calendar.event(id) ?: return
        calendar.saveEvent(event.copy(party = _state.value.details))
    }

    private companion object {
        const val TYPING_DELAY_MS = 500L
    }
}

@Composable
fun PartySection(occurrence: EventOccurrence, editable: Boolean) {
    val id = occurrence.event.seriesId ?: occurrence.event.id
    val viewModel: PartyViewModel = koinViewModel(key = "party:$id")
    LaunchedEffect(id) { viewModel.load(id) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.loaded) return
    SectionCard(stringResource(Res.string.party_title)) {
        if (!editable) {
            ReadOnlyParty(state.details)
            return@SectionCard
        }
        SwitchRow(stringResource(Res.string.party_own), state.details.own, { own -> viewModel.update { it.copy(own = own) } })
        if (state.details.own) {
            OwnParty(occurrence, state, viewModel)
        } else {
            Invited(state, viewModel)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Invited(state: PartyUiState, viewModel: PartyViewModel) {
    val p = state.details
    var pickDate by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(Res.string.party_rsvp), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        listOf(
            Rsvp.Yes to Res.string.rsvp_yes,
            Rsvp.No to Res.string.rsvp_no,
            Rsvp.Pending to Res.string.rsvp_pending,
        ).forEach { (r, label) ->
            FilterChip(selected = p.rsvp == r, onClick = {
                viewModel.update { it.copy(rsvp = r) }
            }, label = { Text(stringResource(label)) })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { pickDate = true }) {
            Text(
                p.rsvpBy?.let { stringResource(Res.string.party_rsvp_by, formatDateLong(it)) }
                    ?: stringResource(Res.string.party_rsvp_by_add),
            )
        }
        if (p.rsvpBy != null) {
            TextButton(onClick = { viewModel.update { it.copy(rsvpBy = null) } }) { Text(stringResource(Res.string.party_rsvp_by_clear)) }
        }
    }
    if (pickDate) {
        DatePickerSheet(p.rsvpBy ?: state.today ?: return, onDismiss = { pickDate = false }, onPicked = { d ->
            viewModel.update { it.copy(rsvpBy = d) }
        })
    }
    if (p.rsvp != Rsvp.No) {
        Text(stringResource(Res.string.party_gift), style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = p.gift.idea,
            onValueChange = { v -> viewModel.update(typing = true) { it.copy(gift = it.gift.copy(idea = v.take(MAX_TEXT))) } },
            label = { Text(stringResource(Res.string.party_gift_idea)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = p.gift.budget?.toString().orEmpty(),
            onValueChange = { v ->
                val budget = v.filter(Char::isDigit).take(MAX_BUDGET_DIGITS).toIntOrNull()
                viewModel.update(typing = true) { it.copy(gift = it.gift.copy(budget = budget)) }
            },
            label = { Text(stringResource(Res.string.party_gift_budget)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).clickable(role = Role.Checkbox) {
                viewModel.setBought(!p.gift.bought)
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = p.gift.bought, onCheckedChange = null)
            Text(stringResource(Res.string.party_gift_bought), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OwnParty(occurrence: EventOccurrence, state: PartyUiState, viewModel: PartyViewModel) {
    val p = state.details
    var guest by rememberSaveable { mutableStateOf("") }
    val platform = LocalPlatformActions.current
    Text(stringResource(Res.string.party_guests), style = MaterialTheme.typography.labelLarge)
    if (p.guests.isNotEmpty()) {
        Text(
            stringResource(
                Res.string.party_guests_summary,
                p.guests.count { it.rsvp == Rsvp.Yes },
                p.guests.count { it.rsvp == Rsvp.No },
                p.guests.count { it.rsvp == Rsvp.Pending },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    p.guests.forEach { g ->
        val answer = stringResource(rsvpLabel(g.rsvp))
        val change = stringResource(Res.string.party_guest_status, g.name)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(g.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            SuggestionChip(
                onClick = { viewModel.cycleGuest(g.id) },
                label = { Text(answer) },
                modifier = Modifier.semantics { contentDescription = change },
            )
            IconButton(onClick = { viewModel.removeGuest(g.id) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.party_guest_remove, g.name))
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedTextField(
            value = guest,
            onValueChange = { guest = it },
            label = { Text(stringResource(Res.string.party_guest_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                viewModel.addGuest(guest)
                guest = ""
            }),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            viewModel.addGuest(guest)
            guest = ""
        }, enabled = guest.isNotBlank()) { Text(stringResource(Res.string.action_add)) }
    }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { pickDate = true }) {
        Text(p.rsvpBy?.let { stringResource(Res.string.party_rsvp_by, formatDateLong(it)) } ?: stringResource(Res.string.party_rsvp_by_add))
    }
    if (pickDate) {
        DatePickerSheet(p.rsvpBy ?: state.today ?: return, onDismiss = { pickDate = false }, onPicked = { d ->
            viewModel.update { it.copy(rsvpBy = d) }
        })
    }
    OutlinedTextField(
        value = p.invitation,
        onValueChange = { v -> viewModel.update(typing = true) { it.copy(invitation = v.take(MAX_INVITATION)) } },
        label = { Text(stringResource(Res.string.party_invitation)) },
        placeholder = { Text(stringResource(Res.string.party_invitation_hint)) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    val invitation = invitationText(occurrence, p, state.myName)
    OutlinedButton(onClick = { platform.shareText(invitation, occurrence.event.title) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.party_invitation_share))
    }
}

/** "Välkommen på Ellas kalas! Lördag 10 oktober kl 14:00. Plats: … OSA senast … till Sara." */
@Composable
private fun invitationText(o: EventOccurrence, p: PartyDetails, myName: String): String {
    val e = o.event
    val whenText = if (e.allDay) {
        formatDateLong(
            o.start.date,
        )
    } else {
        stringResource(Res.string.party_invite_when, formatDateLong(o.start.date), formatTime(o.start.time))
    }
    return listOfNotNull(
        stringResource(Res.string.party_invite_title, e.title),
        whenText.replaceFirstChar { it.uppercase() },
        e.location.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.party_invite_where, it) },
        rsvp(p.rsvpBy, myName),
        p.invitation.trim().takeIf { it.isNotEmpty() },
    ).joinToString("\n")
}

@Composable
private fun rsvp(by: LocalDate?, myName: String): String? = when {
    by != null -> stringResource(Res.string.party_invite_rsvp, formatDateLong(by), myName)
    myName.isNotBlank() -> stringResource(Res.string.party_invite_rsvp_plain, myName)
    else -> null
}

/** What helpers (morfar) see: the answer and whether the present is sorted. */
@Composable
private fun ReadOnlyParty(p: PartyDetails) {
    val text = if (p.own) {
        stringResource(
            Res.string.party_guests_summary,
            p.guests.count { it.rsvp == Rsvp.Yes },
            p.guests.count { it.rsvp == Rsvp.No },
            p.guests.count { it.rsvp == Rsvp.Pending },
        )
    } else {
        stringResource(
            Res.string.search_line,
            stringResource(rsvpLabel(p.rsvp)),
            stringResource(if (p.gift.bought) Res.string.party_gift_bought else Res.string.party_gift_not_bought),
        )
    }
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

private fun rsvpLabel(r: Rsvp) = when (r) {
    Rsvp.Pending -> Res.string.rsvp_pending
    Rsvp.Yes -> Res.string.rsvp_yes
    Rsvp.No -> Res.string.rsvp_no
}

private const val MAX_TEXT = 200
private const val MAX_INVITATION = 1_000
private const val MAX_BUDGET_DIGITS = 5
