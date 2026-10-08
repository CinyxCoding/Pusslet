package se.pusslet.data.usecase

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.data.repository.CalendarRepository
import kotlin.time.Duration.Companion.minutes

/**
 * Changes who drops off / picks up for one occurrence. For a recurring event this creates (or updates) a
 * per-occurrence override, so "Johan hämtar imorgon" never changes the whole series.
 */
class HandoffAssigner(private val calendar: CalendarRepository) {
    suspend fun assign(occurrence: EventOccurrence, kind: HandoffKind, personId: String?, time: LocalTime? = null) {
        val base = occurrence.event
        val target: Event = when {
            base.isOverride || !base.isRecurring -> base
            else -> calendar.event(overrideId(base.id, occurrence.occurrenceDate)) ?: base.copy(
                id = overrideId(base.id, occurrence.occurrenceDate),
                rrule = null,
                exDates = emptyList(),
                seriesId = base.id,
                originalDate = occurrence.occurrenceDate,
                start = occurrence.start,
                end = occurrence.end,
            )
        }
        val current = if (kind == HandoffKind.PickUp) target.pickUp else target.dropOff
        val handoff = Handoff(personId, time ?: current?.time)
        calendar.saveEvent(if (kind == HandoffKind.PickUp) target.copy(pickUp = handoff) else target.copy(dropOff = handoff))
    }

    /** Creates a stand-alone hand-off event when the day has none ("Johan hämtar imorgon" on a day without school). */
    suspend fun createStandalone(
        id: String,
        title: String,
        date: LocalDate,
        time: LocalTime,
        kind: HandoffKind,
        personId: String,
        childIds: List<String>,
        rrule: String? = null,
    ): Event {
        val start = LocalDateTime(date, time)
        val event = Event(
            id = id,
            title = title,
            start = start,
            end = EventExpander.addWallClock(start, 15.minutes),
            participantIds = childIds,
            pickUp = if (kind == HandoffKind.PickUp) Handoff(personId, time) else null,
            dropOff = if (kind == HandoffKind.DropOff) Handoff(personId, time) else null,
            rrule = rrule,
            tags = listOf(if (kind == HandoffKind.PickUp) "hämtning" else "lämning"),
        )
        calendar.saveEvent(event)
        return event
    }

    companion object {
        fun overrideId(seriesId: String, date: LocalDate) = "$seriesId@$date"
    }
}
