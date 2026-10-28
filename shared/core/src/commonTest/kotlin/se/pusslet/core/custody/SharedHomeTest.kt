package se.pusslet.core.custody

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import se.pusslet.core.model.ChoreRotation
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.DietProfile
import se.pusslet.core.model.Event
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.Household
import se.pusslet.core.model.PackingChecks
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.PackingTrigger
import se.pusslet.core.time.isoWeek
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedHomeTest {
    // Ahmed's home: Ella lives here and with her mum; Noah is Ahmed's and Sara's and stays private.
    private val home = SharedHome(shared = setOf("ella"), children = setOf("ella", "noah"))

    private fun event(vararg people: String, pickUp: String? = null) = Event(
        id = "e",
        title = "Fotboll",
        start = LocalDateTime.parse("2026-10-13T17:30"),
        end = LocalDateTime.parse("2026-10-13T18:30"),
        participantIds = people.toList(),
        pickUp = pickUp?.let { Handoff(it) },
    )

    @Test
    fun eventsAboutOnlyTheSharedChildAreShared() {
        assertTrue(home.shares(event("ella", "ahmed")), "Ella's training, Ahmed drives")
        assertTrue(home.shares(event("ahmed", pickUp = "ella")), "picking Ella up")
        assertFalse(home.shares(event("ella", "noah")), "the siblings' dentist stays at home")
        assertFalse(home.shares(event("ahmed", "sara")), "the adults' dinner")
        assertFalse(home.shares(event()), "no one")
    }

    @Test
    fun tasksRulesAndListsFollowTheChild() {
        assertTrue(home.shares(FamilyTask("t", "Läxa: glosor", assigneeId = "ella")))
        assertFalse(home.shares(FamilyTask("t", "Städa", assigneeId = "ahmed")))
        val turns = ChoreRotation(listOf("ella", "noah"), anchorWeek = LocalDate(2026, 10, 5).isoWeek)
        assertFalse(home.shares(FamilyTask("t", "Duka", rotation = turns)), "a chore Ella and Noah take turns on")

        val gym = PackingRule("r", "Gympapåse", childId = "ella", trigger = PackingTrigger.Weekly(emptySet()))
        assertTrue(home.shares(gym))
        assertFalse(home.shares(gym.copy(childId = null)), "a rule for every child is each home's own")
        assertTrue(home.shares(PackingChecks(LocalDate(2026, 10, 13), "ella")))

        val schedule = CustodySchedule("c", listOf("ella"), listOf(Household("a", "Hos pappa")), anchorDate = LocalDate(2026, 10, 5))
        assertTrue(home.shares(schedule))
        assertFalse(home.shares(schedule.copy(childIds = listOf("noah"))))
    }

    @Test
    fun allergiesOnlyWhenTheParentSaysSoAndRequestsFollowTheirEvent() {
        val diet = DietProfile("ella")
        assertFalse(home.shares(diet, shareDiets = false))
        assertTrue(home.shares(diet, shareDiets = true))
        assertFalse(home.shares(DietProfile("noah"), shareDiets = true))

        val request = HandoffRequest("h", eventId = "e1", occurrenceDate = LocalDate(2026, 10, 13), fromMemberId = "ahmed")
        assertTrue(home.shares(request, sharedEventIds = setOf("e1")))
        assertFalse(home.shares(request, sharedEventIds = setOf("e2")))
    }
}
