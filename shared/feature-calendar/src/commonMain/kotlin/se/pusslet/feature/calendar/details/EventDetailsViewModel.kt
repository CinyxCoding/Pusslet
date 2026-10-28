package se.pusslet.feature.calendar.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.calendar.EffectiveHandoffs
import se.pusslet.core.calendar.EffectiveHandoffs.matches
import se.pusslet.core.helper.HelperAnswer
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
import se.pusslet.data.sync.SyncStatus
import se.pusslet.data.sync.waitsForNetwork
import se.pusslet.data.usecase.HandoffAssigner
import se.pusslet.data.usecase.HandoffRequests
import se.pusslet.feature.calendar.settleAnswers
import se.pusslet.feature.calendar.takeHandoff
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What the details screen says about the latest request for one hand-off (UX plan Steg 3, "Föräldrar ser svaren"). Parents
 * see the state as a quiet line; a caregiver only sees what he can answer, or his own "Jag kan inte".
 */
sealed interface RequestState {
    val request: HandoffRequest

    /** "Frågat Bengt · inget svar än", or "Frågat alla · inget svar än" with "Bengt kan inte" for each no in [declinedBy]. */
    data class Asked(override val request: HandoffRequest, val to: FamilyPerson?, val declinedBy: List<FamilyPerson>) : RequestState {
        val toEveryone: Boolean get() = request.toMemberId == null
    }

    /** "Bengt svarade ja"; [by] is null when that member has left the family. */
    data class Accepted(override val request: HandoffRequest, val by: FamilyPerson?) : RequestState

    /** "Bengt kan inte"; "Jag tar det" stays where it was. */
    data class Declined(override val request: HandoffRequest, val by: FamilyPerson?) : RequestState

    /**
     * A caregiver's own "Jag kan inte": a Problem with "Jag tar det" for parents ("Bengt kan inte hämta Simon 15:30"), and
     * "Du har sagt att du inte kan" on his own phone ([mine]).
     */
    data class CannotMake(override val request: HandoffRequest, val from: FamilyPerson?, val mine: Boolean) : RequestState

    /** A caregiver's card: Ja/Nej while [answer] is null, then what he said and "Ändra svar" until a parent's phone wrote it in. */
    data class ForMe(override val request: HandoffRequest, val from: FamilyPerson?, val answer: HelperAnswer?) : RequestState {
        val canChangeAnswer: Boolean get() = answer != null && request.appliedAt == null
    }
}

