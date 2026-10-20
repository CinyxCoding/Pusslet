package se.pusslet.feature.calendar.details

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.helper.HelperAnswer
import se.pusslet.core.model.Child
import se.pusslet.core.model.Event
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.Member
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.model.Role
import se.pusslet.data.sync.SyncState
import se.pusslet.data.sync.SyncStatus
import se.pusslet.data.usecase.HandoffAssigner
import se.pusslet.data.usecase.HandoffRequests
import se.pusslet.feature.calendar.TestWorld
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Händelsedetaljer and hand-off requests (UX plan Steg 3, ADR-015, ADR-030): Ella's school on Friday 16 October 2026, with
 * Sara picking up at 16:30. "Today" is Tuesday 13 October (see [TestWorld]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailsViewModelTest {
    private val monday = LocalDate(2026, 10, 12)
    private val friday = LocalDate(2026, 10, 16)
    private val asked = Instant.parse("2026-10-12T18:00:00Z")
    private val answered = Instant.parse("2026-10-12T19:00:00Z")

    /** Friday 16 October 17:00 in Stockholm, after the 16:30 pick-up. */
    private val fridayEvening = Instant.parse("2026-10-16T15:00:00Z")
    private val syncStatus = MutableStateFlow(SyncStatus(SyncState.Synced))

    private val school = Event(
        SCHOOL,
        "Skola & fritids",
        LocalDateTime(monday, LocalTime(8, 15)),
        LocalDateTime(monday, LocalTime(16, 30)),
        rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
        participantIds = listOf(ELLA),
        dropOff = Handoff(JOHAN, LocalTime(8, 5)),
        pickUp = Handoff(SARA, LocalTime(16, 30)),
        tags = listOf("skola"),
    )

    /** Sara asks Bengt about Friday's pick-up, as in the demo. */
    private val ask = HandoffRequest(
        id = "r-fre",
        eventId = SCHOOL,
        occurrenceDate = friday,
        kind = HandoffKind.PickUp,
        fromMemberId = SARA,
        toMemberId = BENGT,
        createdAt = asked,
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun parent() = TestWorld()

    private fun bengt() = TestWorld(Role.Caregiver, memberId = BENGT)

    private suspend fun TestWorld.seed(event: Event = school, requests: List<HandoffRequest> = emptyList()) {
        family.saveMember(Member(SARA, "Sara", Role.Parent))
        family.saveMember(Member(JOHAN, "Johan", Role.Parent))
        family.saveMember(Member(BENGT, "Bengt", Role.Caregiver))
        family.saveChild(Child(ELLA, "Ella", birthDate = LocalDate(2017, 3, 14)))
        calendar.saveEvent(event)
        requests.forEach { calendar.saveHandoffRequest(it) }
    }

    private fun TestScope.open(world: TestWorld, eventId: String = SCHOOL): EventDetailsViewModel {
        val vm = EventDetailsViewModel(
            world.calendar,
            world.family,
            HandoffAssigner(world.calendar),
            HandoffRequests(world.calendar, world.sessions, world.ids, world.clock),
            world.sessions,
            syncStatus,
            world.clock,
            world.zone,
        )
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { vm.state.collect {} }
        vm.load(eventId, friday)
        return vm
    }

    private suspend fun TestWorld.request(id: String = ask.id) = calendar.handoffRequests.first().single { it.id == id }

    @Test
    fun anAcceptedAnswerShowsBeforeAParentsPhoneHasWrittenItIn() = runTest {
        val world = parent()
        world.seed(requests = listOf(ask.copy(status = RequestStatus.Accepted, respondedBy = BENGT, respondedAt = answered)))

        val state = open(world).state.value

        // "Bengt hämtar 16:30" although the event still says Sara.
        assertEquals(BENGT, state.occurrence?.event?.pickUp?.personId)
        assertEquals(LocalTime(16, 30), state.occurrence?.pickUpTime())
        assertEquals(SARA, world.calendar.events.first().single().pickUp?.personId)
        assertEquals("Bengt", assertIs<RequestState.Accepted>(state.requestState(HandoffKind.PickUp)).by?.name)
    }

    @Test
    fun parentsSeeWhoWasAskedAndThatNobodyHasAnswered() = runTest {
        val world = parent()
        world.seed(requests = listOf(ask))

        val state = open(world).state.value

        val line = assertIs<RequestState.Asked>(state.requestState(HandoffKind.PickUp))
        assertFalse(line.toEveryone)
        assertEquals("Bengt", line.to?.name)
        assertTrue(line.declinedBy.isEmpty())
        assertNull(state.requestState(HandoffKind.DropOff))
        // A question is open, so "Kan någon hämta?" is not offered twice; Sara asked, so "Jag tar det" does not answer it.
        assertFalse(state.canRequestHelp(HandoffKind.PickUp))
        assertFalse(state.answersOpenRequest(HandoffKind.PickUp))
    }

    @Test
    fun parentsSeeEachNoOnAQuestionToEveryone() = runTest {
        val world = parent()
        world.seed(requests = listOf(ask.copy(toMemberId = null, declines = mapOf(BENGT to answered))))

        val state = open(world).state.value

        val line = assertIs<RequestState.Asked>(state.requestState(HandoffKind.PickUp))
        assertTrue(line.toEveryone)
        assertEquals(listOf("Bengt"), line.declinedBy.map { it.name })
    }

    @Test
    fun parentsSeeADeclineAndCanAskAgain() = runTest {
        val world = parent()
        world.seed(requests = listOf(ask.copy(status = RequestStatus.Declined, respondedBy = BENGT, respondedAt = answered)))
        val vm = open(world)

        assertEquals("Bengt", assertIs<RequestState.Declined>(vm.state.value.requestState(HandoffKind.PickUp)).by?.name)
        assertTrue(vm.state.value.canRequestHelp(HandoffKind.PickUp))

        vm.requestHelp(HandoffKind.PickUp)

        val again = world.calendar.handoffRequests.first().single { it.id != ask.id }
        assertNull(again.toMemberId)
        assertEquals(SCHOOL, again.eventId)
        val newest = assertIs<RequestState.Asked>(vm.state.value.requestState(HandoffKind.PickUp))
        assertTrue(newest.toEveryone, "The newest request wins")
    }

    @Test
    fun theNewestRequestPerHandoffWinsAndOwnTakesNeedNoLine() = runTest {
        val world = parent()
        val older = ask.copy(
            id = "r-old",
            status = RequestStatus.Declined,
            respondedBy = BENGT,
            createdAt = Instant.parse("2026-10-01T08:00:00Z"),
        )
        val mine = ask.copy(status = RequestStatus.Accepted, respondedBy = SARA, respondedAt = answered, appliedAt = answered)
        world.seed(requests = listOf(older, mine))

        assertNull(open(world).state.value.requestState(HandoffKind.PickUp), "Sara took it herself: the hand-off says so")
    }

    @Test
    fun aCaregiversCannotMakeIsAProblemThatJagTarDetAnswers() = runTest {
        val world = parent()
        val cannot = ask.copy(id = "r-kan-inte", fromMemberId = BENGT, toMemberId = null)
        world.seed(school.copy(pickUp = Handoff(BENGT, LocalTime(16, 30))), listOf(cannot))
        val vm = open(world)

        val line = assertIs<RequestState.CannotMake>(vm.state.value.requestState(HandoffKind.PickUp))
        assertFalse(line.mine)
        assertEquals("Bengt", line.from?.name)

        vm.take(HandoffKind.PickUp)

        val saved = world.request("r-kan-inte")
        assertEquals(RequestStatus.Accepted, saved.status)
        assertEquals(SARA, saved.respondedBy)
        assertNotNull(saved.appliedAt)
        assertEquals(SARA, vm.state.value.occurrence?.event?.pickUp?.personId)
        assertNull(vm.state.value.requestState(HandoffKind.PickUp), "Solved: Sara took it herself")
    }

    @Test
    fun aCannotMakeIsSolvedWhenTheHandoffGoesToSomeoneElse() = runTest {
        val world = parent()
        world.seed(school.copy(pickUp = Handoff(JOHAN, LocalTime(16, 30))), listOf(ask.copy(fromMemberId = BENGT, toMemberId = null)))

        assertNull(open(world).state.value.requestState(HandoffKind.PickUp))
    }

    @Test
    fun aParentsOwnChoiceWinsOverAnAnswerNotWrittenInYet() = runTest {
        val world = parent()
        world.seed(requests = listOf(ask.copy(status = RequestStatus.Accepted, respondedBy = BENGT, respondedAt = answered)))
        val vm = open(world)

        vm.assign(HandoffKind.PickUp, JOHAN)

        assertEquals(JOHAN, vm.state.value.occurrence?.event?.pickUp?.personId)
        assertNotNull(world.request().appliedAt)
    }

    @Test
    fun noKanNagonHamtaForEventsCaregiversDoNotSee() = runTest {
        val world = parent()
        world.seed(school.copy(sharedWithCaregivers = false))
        val vm = open(world)

        assertFalse(vm.state.value.canRequestHelp(HandoffKind.PickUp))
        vm.requestHelp(HandoffKind.PickUp)

        assertTrue(world.calendar.handoffRequests.first().isEmpty())
    }

    @Test
    fun sharedEventsOfferKanNagonHamta() = runTest {
        val world = parent()
        world.seed()
        val vm = open(world)

        assertTrue(vm.state.value.canRequestHelp(HandoffKind.PickUp))
        assertFalse(vm.state.value.canRequestHelp(HandoffKind.DropOff), "Only pick-ups are asked about")
        vm.requestHelp(HandoffKind.PickUp)

        val saved = world.calendar.handoffRequests.first().single()
        assertEquals(SARA, saved.fromMemberId)
        assertEquals(friday, saved.occurrenceDate)
        assertFalse(vm.state.value.canRequestHelp(HandoffKind.PickUp))
    }

    @Test
    fun caregiverYesAcceptsWithBengtAndWritesNoEvent() = runTest {
        val world = bengt()
        world.seed(requests = listOf(ask))
        val vm = open(world)
        val events = world.calendar.events.first()

        val card = assertIs<RequestState.ForMe>(vm.state.value.requestState(HandoffKind.PickUp))
        assertNull(card.answer)
        assertEquals("Sara", card.from?.name)

        vm.accept(HandoffKind.PickUp)

        val saved = world.request()
        assertEquals(RequestStatus.Accepted, saved.status)
        assertEquals(BENGT, saved.respondedBy)
        assertEquals(world.clock.now(), saved.respondedAt)
        assertEquals(events, world.calendar.events.first(), "A caregiver never writes events")
        val state = vm.state.value
        val after = assertIs<RequestState.ForMe>(state.requestState(HandoffKind.PickUp))
        assertEquals(HelperAnswer.Yes, after.answer)
        assertTrue(after.canChangeAnswer)
        assertEquals(BENGT, state.occurrence?.event?.pickUp?.personId, "Du hämtar 16:30 at once")

        vm.changeAnswer(HandoffKind.PickUp)

        assertEquals(RequestStatus.Pending, world.request().status)
        assertEquals(events, world.calendar.events.first())
    }

    @Test
    fun caregiverNoDeclinesARequestToHim() = runTest {
        val world = bengt()
        world.seed(requests = listOf(ask))
        val vm = open(world)
        val events = world.calendar.events.first()

        vm.decline(HandoffKind.PickUp)

        val saved = world.request()
        assertEquals(RequestStatus.Declined, saved.status)
        assertEquals(BENGT, saved.respondedBy)
        assertEquals(events, world.calendar.events.first())
        assertEquals(HelperAnswer.No, assertIs<RequestState.ForMe>(vm.state.value.requestState(HandoffKind.PickUp)).answer)
    }

    @Test
    fun caregiverNoOnAQuestionToEveryoneOnlyRecordsHisNo() = runTest {
        val world = bengt()
        world.seed(requests = listOf(ask.copy(toMemberId = null)))
        val vm = open(world)

        vm.decline(HandoffKind.PickUp)

        val saved = world.request()
        assertEquals(RequestStatus.Pending, saved.status, "The others can still answer")
        assertTrue(BENGT in saved.declines)
        assertNull(saved.respondedBy)
        assertEquals(HelperAnswer.No, assertIs<RequestState.ForMe>(vm.state.value.requestState(HandoffKind.PickUp)).answer)
    }

    @Test
    fun theCaregiverPathNeverWritesEvents() = runTest {
        val world = bengt()
        world.seed(school.copy(pickUp = Handoff(null, LocalTime(16, 30))), listOf(ask))
        val vm = open(world)
        val events = world.calendar.events.first()

        // None of these are offered to him; the view model refuses them too.
        vm.take(HandoffKind.PickUp)
        vm.assign(HandoffKind.DropOff, BENGT)
        vm.requestHelp(HandoffKind.PickUp)
        vm.accept(HandoffKind.PickUp)

        assertEquals(events, world.calendar.events.first())
        assertEquals(1, world.calendar.handoffRequests.first().size)
        assertFalse(vm.state.value.editable)
        assertFalse(vm.state.value.canRequestHelp(HandoffKind.PickUp))
    }

    @Test
    fun aCaregiverOnlySeesWhatIsForHim() = runTest {
        val world = bengt()
        world.seed(requests = listOf(ask.copy(toMemberId = JOHAN)))
        val vm = open(world)

        assertNull(vm.state.value.requestState(HandoffKind.PickUp), "Sara asked Johan, not him")
        vm.accept(HandoffKind.PickUp)
        assertEquals(RequestStatus.Pending, world.request().status)
    }

    @Test
    fun aCaregiverSeesHisOwnCannotMakeAsWaiting() = runTest {
        val world = bengt()
        world.seed(school.copy(pickUp = Handoff(BENGT, LocalTime(16, 30))), listOf(ask.copy(fromMemberId = BENGT, toMemberId = null)))

        assertTrue(assertIs<RequestState.CannotMake>(open(world).state.value.requestState(HandoffKind.PickUp)).mine)
    }

    @Test
    fun aPassedHandoffIsNoLongerAskedOrAnswered() = runTest {
        val caregiver = TestWorld(Role.Caregiver, now = fridayEvening, memberId = BENGT)
        caregiver.seed(requests = listOf(ask))
        val vm = open(caregiver)
        assertTrue(vm.state.value.handoffPassed(HandoffKind.PickUp))
        assertNull(vm.state.value.requestState(HandoffKind.PickUp), "No Ja/Nej once the pick-up is over")
        vm.accept(HandoffKind.PickUp)
        assertEquals(RequestStatus.Pending, caregiver.request().status)

        val parent = TestWorld(now = fridayEvening)
        parent.seed(requests = listOf(ask))
        val details = open(parent).state.value
        assertNull(details.requestState(HandoffKind.PickUp), "No \"Frågat Bengt\" about a pick-up that is over")
        assertFalse(details.canRequestHelp(HandoffKind.PickUp))
    }

    @Test
    fun whatWasAnsweredStillShowsAfterTheHandoff() = runTest {
        val parent = TestWorld(now = fridayEvening)
        parent.seed(requests = listOf(ask.copy(status = RequestStatus.Accepted, respondedBy = BENGT, respondedAt = answered)))

        assertIs<RequestState.Accepted>(open(parent).state.value.requestState(HandoffKind.PickUp))
    }

    @Test
    fun anAnswerWithoutNetworkSaysItIsSentLater() = runTest {
        val world = bengt()
        world.seed(requests = listOf(ask))
        val vm = open(world)
        assertFalse(vm.state.value.waitingForNetwork)

        syncStatus.value = SyncStatus(SyncState.Offline)

        assertTrue(vm.state.value.waitingForNetwork)
    }

    @Test
    fun parentsSeeWhichCaregiversDoNotSeeAPrivateEvent() = runTest {
        val world = parent()
        world.seed(school.copy(sharedWithCaregivers = false))

        assertEquals(listOf("Bengt"), open(world).state.value.caregivers.map { it.name })
    }

    private companion object {
        const val SCHOOL = "e-skola"
        const val SARA = "m-sara"
        const val JOHAN = "m-johan"
        const val BENGT = "m-bengt"
        const val ELLA = "c-ella"
    }
}
