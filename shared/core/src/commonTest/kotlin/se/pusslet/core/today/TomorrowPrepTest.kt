package se.pusslet.core.today

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Child
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealPlanWeek
import se.pusslet.core.model.Member
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.PackingChecks
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.PackingTrigger
import se.pusslet.core.model.Role
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kvällskollen: from 18:00 Idag gets tomorrow ready – hand-offs, what to pack tonight and dinner. */
class TomorrowPrepTest {
    private val zone = TimeZone.of("Europe/Stockholm")
    private val tuesday = LocalDate(2026, 10, 13)
    private val wednesday = tuesday.plusDays(1)
    private val ella = Child("c-ella", "Ella", MemberColor.Rose, birthDate = LocalDate(2017, 3, 14))
    private val sara = FamilyPerson.of(Member("m-sara", "Sara", Role.Parent, MemberColor.Sage))
    private val johan = FamilyPerson.of(Member("m-johan", "Johan", Role.Parent, MemberColor.Slate))

    private fun school(date: LocalDate, dropOff: String? = "m-johan", pickUp: String? = "m-sara"): EventOccurrence {
        val event = Event(
            id = "e-skola@$date",
            title = "Skola",
            start = LocalDateTime(date, LocalTime(8, 15)),
            end = LocalDateTime(date, LocalTime(15, 30)),
            participantIds = listOf(ella.id),
            dropOff = Handoff(dropOff, LocalTime(8, 5)),
            pickUp = Handoff(pickUp, LocalTime(15, 45)),
            tags = listOf("skola"),
        )
        return EventOccurrence(event, event.start, event.end, occurrenceDate = date)
    }

    /** Gympapåse on Wednesdays. */
    private val gym = PackingRule("p-gym", "Gympapåse", ella.id, PackingTrigger.Weekly(setOf(DayOfWeek.WEDNESDAY)))

    private fun input(now: LocalTime, occurrences: List<EventOccurrence> = listOf(school(tuesday), school(wednesday))) = TodayInput(
        today = tuesday,
        now = now,
        zone = zone,
        me = "m-sara",
        people = listOf(sara, johan, FamilyPerson.of(ella)),
        children = listOf(ella),
        occurrences = occurrences,
        packingRules = listOf(gym),
    )

    @Test
    fun beforeSixThereIsNoKvallskoll() {
        assertNull(TodayPlanner.build(input(LocalTime(17, 59))).tomorrow)
    }

    @Test
    fun fromSixTomorrowsHandoffsAndPackingAreReady() {
        val prep = assertNotNull(TodayPlanner.build(input(LocalTime(18, 0))).tomorrow)

        assertEquals(wednesday, prep.date)
        // Tomorrow's hand-offs are kept although their clock time has "passed" today.
        assertEquals(listOf(HandoffKind.DropOff, HandoffKind.PickUp), prep.handoffs.map { it.kind })
        assertEquals("Johan", prep.handoffs.first().person?.name)
        assertTrue(prep.handoffs.last().isMe, "Du hämtar Ella 15:45")
        assertEquals(PackingState.ToPack, prep.packing.state)
        assertEquals(listOf("Gympapåse"), prep.packing.children.single().items.map { it.text })
    }

    @Test
    fun packedTonightCountsForTomorrow() {
        val key = assertNotNull(TodayPlanner.build(input(LocalTime(19, 0))).tomorrow).packing.children.single().items.single().key
        val checked = PackingChecks(wednesday, ella.id, mapOf(key to true))
        val withCheck = input(LocalTime(19, 0)).copy(packingChecks = mapOf(checked.docId to checked))
        val prep = assertNotNull(TodayPlanner.build(withCheck).tomorrow)

        assertEquals(PackingState.AllPacked, prep.packing.state)
    }

    @Test
    fun aHandoffWithAProblemStaysUnderAttLosa() {
        val nobody = input(LocalTime(18, 30), listOf(school(tuesday), school(wednesday, pickUp = null)))
        val plan = TodayPlanner.build(nobody)
        val prep = assertNotNull(plan.tomorrow)

        assertEquals(listOf(HandoffKind.DropOff), prep.handoffs.map { it.kind })
        assertTrue(plan.problems.any { it.date == wednesday })
    }

    @Test
    fun tomorrowsDinnerIsIncluded() {
        val week = MealPlanWeek(wednesday.isoWeek).with(MealEntry(wednesday, title = "Tacos"))
        val prep = assertNotNull(TodayPlanner.build(input(LocalTime(20, 0)).copy(mealWeeks = listOf(week))).tomorrow)

        assertEquals("Tacos", prep.dinner?.title)
    }

    @Test
    fun aQuietTomorrowNeedsNoKvallskoll() {
        val quiet = input(LocalTime(20, 0), listOf(school(tuesday))).copy(packingRules = emptyList())

        assertNull(TodayPlanner.build(quiet).tomorrow)
    }
}
