package se.pusslet.data.usecase

import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffReceipt
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.session.SessionProvider
import kotlin.time.Clock

/**
 * "Hämtad ✓" (ADR-048): ticks off a pick-up for everyone. A caregiver may do it too (morfar picked Ella up); the push to
 * the others comes from the server when the receipt arrives.
 */
class PickupReceipts(private val calendar: CalendarRepository, private val sessions: SessionProvider, private val clock: Clock) {
    suspend fun confirm(
        occurrence: EventOccurrence,
        kind: HandoffKind = HandoffKind.PickUp,
        childIds: List<String> = occurrence.attending,
    ) {
        val me = sessions.session.value.memberId ?: return
        val receipt = HandoffReceipt(
            id = HandoffReceipt.idFor(occurrence, kind),
            eventId = HandoffReceipt.seriesOf(occurrence),
            occurrenceDate = occurrence.occurrenceDate,
            kind = kind,
            childIds = childIds,
            doneBy = me,
            doneAt = clock.now(),
        )
        calendar.saveHandoffReceipt(receipt)
    }

    /** "Ångra": only the one who ticked it off. */
    suspend fun undo(receipt: HandoffReceipt) {
        if (receipt.doneBy != sessions.session.value.memberId) return
        calendar.deleteHandoffReceipt(receipt.id)
    }
}
