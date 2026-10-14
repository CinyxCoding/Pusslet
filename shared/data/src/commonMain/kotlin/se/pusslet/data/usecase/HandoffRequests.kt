package se.pusslet.data.usecase

import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.session.SessionProvider
import kotlin.time.Clock

/**
 * Answering and asking about drop-offs and pick-ups – what a caregiver may do (ADR-015, ADR-030). Everything is written
 * locally first and synced later; an accepted answer counts at once through `EffectiveHandoffs`, and a parent's phone writes
 * it into the event ([AcceptedRequestApplier]).
 */
class HandoffRequests(
    private val calendar: CalendarRepository,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** "Ja, jag hämtar Ella". Only a request that is still open can be accepted. */
    suspend fun accept(request: HandoffRequest): Boolean {
        val me = sessions.session.value.memberId ?: return false
        if (request.status != RequestStatus.Pending || !request.isFor(me)) return false
        calendar.saveHandoffRequest(request.copy(status = RequestStatus.Accepted, respondedBy = me, respondedAt = clock.now()))
        return true
    }

    /** "Nej, jag kan inte": a request to me is declined; one to anyone only records my no, so the others can still answer. */
    suspend fun decline(request: HandoffRequest): Boolean {
        val me = sessions.session.value.memberId ?: return false
        if (request.status != RequestStatus.Pending || !request.isFor(me)) return false
        val now = clock.now()
        val answered = if (request.toMemberId == me) {
            request.copy(status = RequestStatus.Declined, respondedBy = me, respondedAt = now)
        } else {
            request.copy(declines = request.declines + (me to now))
        }
        calendar.saveHandoffRequest(answered)
        return true
    }

    /** "Ändra svar": only until a parent's phone has written the answer into the event. */
    suspend fun changeAnswer(request: HandoffRequest): Boolean {
        val me = sessions.session.value.memberId ?: return false
        if (request.appliedAt != null) return false
        val reopened = when {
            request.respondedBy == me && request.status in setOf(RequestStatus.Accepted, RequestStatus.Declined) ->
                request.copy(status = RequestStatus.Pending, respondedBy = null, respondedAt = null)
            me in request.declines -> request.copy(declines = request.declines - me)
            else -> return false
        }
        calendar.saveHandoffRequest(reopened)
        return true
    }

    /** "Jag kan inte idag": asks the whole family to take over one of my drop-offs or pick-ups. */
    suspend fun cannotMake(occurrence: EventOccurrence, kind: HandoffKind, message: String = ""): HandoffRequest? {
        val me = sessions.session.value.memberId ?: return null
        val request = HandoffRequest(
            id = ids.newId(),
            eventId = occurrence.event.seriesId ?: occurrence.event.id,
            occurrenceDate = occurrence.occurrenceDate,
            kind = kind,
            fromMemberId = me,
            toMemberId = null,
            message = message,
            createdAt = clock.now(),
        )
        calendar.saveHandoffRequest(request)
        return request
    }

    private fun HandoffRequest.isFor(me: String): Boolean = fromMemberId != me && (toMemberId == null || toMemberId == me)
}
