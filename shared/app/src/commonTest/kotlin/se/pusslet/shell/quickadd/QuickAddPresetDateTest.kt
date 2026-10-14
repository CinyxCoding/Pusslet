package se.pusslet.shell.quickadd

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.core.quickadd.QuickAddAction
import se.pusslet.core.quickadd.QuickAddContext
import se.pusslet.core.quickadd.QuickAddResult
import se.pusslet.core.quickadd.SwedishQuickAddParser
import se.pusslet.core.quickadd.UncertainField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Quick add opened on a day in Kalender uses that day when the text names none. "Today" is Thursday 2026-10-08 at 10:00.
 */
class QuickAddPresetDateTest {
    private val parser = SwedishQuickAddParser()
    private val today = LocalDate(2026, 10, 8)
    private val saturday = LocalDate(2026, 10, 10)
    private val people = listOf(
        FamilyPerson("sara", "Sara", MemberColor.Sage, isChild = false, role = Role.Parent),
        FamilyPerson("johan", "Johan", MemberColor.Ocean, isChild = false, role = Role.Parent),
        FamilyPerson("simon", "Simon", MemberColor.Ochre, isChild = true),
    )
    private val ctx = QuickAddContext(today, LocalTime(10, 0), people, currentUserId = "sara")

    private fun parse(text: String, preset: LocalDate): QuickAddResult =
        QuickAddPresetDate.apply(parser, parser.parse(text, ctx), ctx, preset)

    @Test
    fun textWithoutDateLandsOnThePresetDay() {
        val r = parse("simskola 17:00", saturday)
        val event = assertIs<QuickAddAction.CreateEvent>(r.primary)
        assertEquals(saturday, event.date)
        assertEquals(LocalTime(17, 0), event.startTime)
        assertFalse(UncertainField.Date in r.uncertain, "the day came from the screen, not from a guess")
    }

    @Test
    fun aTimeThatHasPassedTodayStillUsesThePresetDay() {
        assertEquals(saturday, assertIs<QuickAddAction.CreateEvent>(parse("simskola 8:00", saturday).primary).date)
    }

    @Test
    fun aWrittenDateWins() {
        assertEquals(LocalDate(2026, 10, 9), assertIs<QuickAddAction.CreateEvent>(parse("tandläkare imorgon 9:00", saturday).primary).date)
        assertEquals(LocalDate(2026, 10, 12), assertIs<QuickAddAction.CreateEvent>(parse("frisör måndag 15:00", saturday).primary).date)
    }

    @Test
    fun todaysWeekdayIsWrittenAndStaysUncertain() {
        val r = parse("tandläkare torsdag 15", saturday)
        assertEquals(today, assertIs<QuickAddAction.CreateEvent>(r.primary).date)
        assertTrue(UncertainField.Date in r.uncertain)
    }

    @Test
    fun openingFromTodayChangesNothing() {
        val r = parse("simskola 8:00", today)
        assertEquals(parser.parse("simskola 8:00", ctx), r)
    }

    @Test
    fun handOffWithoutDateUsesThePresetDay() {
        assertEquals(saturday, assertIs<QuickAddAction.AssignHandoff>(parse("Johan hämtar", saturday).primary).date)
    }

    @Test
    fun shoppingHasNoDateToChange() {
        val r = parse("mjölk, bröd", saturday)
        assertEquals(parser.parse("mjölk, bröd", ctx), r)
    }
}
