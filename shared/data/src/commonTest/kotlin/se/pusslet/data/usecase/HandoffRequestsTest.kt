package se.pusslet.data.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Event
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.model.Role
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.FakeCacheDao
import se.pusslet.data.FakePreferenceDao
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.NoDeviceCalendars
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Morfar Bengt answers a request on his phone; Sara's phone writes the answer into the event (ADR-030). */
class HandoffRequestsTest {
    private val zone = TimeZone.of("Europe/Stockholm")
    private val friday = LocalDate(2026, 10, 16)
    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-10-15T18:00:00Z")
    }
    private val store = InMemorySyncLocalStore()

    /** One family's data, seen from one member's phone. */
    private inner class Phone(memberId: String, role: Role) {
        val sessions = FixedSessionProvider(Session(familyId = "f", memberId = memberId, uid = "u-$memberId", role = role))
        val calendar =
            CalendarRepository(
                DocumentStore(store, sessions, clock),
                FakeCacheDao(),
                PreferencesRepository(FakePreferenceDao()),
                NoDeviceCalendars,
                sessions,
            )
        val requests = HandoffRequests(calendar, sessions, SequentialIdGenerator(), clock)
        val applier = AcceptedRequestApplier(calendar, HandoffAssigner(calendar), sessions, clock, zone)
    }

    private val sara = Phone("m-sara", Role.Parent)
    private val bengt = Phone("m-bengt", Role.Caregiver)

    private val school = Event(
        id = "e-school",
        title = "Skola",
        start = LocalDateTime(LocalDate(2026, 10, 12), LocalTime(8, 15)),
        end = LocalDateTime(LocalDate(2026, 10, 12), LocalTime(16, 0)),
        rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
        participantIds = listOf("c-ella"),
        pickUp = Handoff(null, LocalTime(16, 30)),
    )

    private fun request(to: String? = "m-bengt") = HandoffRequest(
        id = "r1",
        eventId = "e-school",
        occurrenceDate = friday,
        kind = HandoffKind.PickUp,
        fromMemberId = "m-sara",
        toMemberId = to,
    )

    private suspend fun stored(phone: Phone = sara): HandoffRequest = phone.calendar.handoffRequests.first().single()

    @Test
    fun yesCountsAtOnceAndAParentPhoneWritesItIn() = runTest {
        sara.calendar.saveEvent(school)
        sara.calendar.saveHandoffRequest(request())
        assertTrue(bengt.requests.accept(stored(bengt)))
        val answered = stored()
        assertEquals(RequestStatus.Accepted, answered.status)
        assertEquals("m-bengt", answered.respondedBy)
        assertNull(answered.appliedAt)

        // A caregiver's phone never writes events.
        assertEquals(0, bengt.applier.applyPending())
        assertEquals(1, sara.applier.applyPending())
        val friday = sara.calendar.occurrences(this@HandoffRequestsTest.friday, this@HandoffRequestsTest.friday, zone).first().single()
        assertEquals("m-bengt", friday.event.pickUp?.personId)
        assertEquals(LocalTime(16, 30), friday.event.pickUp?.time)
        assertNotNull(stored().appliedAt)
        // Running again changes nothing.
        assertEquals(0, sara.applier.applyPending())
    }

    @Test
    fun noToMeDeclinesButNoToAnyoneOnlyRecordsMyAnswer() = runTest {
        sara.calendar.saveHandoffRequest(request(to = "m-bengt"))
        assertTrue(bengt.requests.decline(stored(bengt)))
        assertEquals(RequestStatus.Declined, stored().status)

        sara.calendar.saveHandoffRequest(request(to = null).copy(status = RequestStatus.Pending, respondedBy = null, respondedAt = null))
        assertTrue(bengt.requests.decline(stored(bengt)))
        val open = stored()
        assertEquals(RequestStatus.Pending, open.status)
        assertEquals(setOf("m-bengt"), open.declines.keys)
    }

    @Test
    fun theAnswerCanChangeUntilItIsWrittenIn() = runTest {
        sara.calendar.saveEvent(school)
        sara.calendar.saveHandoffRequest(request())
        bengt.requests.accept(stored(bengt))
        assertTrue(bengt.requests.changeAnswer(stored(bengt)))
        assertEquals(RequestStatus.Pending, stored().status)
        assertNull(stored().respondedBy)

        bengt.requests.accept(stored(bengt))
        sara.applier.applyPending()
        assertFalse(bengt.requests.changeAnswer(stored(bengt)))
    }

    @Test
    fun onlyRequestsForMeAndNotMyOwn() = runTest {
        sara.calendar.saveHandoffRequest(request(to = "m-johan"))
        assertFalse(bengt.requests.accept(stored(bengt)))
        assertFalse(sara.requests.accept(stored()))
        assertEquals(RequestStatus.Pending, stored().status)
    }

    @Test
    fun cannotMakeAsksTheWholeFamily() = runTest {
        sara.calendar.saveEvent(school.copy(pickUp = Handoff("m-bengt", LocalTime(16, 30))))
        val occurrence = sara.calendar.occurrences(friday, friday, zone).first().single()
        val asked = assertNotNull(bengt.requests.cannotMake(occurrence, HandoffKind.PickUp))
        assertEquals("m-bengt", asked.fromMemberId)
        assertNull(asked.toMemberId)
        assertEquals("e-school", asked.eventId)
        assertEquals(RequestStatus.Pending, stored().status)
    }
}
