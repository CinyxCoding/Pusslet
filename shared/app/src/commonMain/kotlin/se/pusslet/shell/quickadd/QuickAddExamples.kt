package se.pusslet.shell.quickadd

import se.pusslet.core.model.FamilyPerson

/** What an example chip in quick add shows; each kind has its own icon and string (`quickadd_example_*`). */
enum class QuickAddExampleKind { Event, Shopping, Handoff, Meal }

/**
 * One example chip. [name] is a child of this family ("Noah dans tis 17:00"); null uses the text without a name
 * (`quickadd_example_event_generic`). The names always come from the family, never from the demo (UX plan 4.6).
 */
data class QuickAddExample(val kind: QuickAddExampleKind, val name: String? = null)

/**
 * The example chips that teach quick add (UX plan 4.6): at most [MAX], built from the family's own children, and gone once
 * this phone has saved [LEARNED_AFTER] entries. Pure, so the "no demo names" rule is unit tested.
 */
internal object QuickAddExamples {
    const val MAX = 3
    const val LEARNED_AFTER = 3

    /**
     * True once the field has been used [LEARNED_AFTER] times on this phone. [recentCount] (the remembered texts) covers
     * phones that used quick add before the counter existed: every remembered text was saved at least once.
     */
    fun learned(saves: Int, recentCount: Int): Boolean = maxOf(saves, recentCount) >= LEARNED_AFTER

    /** With children: an activity for the first child, milk and a pick-up of the second (or the same) child. */
    fun forFamily(people: List<FamilyPerson>): List<QuickAddExample> {
        val children = childNames(people)
        val first = children.firstOrNull() ?: return listOf(
            QuickAddExample(QuickAddExampleKind.Event),
            QuickAddExample(QuickAddExampleKind.Shopping),
            QuickAddExample(QuickAddExampleKind.Meal),
        )
        return listOf(
            QuickAddExample(QuickAddExampleKind.Event, first),
            QuickAddExample(QuickAddExampleKind.Shopping),
            QuickAddExample(QuickAddExampleKind.Handoff, children.getOrElse(1) { first }),
        ).take(MAX)
    }

    fun visible(people: List<FamilyPerson>, saves: Int, recentCount: Int): List<QuickAddExample> =
        if (learned(saves, recentCount)) emptyList() else forFamily(people)

    /** The child named in the field's placeholder (`quickadd_hint`); null means `quickadd_hint_generic`. */
    fun hintName(people: List<FamilyPerson>): String? = childNames(people).firstOrNull()

    private fun childNames(people: List<FamilyPerson>): List<String> =
        people.filter { it.isChild }.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct()
}
