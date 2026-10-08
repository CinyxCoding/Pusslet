package se.pusslet.core.conflicts

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Event
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.core.recurrence.EventExpander
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConflictDetectorTest {
    private val date = LocalDate(2026, 10, 8)
    private val zone = TimeZone.of("Europe/Stockholm")
    private val people = listOf(
        FamilyPerson("sara", "Sara", MemberColor.Sage, false, Role.Parent),
        FamilyPerson("johan", "Johan", MemberColor.Ocean, false, Role.Parent),
        FamilyPerson("bengt", "Bengt", MemberColor.Slate, false, Role.Caregiver),
        FamilyPerson("ella", "Ella", MemberColor.Rose, true),
        FamilyPerson("simon", "Simon", MemberColor.Ochre, true),
    )

    private fun ev(id: String, from: String, to: String, who: List<String>, pickUp: Handoff? = null, dropOff: Handoff? = null) = Event(
        id = id,
        title = id,
        start = LocalDateTime.parse("2026-10-08T$from"),
        end = LocalDateTime.parse("2026-10-08T$to"),
        participantIds = who,
        pickUp = pickUp,
        dropOff = dropOff,
    )

    private fun run(vararg events: Event) =
        ConflictDetector.detect(date, EventExpander.expand(events.toList(), date, date, zone), people)

    @Test
    fun bothParentsInMeetingsNobodyCanPickUp() {
        val conflicts = run(
            ev("fritids", "08:00", "16:30", listOf("ella"), pickUp = Handoff(null)),
            ev("Saras möte", "16:00", "17:00", listOf("sara")),
            ev("Johans möte", "16:30", "17:30", listOf("johan")),
        )
        val c = assertIs<Conflict.NobodyAvailable>(conflicts.single())
        assertEquals(listOf("sara", "johan"), c.busyAdultIds)
        assertEquals(LocalTime(16, 30), c.time)
        assertEquals(listOf("ella"), c.childIds)
    }

    @Test
    fun unassignedWhenSomeoneIsFree() {
        val conflicts = run(
            ev("förskola", "08:00", "16:00", listOf("simon"), pickUp = Handoff(null)),
            ev("Saras möte", "15:30", "16:30", listOf("sara")),
        )
        assertIs<Conflict.Unassigned>(conflicts.single())
    }

    @Test
    fun assignedPersonIsBusy() {
        val conflicts = run(
            ev("förskola", "08:00", "16:00", listOf("simon"), pickUp = Handoff("johan")),
            ev("Tandläkare", "15:45", "16:30", listOf("johan")),
        )
        val c = assertIs<Conflict.AssigneeBusy>(conflicts.single())
        assertEquals("johan", c.personId)
        assertEquals(HandoffKind.PickUp, c.kind)
    }

    @Test
    fun explicitPickupTimeAndDropOff() {
        val conflicts = run(
            ev("skola", "08:15", "14:00", listOf("ella"), dropOff = Handoff("sara"), pickUp = Handoff("bengt", LocalTime(15, 0))),
            ev("Saras frukostmöte", "07:30", "08:30", listOf("sara")),
            ev("Bengts lunch", "11:00", "12:00", listOf("bengt")),
        )
        val c = assertIs<Conflict.AssigneeBusy>(conflicts.single())
        assertEquals(HandoffKind.DropOff, c.kind)
    }

    @Test
    fun noConflictWhenAssigneeIsFreeOrEventIsTheirOwnWithTheChild() {
        assertTrue(
            run(
                ev("förskola", "08:00", "16:00", listOf("simon"), pickUp = Handoff("johan")),
                ev("Simon simskola", "16:00", "17:00", listOf("simon", "johan")),
            ).none { it is Conflict.AssigneeBusy },
        )
    }

    @Test
    fun childDoubleBooked() {
        val conflicts = run(
            ev("fotboll", "17:00", "18:00", listOf("ella")),
            ev("dans", "17:30", "18:30", listOf("ella")),
        )
        assertIs<Conflict.DoubleBooked>(conflicts.single())
    }

    @Test
    fun eventsWithoutHandoffsDoNotNeedPickup() {
        assertTrue(run(ev("fotboll", "17:00", "18:00", listOf("ella"))).isEmpty())
    }
}
