package se.pusslet.core.calendar

import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.RequestStatus
import kotlin.time.Instant

/**
 * Lets an accepted hand-off request count before a parent's phone has written it into the event (ADR-030): "Bengt
 * hämtar" shows on every phone as soon as his "Ja" has synced, even though a caregiver may not write events himself.
 *
 * Once a parent's phone has applied the answer ([HandoffRequest.appliedAt]) the event carries it, and later changes to
 * the event win – an old answer never overrides them.
 */
object EffectiveHandoffs {
    /** The answers that still have to be written into their events. */
    fun pending(requests: List<HandoffRequest>): List<HandoffRequest> =
        requests.filter { it.status == RequestStatus.Accepted && it.appliedAt == null && it.respondedBy != null }

    fun apply(occurrences: List<EventOccurrence>, requests: List<HandoffRequest>): List<EventOccurrence> {
        val accepted = pending(requests)
        if (accepted.isEmpty()) return occurrences
        return occurrences.map { apply(it, accepted) }
    }

    /** [occurrence] with the latest pending answer per kind; [accepted] must come from [pending]. */
    fun apply(occurrence: EventOccurrence, accepted: List<HandoffRequest>): EventOccurrence {
        var event = occurrence.event
        for (kind in HandoffKind.entries) {
            val answer = accepted
                .filter { it.kind == kind && it.matches(occurrence) }
                .maxByOrNull { it.respondedAt ?: Instant.DISTANT_PAST }
                ?: continue
            event = when (kind) {
                HandoffKind.PickUp -> event.copy(pickUp = Handoff(answer.respondedBy, event.pickUp?.time))
                HandoffKind.DropOff -> event.copy(dropOff = Handoff(answer.respondedBy, event.dropOff?.time))
            }
        }
        return if (event == occurrence.event) occurrence else occurrence.copy(event = event)
    }

    /** A request names the series; a single changed occurrence is an override event that points back to it. */
    fun HandoffRequest.matches(occurrence: EventOccurrence): Boolean =
        occurrenceDate == occurrence.occurrenceDate && (eventId == occurrence.event.id || eventId == occurrence.event.seriesId)
}
