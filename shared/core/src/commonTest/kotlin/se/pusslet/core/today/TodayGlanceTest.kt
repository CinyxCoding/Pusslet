package se.pusslet.core.today

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
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.PackingTrigger
import se.pusslet.core.model.Role
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.core.time.plusDays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodayGlanceTest {
    private val zone = TimeZone.of("Europe/Stockholm")
    private val tuesday = LocalDate(2026, 10, 13)
    private val sara = Member("sara", "Sara", Role.Parent, MemberColor.Sage)
    private val johan = Member("johan", "Johan", Role.Parent, MemberColor.Ocean)
    private val ella = Child("ella", "Ella", MemberColor.Rose)

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime(date, LocalTime(h, m))

    private val events = listOf(
        Event(
            "school",
            "Skola & fritids",
            at(tuesday, 8, 15),
            at(tuesday, 16, 30),
            rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
            participantIds = listOf("ella"),
            dropOff = Handoff("johan", LocalTime(8, 5)),
            pickUp = Handoff("sara"),
            tags = listOf("skola"),
        ),
        Event("meet1", "Styrelsemöte", at(tuesday, 16), at(tuesday, 17, 30), participantIds = listOf("sara")),
        Event("meet2", "Kundmöte", at(tuesday, 15, 45), at(tuesday, 17), participantIds = listOf("johan")),
        Event("dance", "Dans", at(tuesday.plusDays(1), 17, 15), at(tuesday.plusDays(1), 18, 15), participantIds = listOf("ella")),
        Event("autumn", "Höstlov-planering", at(tuesday, 0), at(tuesday.plusDays(1), 0), allDay = true),
    )

    private fun glance(now: LocalTime): TodayGlance {
        val plan = TodayPlanner.build(
            TodayInput(
                today = tuesday,
                now = now,
                zone = zone,
                me = "sara",
                people = listOf(FamilyPerson.of(sara), FamilyPerson.of(johan), FamilyPerson.of(ella)),
                children = listOf(ella),
                occurrences = EventExpander.expand(events, tuesday, tuesday.plusDays(4), zone),
                packingRules = listOf(PackingRule("gym", "Gympapåse", "ella", PackingTrigger.Weekly(setOf(DayOfWeek.TUESDAY)))),
            ),
        )
        return TodayGlancer.from(plan, now)
    }

    @Test
    fun morningShowsTheNextEventTheHandoffsAndWhatToBring() {
        val g = glance(LocalTime(6, 50))
        assertEquals("Kundmöte", g.next?.occurrence?.event?.title)
        assertEquals(false, g.nextIsNow)
        assertEquals(1, g.laterToday)
        assertEquals(listOf("DropOff Johan", "PickUp Sara"), g.handoffs.map { "${it.kind} ${it.person?.name}" })
        assertEquals(listOf("Gympapåse"), g.packing.single().items.map { it.text })
        assertEquals(listOf("Höstlov-planering"), g.allDay.map { it.event.title })
        assertNull(g.tomorrow)
        // The drop-off at 08:05 is the first thing that changes the widget.
        assertEquals(at(tuesday, 8, 5), g.refreshAt)
    }

    @Test
    fun anOngoingEventIsNowAndPassedHandoffsAreGone() {
        val g = glance(LocalTime(16, 10))
        assertEquals("Kundmöte", g.next?.occurrence?.event?.title)
        assertTrue(g.nextIsNow)
        assertEquals(1, g.laterToday)
        assertEquals(listOf(HandoffKind.PickUp), g.handoffs.map { it.kind })
        assertEquals(at(tuesday, 16, 30), g.refreshAt)
        assertTrue(glance(LocalTime(16, 30)).handoffs.isEmpty())
    }

    @Test
    fun eveningLooksAtTomorrowAndRefreshesAtMidnight() {
        val g = glance(LocalTime(17, 45))
        assertNull(g.next)
        assertTrue(g.handoffs.isEmpty())
        // Tomorrow comes from the day previews, which leave the everyday school day out.
        assertEquals("Dans", g.tomorrow?.event?.title)
        assertEquals(at(tuesday.plusDays(1), 0), g.refreshAt)
    }

    @Test
    fun checkedItemsAreLeftOut() {
        val plan = TodayPlanner.build(
            TodayInput(
                today = tuesday,
                now = LocalTime(7, 0),
                zone = zone,
                me = null,
                people = emptyList(),
                children = emptyList(),
                occurrences = emptyList(),
            ),
        )
        val packed = plan.copy(
            packing = listOf(
                ChildPacking(
                    FamilyPerson.of(ella),
                    listOf(
                        se.pusslet.core.model.PackingItem(
                            "gym",
                            "ella",
                            "Gympapåse",
                            se.pusslet.core.model.PackingReason.Rule("gym"),
                            checked = true,
                        ),
                    ),
                ),
            ),
        )
        val g = TodayGlancer.from(packed, LocalTime(7, 0))
        assertTrue(g.packing.isEmpty())
        assertTrue(g.isEmpty)
    }
}
