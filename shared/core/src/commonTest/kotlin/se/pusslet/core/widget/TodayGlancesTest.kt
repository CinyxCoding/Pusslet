package se.pusslet.core.widget

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Child
import se.pusslet.core.model.Event
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.Member
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.PackingChecks
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.PackingTrigger
import se.pusslet.core.model.Role
import se.pusslet.core.notify.BriefPacking
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.core.time.plusDays
import se.pusslet.core.today.TodayInput
import se.pusslet.core.today.TodayPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Idag widget's facts, from the demo family's Thursday 8 October 2026 (as TodayHeroTest). */
class TodayGlancesTest {
    private val zone = TimeZone.of("Europe/Stockholm")
    private val thursday = LocalDate(2026, 10, 8)

    private val sara = Member("sara", "Sara", Role.Parent, MemberColor.Sage)
    private val johan = Member("johan", "Johan", Role.Parent, MemberColor.Ocean)
    private val bengt = Member("bengt", "Bengt", Role.Caregiver, MemberColor.Slate)
    private val ella = Child("ella", "Ella", MemberColor.Rose)
    private val simon = Child("simon", "Simon", MemberColor.Plum)

    private fun at(h: Int, m: Int = 0) = LocalDateTime(thursday, LocalTime(h, m))

    private val preschool = Event(
        "e-forskola",
        "Förskola",
        at(8),
        at(16),
        participantIds = listOf("simon"),
        dropOff = Handoff("johan", LocalTime(7, 50)),
        pickUp = Handoff("bengt", LocalTime(15, 30)),
        tags = listOf("förskola"),
    )
    private val school = Event(
        "e-skola",
        "Skola",
        at(8, 15),
        at(16),
        participantIds = listOf("ella"),
        dropOff = Handoff("johan", LocalTime(8, 5)),
        pickUp = Handoff("bengt", LocalTime(15, 45)),
        tags = listOf("skola"),
    )
    private val dentist = Event("e-tand", "Tandläkare", at(14), at(14, 30), participantIds = listOf("simon"))
    private val swimming = Event("e-sim", "Simskola", at(17, 30), at(18, 15), participantIds = listOf("ella"))
    private val themeDay = Event("e-tema", "Temadag", at(0), LocalDateTime(thursday.plusDays(1), LocalTime(0, 0)), allDay = true)
    private val gympa = PackingRule("p-gympa", "Gympapåse", "ella", PackingTrigger.Weekly(setOf(DayOfWeek.THURSDAY)))
    private val packed = mapOf(PackingChecks.docId(thursday, "ella") to PackingChecks(thursday, "ella", mapOf("rule:p-gympa" to true)))

    private fun glance(
        now: LocalTime,
        events: List<Event> = listOf(preschool, school, dentist, swimming, themeDay),
        checks: Map<String, PackingChecks> = emptyMap(),
    ) = TodayGlances.from(
        TodayPlanner.build(
            TodayInput(
                today = thursday,
                now = now,
                zone = zone,
                me = "sara",
                people = listOf(sara, johan, bengt).map(FamilyPerson::of) + listOf(ella, simon).map(FamilyPerson::of),
                children = listOf(ella, simon),
                occurrences = EventExpander.expand(events, thursday, thursday.plusDays(3), zone),
                packingRules = listOf(gympa),
                packingChecks = checks,
            ),
        ),
    )

    @Test
    fun theMorningHasTheNextEventTheHandoffsAndWhatToBring() {
        val g = glance(LocalTime(7, 10))
        assertEquals(GlanceEvent("e-tand", thursday, "Tandläkare", LocalTime(14, 0), listOf("Simon")), g.next, "school is a hand-off")
        assertEquals(1, g.later, "Simskola")
        assertEquals(listOf(HandoffKind.DropOff, HandoffKind.PickUp), g.handoffs.map { it.kind })
        assertEquals(listOf("Johan", "Bengt"), g.handoffs.map { it.person })
        assertEquals(listOf(BriefPacking("Ella", listOf("Gympapåse"))), g.packing)
        assertFalse(g.allPacked)
        assertEquals(LocalTime(7, 50), g.nextChange, "Johan leaves with Simon")
    }

    @Test
    fun whatHasStartedOrPassedMovesOn() {
        val afternoon = glance(LocalTime(14, 10))
        assertEquals("Simskola", afternoon.next?.title, "the dentist is under way")
        assertEquals(0, afternoon.later)
        assertEquals(listOf(HandoffKind.PickUp), afternoon.handoffs.map { it.kind })
        assertEquals(LocalTime(15, 30), afternoon.nextChange)

        val evening = glance(LocalTime(18, 0))
        assertEquals(GlanceEvent("e-tema", thursday, "Temadag", null, emptyList()), evening.next, "then the all-day event")
        assertTrue(evening.handoffs.isEmpty())
        assertNull(evening.nextChange, "nothing changes by itself before midnight")

        assertEquals(listOf(BriefPacking("Ella", listOf("Gympapåse"))), evening.packing, "unpacked things stay all day")
        val late = glance(LocalTime(18, 0), events = listOf(preschool, school, swimming), checks = packed)
        assertNull(late.next)
        assertTrue(late.isEmpty, "Inget mer idag")
    }

    @Test
    fun allPackedShowsUntilTheChildrenLeave() {
        val morning = glance(LocalTime(7, 10), checks = packed)
        assertTrue(morning.packing.isEmpty())
        assertTrue(morning.allPacked)
        assertFalse(glance(LocalTime(9, 0), checks = packed).allPacked, "Ella left at 08:05")
    }
}
