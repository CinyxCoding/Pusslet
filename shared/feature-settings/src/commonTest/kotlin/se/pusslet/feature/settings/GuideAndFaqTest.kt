package se.pusslet.feature.settings

import se.pusslet.core.model.Role
import se.pusslet.data.session.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The guide through the app and the common questions (ADR-062): who gets which pages and answers. */
class GuideAndFaqTest {
    private val parent = Session(familyId = "f", memberId = "m-sara", role = Role.Parent)
    private val caregiver = Session(familyId = "f", memberId = "m-bengt", role = Role.Caregiver)

    @Test
    fun bothGuidesStartWithTheWelcomeAndHaveUniquePages() {
        GuideAudience.entries.forEach { audience ->
            val ids = GuidePages.forAudience(audience).map { it.id }
            assertEquals("welcome", ids.first())
            assertEquals(ids.distinct(), ids, "$audience has a page twice")
        }
    }

    @Test
    fun parentsAreTakenThroughEveryPartOfTheApp() {
        val ids = GuidePages.forAudience(GuideAudience.Parents).map { it.id }
        val parts = "today add calendar handoffs meals shopping family two-homes tasks sick medicine notifications help".split(" ")
        assertEquals(emptyList(), parts - ids.toSet())
        assertEquals("help", ids.last(), "The last page says where the guide and the questions are")
    }

    /** The helper view has no +, no Mer and none of the parents' own pages (ADR-029, ADR-050). */
    @Test
    fun otherAdultsOnlyHearAboutTheHelperView() {
        val ids = GuidePages.forAudience(GuideAudience.Helpers).map { it.id }
        listOf("add", "medicine", "two-homes", "sick", "family").forEach { assertFalse(it in ids, "$it is for parents") }
        assertTrue("picked-up" in ids && "requests" in ids)
    }

    @Test
    fun questionsHaveUniqueIds() {
        val ids = Faq.entries.map { it.id }
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun otherAdultsAreNotAskedAboutWhatOnlyParentsCanDo() {
        val shown = Faq.groupsFor(caregiver).flatMap { it.second }
        assertTrue(shown.none { it.shownTo == FaqFor.Parents })
        assertTrue(shown.any { it.id == "picked-up" })
        // A group with nothing left in it is not shown at all.
        assertFalse(Faq.groupsFor(caregiver).any { it.first == FaqGroup.Calendar })
    }

    @Test
    fun parentsGetEveryQuestionButTheExampleFamilysInGroupOrder() {
        val groups = Faq.groupsFor(parent)
        assertEquals(FaqGroup.entries.toList(), groups.map { it.first })
        val ids = groups.flatMap { pair -> pair.second.map { it.id } }
        assertFalse("demo" in ids)
        assertEquals(Faq.entries.size - 1, ids.size)
    }

    @Test
    fun theExampleFamilyIsToldHowToLeaveIt() {
        val ids = Faq.groupsFor(parent.copy(demo = true)).flatMap { pair -> pair.second.map { it.id } }
        assertTrue("demo" in ids)
    }
}
