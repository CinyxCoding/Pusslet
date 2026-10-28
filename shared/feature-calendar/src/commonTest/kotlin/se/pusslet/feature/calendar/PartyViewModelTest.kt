package se.pusslet.feature.calendar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Member
import se.pusslet.core.model.Rsvp
import se.pusslet.data.repository.TaskRepository
import se.pusslet.feature.calendar.details.PartyViewModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The kalas card (ADR-054). "Today" is Tuesday 13 October 2026 (see [TestWorld]). */
@OptIn(ExperimentalCoroutinesApi::class)
class PartyViewModelTest {
    private val saturday = LocalDate(2026, 10, 17)

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun TestWorld.party() = calendar.saveEvent(
        Event(
            "vera",
            "Kalas hos Vera",
            LocalDateTime(saturday, LocalTime(14, 0)),
            LocalDateTime(saturday, LocalTime(16, 0)),
            participantIds = listOf("c-ella"),
            tags = listOf(EventTags.PARTY),
        ),
    )

    @Test
    fun answeringAndThePresentAreSavedOnTheEvent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val w = TestWorld()
        val tasks = TaskRepository(w.docs)
        w.family.saveMember(Member("m-sara", "Sara"))
        w.party()
        tasks.save(FamilyTask("present", "Köp present", linkedEventId = "vera"))
        val vm = PartyViewModel(w.calendar, tasks, w.family, w.sessions, w.ids, w.clock, w.zone)
        vm.load("vera")
        advanceUntilIdle()
        assertEquals("Sara", vm.state.value.myName)

        vm.update { it.copy(rsvp = Rsvp.Yes, rsvpBy = LocalDate(2026, 10, 14)) }
        vm.update(typing = true) { it.copy(gift = it.gift.copy(idea = "Pysselset")) }
        advanceTimeBy(1_000)
        vm.setBought(true)
        advanceUntilIdle()
        val saved = w.calendar.event("vera")?.party
        assertEquals(Rsvp.Yes, saved?.rsvp)
        assertEquals("Pysselset", saved?.gift?.idea, "typing is saved shortly after")
        assertEquals(true, saved?.gift?.bought)
        assertEquals("m-sara", saved?.gift?.boughtBy)
        assertTrue(tasks.get("present")?.done == true, "the present to-do from quick add is ticked off too")
    }

    @Test
    fun ourOwnPartyHasAGuestListWhereAnswersGoRound() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val w = TestWorld()
        w.party()
        val vm = PartyViewModel(w.calendar, TaskRepository(w.docs), w.family, w.sessions, w.ids, w.clock, w.zone)
        vm.load("vera")
        advanceUntilIdle()
        vm.update { it.copy(own = true) }
        vm.addGuest("  Vera ")
        vm.addGuest("")
        vm.addGuest("Ali")
        val vera = vm.state.value.details.guests.first()
        vm.cycleGuest(vera.id)
        vm.cycleGuest(vera.id)
        advanceUntilIdle()
        val guests = w.calendar.events.first().single { it.id == "vera" }.party?.guests.orEmpty()
        assertEquals(listOf("Vera" to Rsvp.No, "Ali" to Rsvp.Pending), guests.map { it.name to it.rsvp })
        vm.removeGuest(vera.id)
        advanceUntilIdle()
        assertEquals(listOf("Ali"), w.calendar.event("vera")?.party?.guests?.map { it.name })
    }
}
