package se.pusslet.core.tasks

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.TaskKind
import se.pusslet.core.recurrence.RRule
import se.pusslet.core.recurrence.RecurrenceExpander
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.minusDays

/** One concrete thing to do on a date (recurring tasks expanded, rotations resolved). */
data class TaskOccurrence(
    val task: FamilyTask,
    val date: LocalDate?,
    val assigneeId: String?,
    val done: Boolean,
) {
    val key: String get() = date?.toString() ?: "once"
}

object TaskScheduler {
    /** Who does a rotating chore in the week of [date]. */
    fun assigneeFor(task: FamilyTask, date: LocalDate): String? {
        val r = task.rotation ?: return task.assigneeId
        if (r.memberIds.isEmpty()) return task.assigneeId
        val weeks = ((date.isoWeek.monday.toEpochDays() - r.anchorWeek.monday.toEpochDays()) / 7).toInt()
        val period = IntMath.floorDiv(weeks, r.everyWeeks.coerceAtLeast(1))
        return r.memberIds[IntMath.floorMod(period, r.memberIds.size)]
    }

    /** Occurrences in [from]..[to]. Non-recurring tasks appear once (on their due date, or undated). */
    fun occurrences(task: FamilyTask, from: LocalDate, to: LocalDate): List<TaskOccurrence> {
        val rule = task.rrule?.let { RRule.parse(it) }
        if (rule == null) {
            if (task.due != null && (task.due < from.minusDays(365) || task.due > to)) return emptyList()
            return listOf(TaskOccurrence(task, task.due, task.assigneeId, task.done))
        }
        val anchor = task.due ?: task.rotation?.anchorWeek?.monday ?: from
        val start = LocalDateTime(anchor, LocalTime(0, 0))
        return RecurrenceExpander.expand(rule, start, from, to).map { dt ->
            val d = dt.date
            TaskOccurrence(task, d, assigneeFor(task, d), task.completions.containsKey(d.toString()))
        }
    }

    /** Open things for a person: due by [horizon] (overdue included) or undated. */
    fun openFor(memberId: String, tasks: List<FamilyTask>, today: LocalDate, horizon: LocalDate): List<TaskOccurrence> =
        tasks.flatMap { t ->
            if (t.rrule != null) occurrences(t, today, horizon) else occurrences(t, today.minusDays(365), horizon)
        }.filter { !it.done && it.assigneeId == memberId }
            .sortedWith(compareBy({ it.date == null }, { it.date }, { it.task.kind != TaskKind.Task }))

    fun isOverdue(o: TaskOccurrence, today: LocalDate): Boolean = !o.done && o.date != null && o.date < today
}

private object IntMath {
    fun floorDiv(a: Int, b: Int): Int = kotlin.math.floor(a.toDouble() / b).toInt()

    fun floorMod(a: Int, b: Int): Int = ((a % b) + b) % b
}
