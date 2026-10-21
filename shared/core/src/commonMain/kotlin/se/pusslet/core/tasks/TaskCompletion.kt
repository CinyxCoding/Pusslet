package se.pusslet.core.tasks

import kotlinx.datetime.LocalDate
import se.pusslet.core.model.Completion
import se.pusslet.core.model.FamilyTask
import kotlin.time.Instant

/**
 * What one tick changed, so "Ångra" can put it back exactly: the occurrence's completion of a recurring task, or the done
 * fields of a one-off. Made by [TaskCompletion.undoOf] before the change.
 */
data class TaskUndo(
    val taskId: String,
    val date: LocalDate?,
    val recurring: Boolean,
    val completion: Completion? = null,
    val done: Boolean = false,
    val doneAt: Instant? = null,
    val doneBy: String? = null,
)

/** Ticking one occurrence off (or on again). Same rule as Idag: a recurring task stores a completion per occurrence date. */
object TaskCompletion {
    fun mark(task: FamilyTask, date: LocalDate?, done: Boolean, by: String?, at: Instant): FamilyTask =
        if (task.rrule != null && date != null) {
            val key = date.toString()
            task.copy(completions = if (done) task.completions + (key to Completion(by, at)) else task.completions - key)
        } else {
            task.copy(done = done, doneAt = if (done) at else null, doneBy = if (done) by else null)
        }

    fun undoOf(task: FamilyTask, date: LocalDate?): TaskUndo = if (task.rrule != null && date != null) {
        TaskUndo(task.id, date, recurring = true, completion = task.completions[date.toString()])
    } else {
        TaskUndo(task.id, date, recurring = false, done = task.done, doneAt = task.doneAt, doneBy = task.doneBy)
    }

    fun restore(task: FamilyTask, undo: TaskUndo): FamilyTask = if (undo.recurring && undo.date != null) {
        val key = undo.date.toString()
        task.copy(completions = undo.completion?.let { task.completions + (key to it) } ?: (task.completions - key))
    } else {
        task.copy(done = undo.done, doneAt = undo.doneAt, doneBy = undo.doneBy)
    }
}
