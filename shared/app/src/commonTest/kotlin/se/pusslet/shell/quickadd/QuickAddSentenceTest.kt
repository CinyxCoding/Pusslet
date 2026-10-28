package se.pusslet.shell.quickadd

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.core.quickadd.ActionKind
import se.pusslet.core.quickadd.QuickAddAction
import se.pusslet.core.quickadd.QuickAddContext
import se.pusslet.core.quickadd.QuickAddResult
import se.pusslet.core.quickadd.SwedishQuickAddParser
import se.pusslet.core.quickadd.UncertainField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The quick-add preview as one sentence (UX plan 4.6), with the parser's guesses as their own parts so the sheet can
 * mark them in terracotta. "Today" is Thursday 2026-10-08 at 10:00; the words are the Swedish resources.
 */
class QuickAddSentenceTest {
    private val parser = SwedishQuickAddParser()
    private val today = LocalDate(2026, 10, 8)
    private val people = listOf(
        FamilyPerson("sara", "Sara", MemberColor.Sage, isChild = false, role = Role.Parent),
        FamilyPerson("johan", "Johan", MemberColor.Ocean, isChild = false, role = Role.Parent),
        FamilyPerson("ella", "Ella", MemberColor.Rose, isChild = true),
        FamilyPerson("simon", "Simon", MemberColor.Ochre, isChild = true),
    )
    private val ctx = QuickAddContext(today, LocalTime(10, 0), people, currentUserId = "sara")

    private val sv = SentenceWords(
        who = "%1\$s har %2\$s",
        labeled = "%1\$s: %2\$s",
        due = "senast %1\$s",
        pickUp = "%1\$s hämtar %2\$s",
        dropOff = "%1\$s lämnar %2\$s",
        sick = "%1\$s sjuk %2\$s",
        range = "%1\$s–%2\$s",
        allChildren = "barnen",
        today = "Idag",
        tomorrow = "Imorgon",
        weekdays = listOf("måndag", "tisdag", "onsdag", "torsdag", "fredag", "lördag", "söndag"),
        monthsShort = listOf("jan", "feb", "mar", "apr", "maj", "jun", "jul", "aug", "sep", "okt", "nov", "dec"),
        dateShort = "%1\$d %2\$s",
        listSeparator = ", ",
        andSeparator = " och ",
        kinds = mapOf(
            ActionKind.Event to "Händelse",
            ActionKind.Task to "Att göra",
            ActionKind.Shopping to "Till inköpslistan",
            ActionKind.Meal to "Till matsedeln",
            ActionKind.Handoff to "Hämta/lämna",
        ),
        mealSlots = mapOf(MealSlot.Breakfast to "Frukost", MealSlot.Lunch to "Lunch", MealSlot.Dinner to "Middag"),
    )

    private fun parse(text: String): QuickAddResult = parser.parse(text, ctx)

    private fun sentence(text: String, index: Int = 0, repeat: String? = null): List<SentencePart> {
        val r = parse(text)
        return QuickAddSentence.build(r.actions[index], if (index == 0) r.uncertain else emptySet(), text, people, today, sv, repeat)
    }

    private fun List<SentencePart>.plain() = joinToString("") { it.text }

    private fun List<SentencePart>.marked() = filter { it.uncertain }.map { it.text }

    @Test
    fun anEventReadsAsOneSentenceWithThePersonFirst() {
        val s = sentence("Ella dans tis 17")
        assertEquals("Ella har dans tisdag 17:00–18:00", s.plain())
        // A bare "17" is a guess: only the start time is marked.
        assertEquals(listOf("17:00"), s.marked())
    }

    @Test
    fun aClearEventHasNothingMarked() {
        val s = sentence("Ella och Simon simskola lör 10:00")
        assertEquals("Ella och Simon har simskola lördag 10:00–11:00", s.plain())
        assertTrue(s.marked().isEmpty())
    }

    @Test
    fun withoutAPersonTheTitleComesFirst() {
        assertEquals("Tandläkare imorgon 09:00–10:00", sentence("Tandläkare imorgon 9:00").plain())
        assertEquals("Studiedag måndag", sentence("studiedag måndag").plain())
    }

    @Test
    fun aGuessedDayIsMarked() {
        val s = sentence("simskola 17:00")
        assertEquals("Simskola idag 17:00–18:00", s.plain())
        assertEquals(listOf("idag"), s.marked())
    }

    @Test
    fun aRepeatReplacesTheDay() {
        val s = sentence("Simon fotboll tis 17:30 varje vecka", repeat = "Varje tisdag")
        assertEquals("Simon har fotboll varje tisdag 17:30–18:30", s.plain())
    }

