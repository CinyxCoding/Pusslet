package se.pusslet.core.party

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toInstant
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.GiftPlan
import se.pusslet.core.model.PartyDetails
import se.pusslet.core.model.Role
import se.pusslet.core.model.Rsvp
import se.pusslet.core.notify.NotificationPayload
import se.pusslet.core.notify.NotifyFixtures.ella
import se.pusslet.core.notify.NotifyFixtures.event
import se.pusslet.core.notify.NotifyFixtures.input
import se.pusslet.core.notify.NotifyFixtures.wednesday
import se.pusslet.core.notify.NotifyFixtures.zone
import se.pusslet.core.notify.ReminderPlanner
import se.pusslet.core.time.plusDays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Kalas reminders (ADR-054). [wednesday] is 21 October 2026; the party is on Saturday. */
class PartyRemindersTest {
    private val saturday = wednesday.plusDays(3)

    private fun plan(details: PartyDetails, role: Role = Role.Parent): List<se.pusslet.core.notify.PlannedNotification> {
        val party = event("e-vera", "Kalas hos Vera", saturday, LocalTime(14, 0), LocalTime(16, 0), participants = listOf(ella.id))
            .copy(tags = listOf(EventTags.PARTY), party = details)
        val now = LocalDateTime(wednesday, LocalTime(8, 0)).toInstant(zone)
        return ReminderPlanner.plan(input(now = now, role = role, events = listOf(party))).filter { it.key.startsWith("party:") }
    }

    @Test
    fun theParentsAreRemindedOfThePresentAndTheAnswer() {
        val planned = plan(PartyDetails(rsvpBy = LocalDate(2026, 10, 22))).associateBy { it.key.substringBeforeLast(':') }
        val gift = planned.getValue("party:gift")
        assertEquals(LocalDateTime(wednesday, LocalTime(19, 0)).toInstant(zone), gift.at, "three days before, in the evening")
        assertEquals(NotificationPayload.PartyGift("e-vera", saturday, "Kalas hos Vera", saturday), gift.payload)
        assertEquals(LocalDateTime(wednesday, LocalTime(19, 0)).toInstant(zone), planned.getValue("party:rsvp").at, "the evening before")

        assertTrue(plan(PartyDetails(rsvp = Rsvp.Yes, gift = GiftPlan(bought = true))).isEmpty(), "answered and bought")
        assertTrue(plan(PartyDetails(rsvpBy = LocalDate(2026, 10, 22)), Role.Caregiver).isEmpty(), "helpers are not chased")
    }
}
