package se.pusslet.core.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * "Hämtad ✓" (ADR-048): a drop-off or pick-up is done. One document per occurrence and kind ([idFor]); a series is named by
 * its own id, so a moved occurrence (an override event) finds the same receipt.
 */
@Serializable
data class HandoffReceipt(
    val id: String,
    /** The series' id for an occurrence of a series, else the event's own id. */
    val eventId: String,
    val occurrenceDate: LocalDate,
    val kind: HandoffKind = HandoffKind.PickUp,
    /** The children it was for. */
    val childIds: List<String> = emptyList(),
    /** The member who tapped "Hämtad". */
    val doneBy: String? = null,
    val doneAt: Instant? = null,
) {
    fun matches(occurrence: EventOccurrence, kind: HandoffKind): Boolean =
        this.kind == kind && occurrenceDate == occurrence.occurrenceDate && eventId == seriesOf(occurrence)

    companion object {
        fun idFor(occurrence: EventOccurrence, kind: HandoffKind): String =
            "${seriesOf(occurrence)}_${occurrence.occurrenceDate}_${if (kind == HandoffKind.PickUp) "pickup" else "dropoff"}"

        fun seriesOf(occurrence: EventOccurrence): String = occurrence.event.seriesId ?: occurrence.event.id
    }
}
