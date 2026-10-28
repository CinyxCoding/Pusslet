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

/** Names, words and items with å, ä and ö, in lower and upper case. "Today" is Thursday 2026-10-08. */
class SwedishLettersQuickAddTest {
    private val parser = SwedishQuickAddParser()
    private val people = listOf(
        FamilyPerson("asa", "Åsa", MemberColor.Sage, isChild = false, role = Role.Parent),
        FamilyPerson("orjan", "Örjan", MemberColor.Ocean, isChild = false, role = Role.Parent),
        FamilyPerson("marta", "Märta", MemberColor.Rose, isChild = true),
        FamilyPerson("bjorn", "Björn", MemberColor.Ochre, isChild = true),
    )
    private val ctx = QuickAddContext(LocalDate(2026, 10, 8), LocalTime(10, 0), people, currentUserId = "asa")

    private fun parse(s: String) = parser.parse(s, ctx)

    @Test
    fun childWithÅÄÖInNameGetsTheEvent() {
        val e = assertIs<QuickAddAction.CreateEvent>(parse("Märta fotboll tis 17:30").primary)
        assertEquals(listOf("marta"), e.participantIds)
        assertEquals("Fotboll", e.title)
    }

    @Test
    fun adultWithÅInNamePicksUp() {
        val h = assertIs<QuickAddAction.AssignHandoff>(parse("Åsa hämtar Björn imorgon").primary)
        assertEquals(HandoffKind.PickUp, h.kind)
        assertEquals("asa", h.personId)
    }

    @Test
    fun upperCaseÅÄÖIsUnderstood() {
        val h = assertIs<QuickAddAction.AssignHandoff>(parse("ÖRJAN HÄMTAR MÄRTA IMORGON").primary)
        assertEquals(HandoffKind.PickUp, h.kind)
        assertEquals("orjan", h.personId)
    }

    @Test
    fun shoppingItemsKeepTheirLetters() {
        val items = assertIs<QuickAddAction.AddShoppingItems>(parse("smör, 2 ägg, ärtor, örtsalt, ål").primary).items
        assertEquals(listOf("smör", "ägg", "ärtor", "örtsalt", "ål"), items.map { it.name })
    }

    @Test
    fun mealWithÄ() {
        val m = assertIs<QuickAddAction.PlanMeal>(parse("ärtsoppa torsdag").primary)
        assertEquals("Ärtsoppa", m.title)
    }
}
