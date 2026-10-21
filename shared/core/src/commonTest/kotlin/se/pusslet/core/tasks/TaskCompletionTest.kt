package se.pusslet.core.tasks

import kotlinx.datetime.LocalDate
import se.pusslet.core.model.Completion
import se.pusslet.core.model.FamilyTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class TaskCompletionTest {
    private val at = Instant.parse("2026-10-20T07:00:00Z")
    private val day = LocalDate(2026, 10, 20)

    @Test
    fun aRecurringTaskIsTickedPerOccurrence() {
        val reading = FamilyTask("t", "Läsa", rrule = "FREQ=DAILY")
        val ticked = TaskCompletion.mark(reading, day, done = true, by = "m-sara", at = at)
        assertEquals(mapOf("2026-10-20" to Completion("m-sara", at)), ticked.completions)
        assertEquals(false, ticked.done)
        assertEquals(emptyMap(), TaskCompletion.mark(ticked, day, done = false, by = "m-sara", at = at).completions)
    }

    @Test
    fun aOneOffIsTickedAsAWhole() {
        val task = FamilyTask("t", "Ringa skolan")
        val ticked = TaskCompletion.mark(task, null, done = true, by = "m-johan", at = at)
        assertTrue(ticked.done)
        assertEquals("m-johan", ticked.doneBy)
        assertEquals(at, ticked.doneAt)
        val unticked = TaskCompletion.mark(ticked, null, done = false, by = "m-johan", at = at)
        assertEquals(task, unticked)
    }

    @Test
    fun undoPutsBackExactlyWhatWasThere() {
        val earlier = Completion("m-johan", Instant.parse("2026-10-20T06:00:00Z"))
        val reading = FamilyTask("t", "Läsa", rrule = "FREQ=DAILY", completions = mapOf("2026-10-20" to earlier))
        val undo = TaskCompletion.undoOf(reading, day)
        val unticked = TaskCompletion.mark(reading, day, done = false, by = "m-sara", at = at)
        assertEquals(reading, TaskCompletion.restore(unticked, undo))

        val task = FamilyTask("t2", "Ringa skolan")
        val undoTick = TaskCompletion.undoOf(task, null)
        assertEquals(task, TaskCompletion.restore(TaskCompletion.mark(task, null, true, "m-sara", at), undoTick))
    }
}