    @Test
    fun aDateFurtherAwayIsShort() {
        assertEquals("Kalas 24 okt", sentence("Kalas 24 okt").plain())
    }

    @Test
    fun aTitleTypedWithACapitalKeepsIt() {
        val event = QuickAddAction.CreateEvent("IKEA", LocalDate(2026, 10, 10), participantIds = listOf("ella"))
        assertEquals("Ella har IKEA lördag", QuickAddSentence.build(event, emptySet(), "Ella IKEA lör", people, today, sv).plain())
    }

    @Test
    fun shoppingSaysWhereItGoes() {
        assertEquals("Till inköpslistan: Mjölk", sentence("Mjölk").plain())
        assertEquals("Till inköpslistan: mjölk, bröd", sentence("mjölk, bröd").plain())
    }

    @Test
    fun aGuessedKindIsMarked() {
        val s = sentence("Vinterdäck")
        assertEquals("Att göra: Vinterdäck", s.plain())
        assertEquals(listOf("Att göra"), s.marked())
    }

    @Test
    fun theLinkedTaskHasItsDueDay() {
        val text = "Ella kalas lör 14-16 hos Vera, köp present"
        assertEquals("Ella har kalas hos Vera lördag 14:00–16:00", sentence(text).plain())
        assertEquals("Att göra: Köp present senast imorgon", sentence(text, index = 1).plain())
    }

    @Test
    fun aMealSaysTheDay() {
        assertEquals("Till matsedeln: Tacos lördag", sentence("tacos lördag").plain())
        val lunch = QuickAddAction.PlanMeal(LocalDate(2026, 10, 9), "Pannkakor", MealSlot.Lunch)
        assertEquals("Till matsedeln: Pannkakor imorgon, lunch", QuickAddSentence.build(lunch, emptySet(), "", people, today, sv).plain())
    }

    @Test
    fun aHandOffNamesWhoAndMarksTheGuessedPerson() {
        val s = sentence("Hämta Simon mån 15:30")
        assertEquals("Sara hämtar Simon måndag 15:30", s.plain())
        assertEquals(listOf("Sara"), s.marked(), "nobody was named, so \"you\" is a guess")
        val johan = sentence("Johan lämnar imorgon")
        assertEquals("Johan lämnar barnen imorgon", johan.plain())
    }

    @Test
    fun everyUncertainFieldMarksItsOwnPart() {
        val event = QuickAddAction.CreateEvent(
            title = "Dans",
            date = LocalDate(2026, 10, 13),
            startTime = LocalTime(17, 0),
            endTime = LocalTime(18, 0),
            participantIds = listOf("ella"),
        )

        fun marked(vararg fields: UncertainField) =
            QuickAddSentence.build(event, fields.toSet(), "Ella dans", people, today, sv).marked()
        assertEquals(listOf("dans"), marked(UncertainField.Title))
        assertEquals(listOf("tisdag"), marked(UncertainField.Date))
        assertEquals(listOf("17:00"), marked(UncertainField.Time))
        assertEquals(listOf("18:00"), marked(UncertainField.EndTime))
        assertEquals(listOf("Ella"), marked(UncertainField.Person))
        assertEquals(listOf("Händelse"), marked(UncertainField.Kind))
        assertEquals(listOf("Ella", "17:00", "18:00"), marked(UncertainField.Person, UncertainField.Time, UncertainField.EndTime))
        assertTrue(marked().isEmpty())
    }

    @Test
    fun fillPutsPartsWhereThePlaceholdersAre() {
        val parts = QuickAddSentence.fill("%2\$s, %1\$s!", listOf(SentencePart("a")), listOf(SentencePart("b", uncertain = true)))
        assertEquals(listOf(SentencePart("b", true), SentencePart(", "), SentencePart("a"), SentencePart("!")), parts)
    }

    @Test
    fun normalizeDropsDoubleSpacesAndJoinsNeighbours() {
        val parts = QuickAddSentence.normalize(
            listOf(SentencePart(" Ella "), SentencePart(" har"), SentencePart(""), SentencePart(" dans ", true), SentencePart(" ")),
        )
        assertEquals(listOf(SentencePart("Ella har"), SentencePart(" dans", true)), parts)
    }

    @Test
    fun onlyTheAppsOwnWordsAreLowerCasedInASentence() {
        assertEquals("idag", QuickAddSentence.inSentence("Idag"))
        assertEquals("varje tisdag", QuickAddSentence.inSentence("Varje tisdag"))
        assertEquals("IKEA", QuickAddSentence.inSentence("IKEA"))
    }

    @Test
    fun aSickReportNamesTheChildrenAndTheDay() {
        assertEquals("Ella och Simon sjuk imorgon", sentence("Ella och Simon sjuka imorgon").plain())
    }
}
