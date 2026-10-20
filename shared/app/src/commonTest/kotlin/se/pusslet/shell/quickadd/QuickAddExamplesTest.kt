package se.pusslet.shell.quickadd

import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The example chips in quick add (UX plan 4.6): the family's own names, at most 3, gone after 3 saves. */
class QuickAddExamplesTest {
    private fun adult(id: String, name: String) = FamilyPerson(id, name, MemberColor.Sage, isChild = false, role = Role.Parent)

    private fun child(id: String, name: String) = FamilyPerson(id, name, MemberColor.Ochre, isChild = true)

    private val alva = adult("m-alva", "Alva")
    private val omar = adult("m-omar", "Omar")
    private val noah = child("c-noah", "Noah")
    private val maja = child("c-maja", "Maja")

    @Test
    fun aFamilyWithChildrenGetsTheirNames() {
        val examples = QuickAddExamples.forFamily(listOf(alva, omar, noah, maja))
        assertEquals(
            listOf(
                QuickAddExample(QuickAddExampleKind.Event, "Noah"),
                QuickAddExample(QuickAddExampleKind.Shopping),
                QuickAddExample(QuickAddExampleKind.Handoff, "Maja"),
            ),
            examples,
        )
        assertEquals("Noah", QuickAddExamples.hintName(listOf(alva, omar, noah, maja)))
    }

    @Test
    fun anOnlyChildIsInBothNamedChips() {
        val examples = QuickAddExamples.forFamily(listOf(alva, noah))
        assertEquals(listOf("Noah", null, "Noah"), examples.map { it.name })
        assertEquals(listOf(QuickAddExampleKind.Event, QuickAddExampleKind.Shopping, QuickAddExampleKind.Handoff), examples.map { it.kind })
    }

    @Test
    fun aFamilyWithoutChildrenGetsExamplesWithoutNames() {
        val examples = QuickAddExamples.forFamily(listOf(alva, omar))
        assertEquals(
            listOf(
                QuickAddExample(QuickAddExampleKind.Event),
                QuickAddExample(QuickAddExampleKind.Shopping),
                QuickAddExample(QuickAddExampleKind.Meal),
            ),
            examples,
        )
        assertTrue(examples.all { it.name == null }, "no pick-up example and no adult names")
        assertNull(QuickAddExamples.hintName(listOf(alva, omar)), "the placeholder uses quickadd_hint_generic")
        assertEquals(QuickAddExamples.forFamily(emptyList()), examples, "a brand-new family is the same")
    }

    @Test
    fun neverMoreThanThreeChips() {
        val big = listOf(alva, omar) + (1..6).map { child("c-$it", "Barn $it") }
        assertTrue(QuickAddExamples.forFamily(big).size <= QuickAddExamples.MAX)
        assertTrue(QuickAddExamples.forFamily(emptyList()).size <= QuickAddExamples.MAX)
    }

    @Test
    fun blankNamesAreSkipped() {
        val examples = QuickAddExamples.forFamily(listOf(alva, child("c-x", "  "), noah))
        assertEquals("Noah", examples.first().name)
        assertEquals("Noah", QuickAddExamples.hintName(listOf(child("c-x", ""), noah)))
    }

    /** "Klart när" for 4.6: no demo name ever shows in a family that does not have it. */
    @Test
    fun noDemoNameInAFamilyWithoutThem() {
        val family = listOf(adult("m-1", "Fatima"), adult("m-2", "Jonas"), child("c-1", "Liam"), child("c-2", "Alice"))
        val texts = QuickAddExamples.forFamily(family).map(::chipText) + hintText(QuickAddExamples.hintName(family))
        for (text in texts) {
            val hit = DEMO_NAMES.firstOrNull { text.contains(it, ignoreCase = true) }
            assertNull(hit, "\"$text\" shows the demo name $hit")
        }
        val names = QuickAddExamples.forFamily(family).mapNotNull { it.name }.toSet()
        assertTrue(names.all { name -> family.any { it.name == name } }, "$names are not all from the family")
    }

    @Test
    fun noNamesAtAllWithoutChildren() {
        val family = listOf(adult("m-1", "Fatima"))
        val texts = QuickAddExamples.forFamily(family).map(::chipText) + hintText(QuickAddExamples.hintName(family))
        for (text in texts) assertNull(DEMO_NAMES.firstOrNull { text.contains(it, ignoreCase = true) }, text)
        assertTrue(texts.none { it.contains("Fatima") }, "adults are never in the examples")
    }

    @Test
    fun theExamplesGoAwayAfterThreeSaves() {
        val family = listOf(alva, noah)
        assertEquals(3, QuickAddExamples.visible(family, saves = 0, recentCount = 0).size)
        assertEquals(3, QuickAddExamples.visible(family, saves = 2, recentCount = 2).size)
        assertTrue(QuickAddExamples.visible(family, saves = 3, recentCount = 1).isEmpty())
        assertTrue(QuickAddExamples.visible(family, saves = 0, recentCount = 3).isEmpty(), "a phone that used quick add before")
    }

    /** The Swedish chip texts (`quickadd_example_*`, values/strings.xml), filled the way the sheet fills them. */
    private fun chipText(example: QuickAddExample): String = when (example.kind) {
        QuickAddExampleKind.Event -> example.name?.let { "$it dans tis 17:00" } ?: "Frisör tor 9:30"
        QuickAddExampleKind.Shopping -> "Mjölk"
        QuickAddExampleKind.Handoff -> "Hämta ${example.name} fre 15:30"
        QuickAddExampleKind.Meal -> "Tacos fre"
    }

    private fun hintText(name: String?) = name?.let { "T.ex. $it fotboll tis 17:30" } ?: "T.ex. fotboll tis 17:30"

    private companion object {
        /** Everyone in the demo family "Familjen Lind" (DemoSeed) and the old example texts. */
        val DEMO_NAMES = listOf("Sara", "Johan", "Bengt", "Morfar", "Ella", "Simon", "Vera", "Ahmed", "Karin", "Lind")
    }
}
