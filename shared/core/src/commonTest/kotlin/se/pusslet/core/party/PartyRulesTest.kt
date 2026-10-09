package se.pusslet.core.party

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.GiftPlan
import se.pusslet.core.model.PartyDetails
import se.pusslet.core.model.Rsvp
import se.pusslet.core.model.TaskCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartyRulesTest {
    private val today = LocalDate(2026, 10, 8)
    private val saturday = LocalDate(2026, 10, 10)

    private fun party(details: PartyDetails? = null, tags: List<String> = listOf(EventTags.PARTY)) = Event(
        "p1",
        "Kalas hos Vera",
        LocalDateTime(saturday, LocalTime(14, 0)),
        LocalDateTime(saturday, LocalTime(16, 0)),
        tags = tags,
        party = details,
    )

    private fun occ(e: Event) = EventOccurrence(e, e.start, e.end, e.start.date)

    @Test
    fun aTaggedPartyNeedsAPresentUntilItIsBoughtOrTickedOff() {
        val o = occ(party())
        assertTrue(PartyRules.needsGift(o, emptyList(), today))
        assertFalse(PartyRules.needsGift(occ(party(PartyDetails(gift = GiftPlan(bought = true)))), emptyList(), today))
        val done = FamilyTask("t", "Köp present", linkedEventId = "p1", done = true)
        assertFalse(PartyRules.needsGift(o, listOf(done), today), "the linked to-do is done")
        val open = FamilyTask("t", "Köp present", linkedEventId = "p1")
        assertTrue(PartyRules.needsGift(o, listOf(open), today))
        val other = FamilyTask("g", "Present till Vera", category = TaskCategory.Gift, due = LocalDate(2026, 10, 9), done = true)
        assertFalse(PartyRules.needsGift(o, listOf(other), today), "a gift to-do due before the party counts too")
    }

    @Test
    fun noPresentForADeclinedOrOwnPartyOrOneThatIsOver() {
        assertFalse(PartyRules.needsGift(occ(party(PartyDetails(rsvp = Rsvp.No))), emptyList(), today))
        assertFalse(PartyRules.needsGift(occ(party(PartyDetails(own = true))), emptyList(), today))
        assertFalse(PartyRules.needsGift(occ(party()), emptyList(), LocalDate(2026, 10, 11)))
        assertFalse(PartyRules.needsGift(occ(party(tags = emptyList())), emptyList(), today), "not a party")
        assertTrue(PartyRules.isParty(party(PartyDetails(), tags = emptyList())), "party details make it a party")
    }

    @Test
    fun rsvpIsNeededUntilAnsweredOrTooLate() {
        val by = LocalDate(2026, 10, 9)
        assertTrue(PartyRules.needsRsvp(party(PartyDetails(rsvpBy = by)), today))
        assertFalse(PartyRules.needsRsvp(party(PartyDetails(rsvpBy = by, rsvp = Rsvp.Yes)), today))
        assertFalse(PartyRules.needsRsvp(party(PartyDetails(rsvpBy = by)), LocalDate(2026, 10, 10)), "the date has passed")
        assertFalse(PartyRules.needsRsvp(party(), today), "no OSA date, nothing to chase")
        assertFalse(PartyRules.needsRsvp(party(PartyDetails(rsvpBy = by, own = true)), today), "we do not answer our own party")
    }

    @Test
    fun thePresentReminderIsThreeDaysBefore() {
        assertEquals(LocalDate(2026, 10, 7), PartyRules.giftReminderDay(occ(party()), LocalDate(2026, 10, 1)))
        assertNull(PartyRules.giftReminderDay(occ(party()), today), "already inside the three days: the Today card covers it")
    }
}
