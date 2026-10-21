package se.pusslet.core.tasks

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Role
import se.pusslet.core.time.minusDays
import kotlin.time.Instant

/** A recently ticked occurrence ("Klart"): who did it and when. [date] is the occurrence of a recurring task, else null. */
data class DoneTask(val task: FamilyTask, val date: LocalDate?, val doneBy: String?, val doneAt: Instant?) {
    val key: String get() = "${task.id}@${date ?: "once"}"
}

/**
 * The task list (Mer › Uppgifter), the answer first: what is mine, what nobody has, what the others have, and what was
 * done lately. One row per task (its [TaskScheduler.current] occurrence).
 */
data class TaskBoard(
    val mine: List<TaskOccurrence> = emptyList(),
    val unassigned: List<TaskOccurrence> = emptyList(),
    val others: List<TaskOccurrence> = emptyList(),
    val done: List<DoneTask> = emptyList(),
) {
    val isEmpty: Boolean get() = mine.isEmpty() && unassigned.isEmpty() && others.isEmpty() && done.isEmpty()
}

object TaskBoardPlanner {
    /** "Klart" shows what was ticked during the last week. */
    const val DONE_DAYS = 7

    /**
     * [people] are the ids of everyone in the family now: a task of someone who has left counts as nobody's, so it does not
     * disappear. A caregiver only gets the tasks shared with caregivers (as the rules, [se.pusslet.core.helper.CaregiverVisibility])
     * and, like parents, sees what nobody has among them.
     */
    fun build(
        tasks: List<FamilyTask>,
        me: String?,
        role: Role,
        people: Set<String>,
        today: LocalDate,
        zone: TimeZone,
    ): TaskBoard {
        val visible = if (role == Role.Parent) tasks else tasks.filter { it.sharedWithCaregivers }
        val open = visible.mapNotNull { TaskScheduler.current(it, today) }
            .map { o -> if (o.assigneeId != null && o.assigneeId !in people) o.copy(assigneeId = null) else o }
            .sortedWith(order(today))
        return TaskBoard(
            mine = open.filter { me != null && it.assigneeId == me },
            unassigned = open.filter { it.assigneeId == null },
            others = open.filter { it.assigneeId != null && it.assigneeId != me },
            done = done(visible, today, zone),
        )
    }

    /** Overdue first, then by day (undated last) and time, then title. */
    private fun order(today: LocalDate): Comparator<TaskOccurrence> = compareBy(
        { !TaskScheduler.isOverdue(it, today) },
        { it.date == null },
        { it.date },
        { it.task.dueTime },
        { it.task.title.lowercase() },
    )

    private fun done(tasks: List<FamilyTask>, today: LocalDate, zone: TimeZone): List<DoneTask> {
        val since = today.minusDays(DONE_DAYS)
        return tasks.flatMap { t ->
            if (t.rrule != null) {
                t.completions.mapNotNull { (key, c) ->
                    val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
                    // When it was ticked (this Saturday's vacuuming may be done on Tuesday); the occurrence date without a time.
                    val ticked = c.doneAt?.toLocalDateTime(zone)?.date ?: date
                    if (ticked < since || ticked > today) null else DoneTask(t, date, c.doneBy, c.doneAt)
                }
            } else {
                val at = t.doneAt
                if (t.done && at != null && at.toLocalDateTime(zone).date >= since) listOf(DoneTask(t, null, t.doneBy, at)) else emptyList()
            }
        }.sortedWith(compareByDescending<DoneTask> { it.doneAt ?: Instant.DISTANT_PAST }.thenByDescending { it.date })
    }
}
