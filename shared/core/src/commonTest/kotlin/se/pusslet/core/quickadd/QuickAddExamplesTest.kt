package se.pusslet.core.quickadd

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The examples the quick-add sheet suggests (quickadd_example_1..4 in values/ and values-en/) must do what they
 * promise, in both languages.
 */
class QuickAddExamplesTest {
    private val people = listOf(
        FamilyPerson("sara", "Sara", MemberColor.Sage, isChild = false, role = Role.Parent),
        FamilyPerson("johan", "Johan", MemberColor.Ocean, isChild = false, role = Role.Parent),
        FamilyPerson("ella", "Ella", MemberColor.Rose, isChild = true),
    )
    private val ctx = QuickAddContext(LocalDate(2026, 10, 8), LocalTime(10, 0), people, currentUserId = "sara")

    private fun parse(s: String) = SwedishQuickAddParser().parse(s, ctx).actions

    private fun checkExamples(party: String, shopping: String, pickUp: String, dinner: String) {
        val partyActions = parse(party)
        val event = assertIs<QuickAddAction.CreateEvent>(partyActions[0], party)
        assertEquals(LocalDate(2026, 10, 10), event.date)
        assertEquals(LocalTime(14, 0), event.startTime)
        assertEquals(listOf("ella"), event.participantIds)
        assertIs<QuickAddAction.CreateTask>(partyActions[1], "the present becomes a to-do")

        assertEquals(3, assertIs<QuickAddAction.AddShoppingItems>(parse(shopping).single(), shopping).items.size)
        assertEquals("johan", assertIs<QuickAddAction.AssignHandoff>(parse(pickUp).single(), pickUp).personId)
        assertEquals(LocalDate(2026, 10, 9), assertIs<QuickAddAction.PlanMeal>(parse(dinner).single(), dinner).date)
    }

    @Test
    fun swedishExamples() = checkExamples(
        "Ella kalas lör 14-16 hos Vera, köp present",
        "mjölk, 2 gurka, pasta",
        "Johan hämtar imorgon",
        "tacos fredag",
    )

    @Test
    fun englishExamples() =
        checkExamples(
            "Ella party Sat 14-16 at Vera's, buy a present",
            "milk, 2 cucumbers, pasta",
            "Johan picks up tomorrow",
            "tacos Friday",
        )
}