data class EventDetailsState(
    val loading: Boolean = true,
    /** With accepted answers that no parent's phone has written in yet ("Bengt hämtar 16:30", ADR-030). */
    val occurrence: EventOccurrence? = null,
    val people: List<FamilyPerson> = emptyList(),
    val myMemberId: String? = null,
    val role: Role = Role.Parent,
    /** The latest request per hand-off kind for this occurrence, answered or not. */
    val requests: List<HandoffRequest> = emptyList(),
    /** When the state was built; a question about a hand-off that is over is no longer asked or answered. */
    val now: LocalDateTime? = null,
    /** "Skickas när du har nät" under a caregiver's answer. */
    val waitingForNetwork: Boolean = false,
    /** The event lives in a space shared with the other home (ADR-056). */
    val sharedWithOtherHome: Boolean = false,
) {
    val editable: Boolean get() = occurrence?.source == EventSource.Family && role == Role.Parent

    /** Who can drop off or pick up: the other home's adults only for a shared event. */
    val adults: List<FamilyPerson> get() = people.filter { !it.isChild && (!it.otherHome || sharedWithOtherHome) }

    /** "Bengt ser inte den här händelsen." for an event only parents see. */
    val caregivers: List<FamilyPerson> get() = people.filter { !it.isChild && it.role == Role.Caregiver }
    private val parent: Boolean get() = role == Role.Parent

    fun request(kind: HandoffKind): HandoffRequest? = requests.firstOrNull { it.kind == kind }

    /** The hand-off is over: open questions about it, and Ja/Nej, go away; what was answered stays. */
    fun handoffPassed(kind: HandoffKind): Boolean {
        val at = now ?: return false
        return occurrence?.handoffPassed(kind, at) == true
    }

    fun requestState(kind: HandoffKind): RequestState? {
        val r = request(kind) ?: return null
        val byId = people.associateBy { it.id }
        val state = if (parent) parentView(r, byId) else caregiverView(r, byId)
        val open = state is RequestState.Asked || state is RequestState.CannotMake || state is RequestState.ForMe
        return state.takeUnless { open && handoffPassed(kind) }
    }

    /**
     * "Kan någon hämta?": parents, for pick-ups of events shared with caregivers (the others never reach them), while no
     * request for it is open.
     */
    fun canRequestHelp(kind: HandoffKind): Boolean = parent && kind == HandoffKind.PickUp &&
        occurrence?.event?.sharedWithCaregivers == true && request(kind)?.status != RequestStatus.Pending && !handoffPassed(kind)

    /** "Jag tar det" also answers an open question to me or to anyone (a caregiver's "Jag kan inte" has it on its Problem). */
    fun answersOpenRequest(kind: HandoffKind): Boolean {
        val me = myMemberId ?: return false
        val r = request(kind) ?: return false
        return parent && r.status == RequestStatus.Pending && r.isFor(me) && !handoffPassed(kind) &&
            requestState(kind) !is RequestState.CannotMake
    }

    private fun parentView(r: HandoffRequest, byId: Map<String, FamilyPerson>): RequestState? = when (r.status) {
        RequestStatus.Pending -> if (r.toMemberId == null && byId[r.fromMemberId]?.role == Role.Caregiver) {
            // Solved once it is given to someone else, even without an answer.
            if (stillHis(r)) RequestState.CannotMake(r, byId[r.fromMemberId], mine = false) else null
        } else {
            RequestState.Asked(r, r.toMemberId?.let(byId::get), r.declines.entries.sortedBy { it.value }.mapNotNull { byId[it.key] })
        }
        // The viewer's own "Jag tar det" needs no line: the hand-off says "Du hämtar".
        RequestStatus.Accepted -> if (r.respondedBy != null && r.respondedBy == myMemberId) {
            null
        } else {
            RequestState.Accepted(r, r.respondedBy?.let(byId::get))
        }
        RequestStatus.Declined -> RequestState.Declined(r, r.respondedBy?.let(byId::get))
        RequestStatus.Cancelled -> null
    }

    /** "Jag kan inte" only matters while the hand-off is still the caregiver's who said it. */
    private fun stillHis(r: HandoffRequest): Boolean {
        val event = occurrence?.event ?: return false
        val handoff = if (r.kind == HandoffKind.PickUp) event.pickUp else event.dropOff
        return handoff?.personId == r.fromMemberId
    }

    private fun caregiverView(r: HandoffRequest, byId: Map<String, FamilyPerson>): RequestState? {
        val me = myMemberId ?: return null
        val from = byId[r.fromMemberId]
        return when {
            r.fromMemberId == me -> {
                val waiting = r.status == RequestStatus.Pending && r.toMemberId == null && stillHis(r)
                if (waiting) RequestState.CannotMake(r, from, mine = true) else null
            }
            r.toMemberId != null && r.toMemberId != me -> null
            r.status == RequestStatus.Pending -> RequestState.ForMe(r, from, if (me in r.declines) HelperAnswer.No else null)
            r.status == RequestStatus.Declined && r.respondedBy == me -> RequestState.ForMe(r, from, HelperAnswer.No)
            r.status == RequestStatus.Accepted && r.respondedBy == me && r.appliedAt == null ->
                RequestState.ForMe(r, from, HelperAnswer.Yes)
            else -> null
        }
    }
}

/** To [me] or to anyone, from someone else, and not already declined by [me]. */
internal fun HandoffRequest.isFor(me: String): Boolean = fromMemberId != me && (toMemberId == null || toMemberId == me) && me !in declines

