package se.pusslet.core.tasks

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskRepeatsTest {
    private val saturday = LocalDate(2026, 10, 24)

    @Test
    fun choicesBecomeRulesOnTheDueDate() {
        assertEquals(null, TaskRepeats.ruleFor(TaskRepeat.None, saturday))
        assertEquals("FREQ=DAILY", TaskRepeats.ruleFor(TaskRepeat.Daily, saturday))
        assertEquals("FREQ=WEEKLY;BYDAY=SA", TaskRepeats.ruleFor(TaskRepeat.Weekly, saturday))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=SA", TaskRepeats.ruleFor(TaskRepeat.Biweekly, saturday))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=24", TaskRepeats.ruleFor(TaskRepeat.Monthly, saturday))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TH", TaskRepeats.ruleFor(TaskRepeat.Custom, saturday, "FREQ=WEEKLY;BYDAY=MO,TH"))
    }

    @Test
    fun rulesMapBackToTheChoices() {
        TaskRepeat.entries.filter { it != TaskRepeat.Custom }.forEach { choice ->
            assertEquals(choice to null, TaskRepeats.repeatOf(TaskRepeats.ruleFor(choice, saturday), saturday))
        }
        // Daily needs no date.
        assertEquals(TaskRepeat.Daily to null, TaskRepeats.repeatOf("FREQ=DAILY", null))
    }

    @Test
    fun anythingRicherIsKeptAsItIs() {
        val bins = "FREQ=WEEKLY;BYDAY=MO,TH"
        assertEquals(TaskRepeat.Custom to bins, TaskRepeats.repeatOf(bins, saturday))
        // On another day than the due date, or bounded, or without a date to compare with.
        assertEquals(TaskRepeat.Custom, TaskRepeats.repeatOf("FREQ=WEEKLY;BYDAY=SU", saturday).first)
        assertEquals(TaskRepeat.Custom, TaskRepeats.repeatOf("FREQ=WEEKLY;BYDAY=SA;COUNT=3", saturday).first)
        assertEquals(TaskRepeat.Custom, TaskRepeats.repeatOf("FREQ=WEEKLY;BYDAY=SA", null).first)
        assertEquals(TaskRepeat.Custom to "NOPE", TaskRepeats.repeatOf("NOPE", saturday))
    }
}
