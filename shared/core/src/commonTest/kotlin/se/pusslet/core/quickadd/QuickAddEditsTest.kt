package se.pusslet.core.quickadd

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Aisle
import se.pusslet.core.shopping.AisleClassifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class QuickAddEditsTest {
    private val today = LocalDate(2026, 10, 8)

    @Test
    fun eventBecomesTaskWithDueDate() {
        val e = QuickAddAction.CreateEvent("Vinterdäck", LocalDate(2026, 10, 10))
        val t = assertIs<QuickAddAction.CreateTask>(QuickAddEdits.convert(e, ActionKind.Task, today))
        assertEquals("Vinterdäck", t.title)
        assertEquals(LocalDate(2026, 10, 10), t.due)
    }

    @Test
    fun taskBecomesShoppingItemsSplitOnCommas() {
        val t = QuickAddAction.CreateTask("2 gurkor, mjölk")
        val s = assertIs<QuickAddAction.AddShoppingItems>(QuickAddEdits.convert(t, ActionKind.Shopping, today))
        assertEquals(listOf("gurkor", "mjölk"), s.items.map { it.name })
        assertEquals(2.0, s.items.first().quantity?.value)
    }

    @Test
    fun undatedTaskBecomesEventToday() {
        val e = assertIs<QuickAddAction.CreateEvent>(QuickAddEdits.convert(QuickAddAction.CreateTask("Fika"), ActionKind.Event, today))
        assertEquals(today, e.date)
        assertNull(e.startTime)
    }

    @Test
    fun movingDateKeepsMultiDayLength() {
        val lov = QuickAddAction.CreateEvent("Höstlov", LocalDate(2026, 10, 26), endDate = LocalDate(2026, 10, 30))
        val moved = assertIs<QuickAddAction.CreateEvent>(QuickAddEdits.withDate(lov, LocalDate(2026, 11, 2)))
        assertEquals(LocalDate(2026, 11, 6), moved.endDate)
    }

    @Test
    fun changingStartKeepsLengthAndClearingMakesAllDay() {
        val e = QuickAddAction.CreateEvent("Kalas", today, LocalTime(14, 0), LocalTime(16, 0))
        val later = QuickAddEdits.withStart(e, LocalTime(15, 0))
        assertEquals(LocalTime(17, 0), later.endTime)
        val allDay = QuickAddEdits.withStart(e, null)
        assertNull(allDay.endTime)
    }

    @Test
    fun togglePeople() {
        val e = QuickAddAction.CreateEvent("Fotboll", today, participantIds = listOf("simon"))
        val both = assertIs<QuickAddAction.CreateEvent>(QuickAddEdits.togglePerson(e, "ella"))
        assertEquals(listOf("simon", "ella"), both.participantIds)
        val t = assertIs<QuickAddAction.CreateTask>(QuickAddEdits.togglePerson(QuickAddAction.CreateTask("Ring"), "johan"))
        assertEquals("johan", t.assigneeId)
    }

    @Test
    fun aisleClassification() {
        assertEquals(Aisle.Produce, AisleClassifier.classify("Bananer"))
        assertEquals(Aisle.Produce, AisleClassifier.classify("vitlök"))
        assertEquals(Aisle.Dairy, AisleClassifier.classify("Mellanmjölk"))
        assertEquals(Aisle.Canned, AisleClassifier.classify("krossade tomater"))
        assertEquals(Aisle.Meat, AisleClassifier.classify("nötfärs 500 g"))
        assertEquals(Aisle.Baby, AisleClassifier.classify("Blöjor storlek 5"))
        assertEquals(Aisle.Pharmacy, AisleClassifier.classify("Alvedon"))
        assertEquals(Aisle.Other, AisleClassifier.classify("Present till Vera"))
        assertEquals(Aisle.Frozen, AisleClassifier.classify("Bananer", mapOf("bananer" to Aisle.Frozen)))
    }
}
