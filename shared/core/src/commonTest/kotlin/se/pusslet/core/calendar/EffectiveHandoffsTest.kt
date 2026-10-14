package se.pusslet.core.calendar

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.RequestStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Instant

class EffectiveHandoffsTest {
    private val friday = LocalDate(2026, 10, 16)
    private val school = Event(
        id = "e-school",
        title = "Skola",
        start = LocalDateTime(friday, LocalTime(8, 15)),
        end = LocalDateTime(friday, LocalTime(16, 0)),
        rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
        participantIds = listOf("c-ella"),
        pickUp = Handoff(null, LocalTime(16, 30)),
        dropOff = Handoff("m-johan", LocalTime(8, 5)),
    )
    private val occurrence = EventOccurrence(school, school.start, school.end, occurrenceDate = friday)

    private fun request(
        status: RequestStatus = RequestStatus.Accepted,
        by: String? = "m-bengt",
        at: String = "2026-10-15T18:00:00Z",
        applied: Instant? = null,
        eventId: String = "e-school",
        date: LocalDate = friday,
        kind: HandoffKind = HandoffKind.PickUp,
    ) = HandoffRequest(
        id = "r-$by-$at",
        eventId = eventId,
        occurrenceDate = date,
        kind = kind,
        fromMemberId = "m-sara",
        status = status,
        respondedBy = by,
        respondedAt = Instant.parse(at),
        appliedAt = applied,
    )

    @Test
    fun anAcceptedAnswerCountsBeforeItIsWrittenIn() {
        val effective = EffectiveHandoffs.apply(listOf(occurrence), listOf(request())).single()
        assertEquals(Handoff("m-bengt", LocalTime(16, 30)), effective.event.pickUp)
        // The other hand-off is untouched.
        assertEquals(Handoff("m-johan", LocalTime(8, 5)), effective.event.dropOff)
    }

    @Test
    fun onlyAcceptedAndNotYetAppliedAnswersCount() {
        val ignored = listOf(
            request(status = RequestStatus.Pending, by = null),
            request(status = RequestStatus.Declined),
            request(applied = Instant.parse("2026-10-15T18:05:00Z")),
            request(date = friday.plusDaysForTest(7)),
            request(eventId = "e-other"),
        )
        assertSame(occurrence, EffectiveHandoffs.apply(listOf(occurrence), ignored).single())
    }

    @Test
    fun theLatestAnswerWinsAndOverridesMatchTheirSeries() {
        val override = school.copy(id = "e-school@2026-10-16", rrule = null, seriesId = "e-school", originalDate = friday)
        val changed = EventOccurrence(override, override.start, override.end, occurrenceDate = friday)
        val answers = listOf(request(by = "m-bengt", at = "2026-10-15T18:00:00Z"), request(by = "m-johan", at = "2026-10-15T19:00:00Z"))
        assertEquals("m-johan", EffectiveHandoffs.apply(listOf(changed), answers).single().event.pickUp?.personId)
    }

    @Test
    fun aDropOffAnswerChangesTheDropOff() {
        val effective = EffectiveHandoffs.apply(listOf(occurrence), listOf(request(kind = HandoffKind.DropOff))).single()
        assertEquals("m-bengt", effective.event.dropOff?.personId)
        assertEquals(null, effective.event.pickUp?.personId)
    }

    private fun LocalDate.plusDaysForTest(days: Int) = LocalDate.fromEpochDays(toEpochDays() + days)
}
