package se.pusslet.feature.calendar

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Rsvp
import se.pusslet.core.time.plusDays
import se.pusslet.feature.calendar.details.PartyViewModel
import se.pusslet.testing.TestGraph
import se.pusslet.testing.TestGraph.Companion.ELLA
import se.pusslet.testing.TestGraph.Companion.SARA
import se.pusslet.testing.viewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PartyViewModelTest {
    private fun vm(g: TestGraph) = PartyViewModel(g.calendar, g.tasks, g.family, g.sessions, g.ids, g.clock, g.zone)

    private suspend fun TestGraph.party() = calendar.saveEvent(
        Event(
            "vera",
            "Kalas hos Vera",
            LocalDateTime(today.plusDays(2), LocalTime(14, 0)),
            LocalDateTime(today.plusDays(2), LocalTime(16, 0)),
            participantIds = listOf(ELLA),
            tags = listOf(EventTags.PARTY),
        ),
    )

    @Test
    fun answeringAndTheGiftAreSavedOnTheEvent() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.party()
        g.tasks.save(FamilyTask("present", "Köp present", linkedEventId = "vera"))
        val vm = vm(g)
        vm.load("vera")
        assertEquals("Sara", vm.state.value.myName)

        vm.update { it.copy(rsvp = Rsvp.Yes, rsvpBy = g.today.plusDays(1)) }
        vm.update(typing = true) { it.copy(gift = it.gift.copy(idea = "Pysselset")) }
        advanceTimeBy(1_000)
        vm.setBought(true)
        val saved = g.calendar.event("vera")?.party
        assertEquals(Rsvp.Yes, saved?.rsvp)
        assertEquals("Pysselset", saved?.gift?.idea, "typing is saved shortly after")
        assertEquals(true, saved?.gift?.bought)
        assertEquals(SARA, saved?.gift?.boughtBy)
        assertTrue(g.tasks.get("present")?.done == true, "the present to-do from quick add is ticked off too")
    }

    @Test
    fun ourOwnPartyHasAGuestListWhereAnswersGoRound() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.party()
        val vm = vm(g)
        vm.load("vera")
        vm.update { it.copy(own = true) }
        vm.addGuest("  Vera ")
        vm.addGuest("")
        vm.addGuest("Ali")
        val vera = vm.state.value.details.guests.first()
        vm.cycleGuest(vera.id)
        vm.cycleGuest(vera.id)
        val guests = g.calendar.events.first().single { it.id == "vera" }.party?.guests.orEmpty()
        assertEquals(listOf("Vera" to Rsvp.No, "Ali" to Rsvp.Pending), guests.map { it.name to it.rsvp })
        vm.removeGuest(vera.id)
        assertEquals(listOf("Ali"), g.calendar.event("vera")?.party?.guests?.map { it.name })
    }
}
