package se.pusslet.core.quickadd

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The example chips in quick add (UX plan 4.6, `quickadd_example_*` in sv and en, filled with a child's name) must parse
 * the way the chip's icon promises, or the first thing a new family tries looks broken. "Today" is Monday 2026-10-12.
 */
class ExampleChipParsingTest {
    private val parser = SwedishQuickAddParser()
    private val people = listOf(
        FamilyPerson("m-alva", "Alva", MemberColor.Sage, isChild = false, role = Role.Parent),
        FamilyPerson("c-noah", "Noah", MemberColor.Ochre, isChild = true),
        FamilyPerson("c-maja", "Maja", MemberColor.Plum, isChild = true),
    )
    private val ctx = QuickAddContext(LocalDate(2026, 10, 12), LocalTime(10, 0), people, currentUserId = "m-alva")

    private fun parse(text: String) = parser.parse(text, ctx)

    @Test
    fun eventChipIsAClearEventForTheChild() {
        for (text in listOf("Noah dans tis 17:00", "Noah dance Tue 17:00")) {
            val r = parse(text)
            val event = assertIs<QuickAddAction.CreateEvent>(r.primary, text)
            assertEquals(LocalDate(2026, 10, 13), event.date, text)
            assertEquals(LocalTime(17, 0), event.startTime, text)
            assertEquals(listOf("c-noah"), event.participantIds, text)
            assertTrue(r.uncertain.isEmpty(), "$text: ${r.uncertain}")
        }
    }

    @Test
    fun genericEventChipIsAClearEvent() {
        for (text in listOf("Frisör tor 9:30", "Haircut Thu 9:30")) {
            val r = parse(text)
            val event = assertIs<QuickAddAction.CreateEvent>(r.primary, text)
            assertEquals(LocalDate(2026, 10, 15), event.date, text)
            assertEquals(LocalTime(9, 30), event.startTime, text)
            assertTrue(r.uncertain.isEmpty(), "$text: ${r.uncertain}")
        }
    }

    @Test
    fun shoppingChipGoesToTheList() {
        for (text in listOf("Mjölk", "Milk")) {
            val r = parse(text)
            assertEquals(listOf(text), assertIs<QuickAddAction.AddShoppingItems>(r.primary, text).items.map { it.name })
            assertTrue(r.uncertain.isEmpty(), "$text: ${r.uncertain}")
        }
    }

    @Test
    fun handoffChipPicksUpTheChildAndOnlyGuessesWho() {
        for (text in listOf("Hämta Maja fre 15:30", "Pick up Maja Fri 15:30")) {
            val r = parse(text)
            val handoff = assertIs<QuickAddAction.AssignHandoff>(r.primary, text)
            assertEquals(HandoffKind.PickUp, handoff.kind, text)
            assertEquals(listOf("c-maja"), handoff.childIds, text)
            assertEquals(LocalDate(2026, 10, 16), handoff.date, text)
            assertEquals(LocalTime(15, 30), handoff.time, text)
            // Nobody is named, so "you" is a guess the preview marks.
            assertEquals("m-alva", handoff.personId, text)
            assertEquals(setOf(UncertainField.Person), r.uncertain, text)
        }
    }

    @Test
    fun mealChipGoesOnTheMenu() {
        for (text in listOf("Tacos fre", "Tacos Fri")) {
            val r = parse(text)
            val meal = assertIs<QuickAddAction.PlanMeal>(r.primary, text)
            assertEquals(LocalDate(2026, 10, 16), meal.date, text)
            assertTrue(r.uncertain.isEmpty(), "$text: ${r.uncertain}")
        }
    }

    @Test
    fun placeholderHintsParseToo() {
        for (text in listOf("Noah fotboll tis 17:30", "Noah football Tue 17:30", "fotboll tis 17:30", "football Tue 17:30")) {
            val r = parse(text)
            assertIs<QuickAddAction.CreateEvent>(r.primary, text)
            assertTrue(r.uncertain.isEmpty(), "$text: ${r.uncertain}")
        }
    }
}
