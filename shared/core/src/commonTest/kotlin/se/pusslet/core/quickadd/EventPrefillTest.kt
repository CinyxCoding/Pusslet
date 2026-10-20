package se.pusslet.core.quickadd

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.recurrence.Frequency
import se.pusslet.core.recurrence.RRule
import se.pusslet.core.recurrence.WeekdayNum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Fyll i själv" opens the full event editor with what was typed (UX plan 4.6). */
class EventPrefillTest {
    private val friday = LocalDate(2026, 10, 16)
    private val opened = LocalDate(2026, 10, 12)

    @Test
    fun anEventKeepsEverythingTheUserSees() {
        val event = QuickAddAction.CreateEvent(
            title = "Dans",
            date = friday,
            startTime = LocalTime(17, 0),
            endTime = LocalTime(18, 0),
            participantIds = listOf("c-noah"),
            recurrence = RRule(Frequency.WEEKLY, byDay = listOf(WeekdayNum(DayOfWeek.FRIDAY))),
            location = "Kulturhuset",
            tags = listOf("träning"),
            packingItems = listOf("Dansskor"),
        )
        val p = EventPrefill.from(listOf(event), "Noah dans fre 17-18 varje vecka", opened)
        assertEquals("Dans", p.title)
        assertEquals(friday, p.date)
        assertFalse(p.allDay)
        assertEquals(LocalTime(17, 0), p.startTime)
        assertEquals(LocalTime(18, 0), p.endTime)
        assertEquals(listOf("c-noah"), p.participantIds)
        assertEquals("FREQ=WEEKLY;BYDAY=FR", p.rrule)
        assertEquals("Kulturhuset", p.location)
        assertEquals(listOf("träning"), p.tags)
        assertEquals(listOf("Dansskor"), p.packingItems)
    }

    @Test
    fun onlyARealAllDayEventIsAllDay() {
        val studyDay = QuickAddAction.CreateEvent("Studiedag", friday)
        assertTrue(EventPrefill.from(listOf(studyDay), "studiedag fre", opened).allDay)
        val task = QuickAddAction.CreateTask("Ring farmor")
        val p = EventPrefill.from(listOf(task), "ring farmor", opened)
        assertFalse(p.allDay, "no time understood is not the same as all day")
        assertNull(p.startTime)
        assertEquals("Ring farmor", p.title)
        assertEquals(opened, p.date)
    }

    @Test
    fun onlyTheFirstActionCounts() {
        val actions = listOf(
            QuickAddAction.CreateEvent("Kalas", friday, LocalTime(14, 0), LocalTime(16, 0)),
            QuickAddAction.CreateTask("Köp present", linkedToEvent = true),
        )
        assertEquals("Kalas", EventPrefill.from(actions, "kalas fre 14-16, köp present", opened).title)
    }

    @Test
    fun aHandOffGetsWhatWasTypedAsTitle() {
        val handoff = QuickAddAction.AssignHandoff("m-alva", HandoffKind.PickUp, friday, listOf("c-maja"), LocalTime(15, 30))
        val p = EventPrefill.from(listOf(handoff), "  hämta Maja   fre 15:30 ", opened)
        assertEquals("Hämta Maja fre 15:30", p.title)
        assertEquals(friday, p.date)
        assertEquals(LocalTime(15, 30), p.startTime)
        assertEquals(listOf("c-maja"), p.participantIds)
    }

    @Test
    fun shoppingAndMealsKeepTheirWordsAndDay() {
        val items = QuickAddAction.AddShoppingItems(listOf(ParsedItem("Mjölk")))
        assertEquals("Mjölk", EventPrefill.from(listOf(items), "Mjölk", opened).title)
        val meal = QuickAddAction.PlanMeal(friday, "Tacos")
        val p = EventPrefill.from(listOf(meal), "tacos fre", opened)
        assertEquals("Tacos", p.title)
        assertEquals(friday, p.date)
    }

    @Test
    fun nothingParsedStillOpensTheEditorOnTheDay() {
        val p = EventPrefill.from(emptyList(), "", opened)
        assertEquals("", p.title)
        assertEquals(opened, p.date)
    }
}
