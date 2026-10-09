package se.pusslet.data.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.core.time.plusDays
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.session.SessionProvider
import kotlin.time.Clock

/** What a hand-off alert shows: "Sara frågar: kan du hämta Simon? Fotboll 18:30". */
data class HandoffAlert(
    val requestId: String,
    val fromName: String,
    val kind: HandoffKind,
    val childNames: List<String>,
    val eventId: String,
    val eventTitle: String,
    val date: LocalDate,
    val time: LocalTime?,
)

/** Shows family alerts on this phone (Android: notifications with "Jag tar det" / "Kan inte"). */
interface FamilyAlerts {
    fun handoffRequest(alert: HandoffAlert)

    fun handoffAnswer(alert: HandoffAlert, byName: String, accepted: Boolean)

    /** "Johan lade till mjölk" while I am in the shop with that list. */
    fun shoppingAdded(listId: String, byName: String, itemName: String)

    /** "Johan ändrade Tandläkare" – someone else changed the calendar. */
    fun eventChanged(eventId: String, date: LocalDate, title: String, byName: String, added: Boolean)
}

object NoFamilyAlerts : FamilyAlerts {
    override fun handoffRequest(alert: HandoffAlert) = Unit

    override fun handoffAnswer(alert: HandoffAlert, byName: String, accepted: Boolean) = Unit

    override fun shoppingAdded(listId: String, byName: String, itemName: String) = Unit

    override fun eventChanged(eventId: String, date: LocalDate, title: String, byName: String, added: Boolean) = Unit
}

/**
 * "Kan du ta hämtningen?" – answering with one tap (also from a notification), and noticing new requests and answers
 * while the app runs. When the app is closed, the push from the backend carries the same alert.
 */
class HandoffRequests(
    private val calendar: CalendarRepository,
    private val family: FamilyRepository,
    private val assigner: HandoffAssigner,
    private val sessions: SessionProvider,
    private val clock: Clock,
    private val zone: TimeZone,
) {
    /** "Jag tar det" assigns me and closes the request; "Kan inte" closes it only when it was asked of me alone. */
    suspend fun answer(requestId: String, accept: Boolean): Boolean {
        val me = sessions.session.value.memberId ?: return false
        val request = calendar.handoffRequests.first().firstOrNull { it.id == requestId } ?: return false
        if (request.status != RequestStatus.Pending) return false
        if (accept) {
            val occurrence = occurrenceOf(request) ?: return false
            assigner.assign(occurrence, request.kind, me)
            calendar.saveHandoffRequest(request.copy(status = RequestStatus.Accepted, respondedBy = me, respondedAt = clock.now()))
        } else if (request.toMemberId == me) {
            calendar.saveHandoffRequest(request.copy(status = RequestStatus.Declined, respondedBy = me, respondedAt = clock.now()))
        }
        return true
    }

    /** Alerts for requests asked of me (or of anyone) and for answers to requests I made – new ones only. */
    fun watch(scope: CoroutineScope, alerts: FamilyAlerts) {
        scope.launch {
            var seen: Map<String, RequestStatus>? = null
            combine(calendar.handoffRequests, family.everyone) { requests, people -> requests to people }.collect { (requests, people) ->
                val me = sessions.session.value.memberId
                val previous = seen
                seen = requests.associate { it.id to it.status }
                if (previous == null || me == null) return@collect
                val byId = people.associateBy { it.id }
                for (r in requests) {
                    val before = previous[r.id]
                    val newForMe = before == null && r.status == RequestStatus.Pending && r.fromMemberId != me &&
                        (r.toMemberId == null || r.toMemberId == me) && byId[me]?.isChild == false
                    val answeredMine = r.fromMemberId == me && before == RequestStatus.Pending &&
                        (r.status == RequestStatus.Accepted || r.status == RequestStatus.Declined)
                    if (!newForMe && !answeredMine) continue
                    val alert = alertFor(r, byId) ?: continue
                    if (newForMe) {
                        alerts.handoffRequest(alert)
                    } else {
                        alerts.handoffAnswer(alert, byId[r.respondedBy]?.name.orEmpty(), r.status == RequestStatus.Accepted)
                    }
                }
            }
        }
    }

    suspend fun alertFor(r: HandoffRequest, people: Map<String, FamilyPerson>): HandoffAlert? {
        val occurrence = occurrenceOf(r) ?: return null
        val children = occurrence.event.participantIds.mapNotNull { people[it] }.filter { it.isChild }.map { it.name }
        val time = if (r.kind == HandoffKind.PickUp) occurrence.pickUpTime() else occurrence.dropOffTime()
        return HandoffAlert(
            r.id,
            people[r.fromMemberId]?.name.orEmpty(),
            r.kind,
            children,
            occurrence.event.id,
            occurrence.event.title,
            r.occurrenceDate,
            time,
        )
    }

    /** The occurrence as the calendar shows it: a series together with its changed single occurrences. */
    private suspend fun occurrenceOf(r: HandoffRequest) = calendar.events.first()
        .filter { it.id == r.eventId || it.seriesId == r.eventId }
        .let { EventExpander.expand(it, r.occurrenceDate, r.occurrenceDate.plusDays(1), zone, EventSource.Family) }
        .firstOrNull { it.occurrenceDate == r.occurrenceDate }
}