/**
 * One occurrence. Parents change and take hand-offs and ask "Kan någon hämta?"; a caregiver (read-only, ADR-015) can only
 * answer a request to him or to anyone. Nothing on the caregiver's path writes events.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailsViewModel(
    private val calendar: CalendarRepository,
    family: FamilyRepository,
    private val assigner: HandoffAssigner,
    private val handoffRequests: HandoffRequests,
    private val sessions: SessionProvider,
    /** The sync engine's status, for "Skickas när du har nät". */
    syncStatus: Flow<SyncStatus>,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val log = Logger.withTag("EventDetails")
    private val target = MutableStateFlow<Pair<String, LocalDate>?>(null)
    private val newId: () -> String = family::newId

    private val occurrence = target.filterNotNull().flatMapLatest { (id, date) ->
        // Overrides may move an occurrence a few days, so look around the original date. Whatever Kalender's filter shows:
        // the details are opened from Idag and the helper view too.
        calendar.allOccurrences(date.plusDays(-SEARCH_DAYS), date.plusDays(SEARCH_DAYS), zone).combine(target) { list, _ ->
            list.firstOrNull { it.event.id == id && it.occurrenceDate == date }
                ?: list.firstOrNull { it.event.seriesId == id && it.occurrenceDate == date }
                ?: list.filter { it.event.id == id }.minByOrNull { kotlin.math.abs(it.occurrenceDate.toEpochDays() - date.toEpochDays()) }
        }
    }

    private val waitingForNetwork = syncStatus.map { it.waitsForNetwork() }.distinctUntilChanged().onStart { emit(false) }

    val state: StateFlow<EventDetailsState> = combine(
        occurrence,
        family.everyone,
        sessions.session,
        calendar.handoffRequests,
        waitingForNetwork,
    ) { o, people, session, requests, waiting ->
        // A caregiver's accepted answer counts at once, like on Idag and in the calendar (ADR-030).
        val effective = o?.let { EffectiveHandoffs.apply(it, EffectiveHandoffs.pending(requests)) }
        EventDetailsState(
            loading = false,
            occurrence = effective,
            people = people,
            myMemberId = session.memberId,
            role = session.role,
            requests = effective?.let { latestPerKind(requests, it) }.orEmpty(),
            now = clock.now().toLocalDateTime(zone),
            waitingForNetwork = waiting,
            sharedWithOtherHome = o?.source == EventSource.Family && calendar.sharedSpaceOf(o.event.seriesId ?: o.event.id) != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EventDetailsState())

    fun load(eventId: String, occurrenceDate: LocalDate) {
        target.value = eventId to occurrenceDate
    }

    /** "Ändra" (parents). A manual choice wins over an answer no parent's phone has written in yet. */
    fun assign(kind: HandoffKind, personId: String?) {
        if (!isParent()) return
        val o = state.value.occurrence ?: return
        viewModelScope.launch {
            assigner.assign(o, kind, personId)
            settleAnswers(calendar, clock, o, kind)
        }
    }

    /** "Jag tar det" (parents). Taking it also answers any open request for the same hand-off. */
    fun take(kind: HandoffKind) {
        if (!isParent()) return
        val me = sessions.session.value.memberId ?: return
        val o = state.value.occurrence ?: return
        viewModelScope.launch { takeHandoff(calendar, assigner, clock, o, kind, me) }
    }

    /** "Kan någon hämta?": a request to anyone, only for events shared with caregivers and while none is open. */
    fun requestHelp(kind: HandoffKind) {
        val me = sessions.session.value.memberId ?: return
        val s = state.value
        val o = s.occurrence ?: return
        if (!s.canRequestHelp(kind)) return
        viewModelScope.launch {
            calendar.saveHandoffRequest(
                HandoffRequest(
                    id = newId(),
                    // A request names the series, so it still matches when the occurrence gets an override.
                    eventId = o.event.seriesId ?: o.event.id,
                    occurrenceDate = o.occurrenceDate,
                    kind = kind,
                    fromMemberId = me,
                    createdAt = clock.now(),
                ),
            )
        }
    }

    /** "Ja, jag hämtar Ella" (caregivers): only the request; the event is written by a parent's phone (ADR-030). */
    fun accept(kind: HandoffKind) = answer(kind) { handoffRequests.accept(it) }

    /** "Nej, jag kan inte": declined if it was to me, otherwise only my no is recorded. */
    fun decline(kind: HandoffKind) = answer(kind) { handoffRequests.decline(it) }

    /** "Ändra svar": open again, until a parent's phone has written the answer in. */
    fun changeAnswer(kind: HandoffKind) = answer(kind) { handoffRequests.changeAnswer(it) }

    /** Answers the latest copy of the request, so a change from another phone is not overwritten. */
    private fun answer(kind: HandoffKind, action: suspend (HandoffRequest) -> Boolean) {
        val shown = state.value.requestState(kind) as? RequestState.ForMe ?: return
        viewModelScope.launch {
            val latest = calendar.handoffRequests.first().firstOrNull { it.id == shown.request.id } ?: return@launch
            if (!action(latest)) log.i { "A hand-off answer was not saved: the request changed meanwhile" }
        }
    }

    private fun isParent(): Boolean = sessions.session.value.role == Role.Parent

    internal companion object {
        const val SEARCH_DAYS = 14
        const val STOP_TIMEOUT_MS = 5_000L

        /** The newest request per kind for [occurrence] (a request names the series or the override). */
        fun latestPerKind(requests: List<HandoffRequest>, occurrence: EventOccurrence): List<HandoffRequest> = requests
            .filter { it.matches(occurrence) }
            .groupBy { it.kind }
            .values
            .map { same -> same.maxBy { it.createdAt ?: Instant.DISTANT_PAST } }
            .sortedBy { it.kind.ordinal }
    }
}
