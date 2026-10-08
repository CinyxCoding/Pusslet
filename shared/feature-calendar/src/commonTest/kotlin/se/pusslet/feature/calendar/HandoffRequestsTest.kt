package se.pusslet.feature.calendar

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Event
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.RequestStatus
import se.pusslet.data.usecase.FamilyAlerts
import se.pusslet.data.usecase.HandoffAlert
import se.pusslet.data.usecase.HandoffAssigner
import se.pusslet.data.usecase.HandoffRequests
import se.pusslet.testing.TestGraph
import se.pusslet.testing.TestGraph.Companion.JOHAN
import se.pusslet.testing.TestGraph.Companion.SARA
import se.pusslet.testing.TestGraph.Companion.SIMON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HandoffRequestsTest {
    private class Recorder : FamilyAlerts {
        val requests = mutableListOf<HandoffAlert>()
        val answers = mutableListOf<Pair<String, Boolean>>()

        override fun handoffRequest(alert: HandoffAlert) {
            requests += alert
        }

        override fun handoffAnswer(alert: HandoffAlert, byName: String, accepted: Boolean) {
            answers += byName to accepted
        }

        override fun shoppingAdded(listId: String, byName: String, itemName: String) = Unit

        override fun eventChanged(eventId: String, date: kotlinx.datetime.LocalDate, title: String, byName: String, added: Boolean) = Unit
    }

    /** Football every Thursday; Sara asks for help with Thursday's pick-up. */
    private suspend fun TestGraph.setUp(): HandoffRequest {
        seedFamily()
        calendar.saveEvent(
            Event(
                "fotboll",
                "Fotboll",
                LocalDateTime(today, LocalTime(17, 0)),
                LocalDateTime(today, LocalTime(18, 0)),
                rrule = "FREQ=WEEKLY;BYDAY=TH",
                participantIds = listOf(SIMON),
                pickUp = Handoff(SARA, LocalTime(18, 0)),
            ),
        )
        return HandoffRequest("r1", "fotboll", today, HandoffKind.PickUp, fromMemberId = SARA)
    }

    @Test
    fun takingItAssignsThisThursdayOnlyAndClosesTheRequest() = runTest {
        val g = TestGraph(memberId = JOHAN)
        val request = g.setUp()
        g.calendar.saveHandoffRequest(request)
        val requests = HandoffRequests(g.calendar, g.family, HandoffAssigner(g.calendar), g.sessions, g.clock, g.zone)
        assertTrue(requests.answer("r1", accept = true))
        val saved = g.calendar.handoffRequests.first().single()
        assertEquals(RequestStatus.Accepted, saved.status)
        assertEquals(JOHAN, saved.respondedBy)
        val events = g.calendar.events.first()
        assertEquals(SARA, events.single { it.id == "fotboll" }.pickUp?.personId, "the series is unchanged")
        assertEquals(JOHAN, events.single { it.seriesId == "fotboll" }.pickUp?.personId, "this Thursday is Johan's")
        assertEquals(false, requests.answer("r1", accept = true), "already answered")
    }

    @Test
    fun decliningARequestToAnyoneLeavesItOpenForOthers() = runTest {
        val g = TestGraph(memberId = JOHAN)
        g.calendar.saveHandoffRequest(g.setUp())
        val requests = HandoffRequests(g.calendar, g.family, HandoffAssigner(g.calendar), g.sessions, g.clock, g.zone)
        requests.answer("r1", accept = false)
        assertEquals(RequestStatus.Pending, g.calendar.handoffRequests.first().single().status)
        g.calendar.saveHandoffRequest(g.calendar.handoffRequests.first().single().copy(toMemberId = JOHAN))
        requests.answer("r1", accept = false)
        assertEquals(RequestStatus.Declined, g.calendar.handoffRequests.first().single().status)
    }

    @Test
    fun newRequestsAndAnswersAreAlertedOnce() = runTest {
        val johan = TestGraph(memberId = JOHAN)
        val request = johan.setUp()
        val alerts = Recorder()
        val requests =
            HandoffRequests(johan.calendar, johan.family, HandoffAssigner(johan.calendar), johan.sessions, johan.clock, johan.zone)
        requests.watch(backgroundScope, alerts)
        testScheduler.runCurrent()
        johan.calendar.saveHandoffRequest(request)
        testScheduler.runCurrent()
        val alert = alerts.requests.single()
        assertEquals("Sara", alert.fromName)
        assertEquals(listOf("Simon"), alert.childNames)
        assertEquals("Fotboll", alert.eventTitle)
        assertEquals(LocalTime(18, 0), alert.time)
        johan.calendar.saveHandoffRequest(request.copy(message = "tack"))
        testScheduler.runCurrent()
        assertEquals(1, alerts.requests.size, "an edit is not a new request")
    }

    @Test
    fun theAskerHearsTheAnswer() = runTest(UnconfinedTestDispatcher()) {
        val sara = TestGraph(memberId = SARA)
        val request = sara.setUp()
        sara.calendar.saveHandoffRequest(request)
        val alerts = Recorder()
        HandoffRequests(
            sara.calendar,
            sara.family,
            HandoffAssigner(sara.calendar),
            sara.sessions,
            sara.clock,
            sara.zone,
        ).watch(backgroundScope, alerts)
        assertTrue(alerts.requests.isEmpty(), "my own request is not an alert for me")
        sara.calendar.saveHandoffRequest(request.copy(status = RequestStatus.Accepted, respondedBy = JOHAN))
        assertEquals(listOf("Johan" to true), alerts.answers)
    }
}
