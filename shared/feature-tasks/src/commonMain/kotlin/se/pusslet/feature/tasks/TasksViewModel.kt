package se.pusslet.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Role
import se.pusslet.core.tasks.DoneTask
import se.pusslet.core.tasks.TaskBoardPlanner
import se.pusslet.core.tasks.TaskCompletion
import se.pusslet.core.tasks.TaskOccurrence
import se.pusslet.core.tasks.TaskScheduler
import se.pusslet.core.tasks.TaskUndo
import se.pusslet.core.time.isoWeek
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.SessionProvider
import kotlin.time.Clock

/** One open row: "Dammsuga – din tur den här veckan", "Anmäl Ella till sportlovsläger · senast fre". */
data class TaskRow(
    val occurrence: TaskOccurrence,
    /** Null when nobody has it (or the one who had it has left). */
    val assignee: FamilyPerson?,
    val mine: Boolean,
    val overdue: Boolean,
    /** For a rotating chore: weeks from this week to the occurrence's week (0 this week, 1 next, -1 last), else null. */
    val turnWeekOffset: Int?,
    val turnWeek: Int?,
    val checklistDone: Int,
    val checklistTotal: Int,
) {
    val task: FamilyTask get() = occurrence.task
    val key: String get() = "${task.id}@${occurrence.key}"
}

/** One row under "Klart": the task, who ticked it and the occurrence (or the day it was ticked). */
data class DoneRow(val done: DoneTask, val by: FamilyPerson?, val day: LocalDate?)

data class TasksUiState(
    val loading: Boolean = true,
    val today: LocalDate? = null,
    /** Parents add, edit and see "Ingen ansvarig" and "Så har vi delat"; caregivers only tick what is shared. */
    val canEdit: Boolean = false,
    val mine: List<TaskRow> = emptyList(),
    /** "Ingen ansvarig" (parents only; a caregiver's shared tasks nobody has are under "Familjen"). */
    val unassigned: List<TaskRow> = emptyList(),
    val family: List<TaskRow> = emptyList(),
    val done: List<DoneRow> = emptyList(),
    /** The task the editor just deleted, for "… borttagen · Ångra". */
    val deleted: FamilyTask? = null,
) {
    val isEmpty: Boolean get() = !loading && mine.isEmpty() && unassigned.isEmpty() && family.isEmpty() && done.isEmpty()
}

/**
 * Mer › Uppgifter (BRIEF §4.7): mine first, then what nobody has, the rest of the family's and what was done lately. A tick
 * marks one occurrence and returns what "Ångra" needs.
 */
class TasksViewModel(
    private val tasks: TaskRepository,
    family: FamilyRepository,
    private val sessions: SessionProvider,
    private val deletedTasks: DeletedTasks,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    val state: StateFlow<TasksUiState> = combine(tasks.tasks, family.people, sessions.session, deletedTasks.last) {
        all,
        people,
        s,
        deleted,
        ->
        val today = clock.todayIn(zone)
        val parent = s.role == Role.Parent
        val byId = people.associateBy { it.id }
        val board = TaskBoardPlanner.build(all, s.memberId, s.role, byId.keys, today, zone)

        fun row(o: TaskOccurrence) = toRow(o, byId, s.memberId, today)
        TasksUiState(
            loading = false,
            today = today,
            canEdit = parent,
            mine = board.mine.map(::row),
            unassigned = if (parent) board.unassigned.map(::row) else emptyList(),
            family = (if (parent) board.others else board.unassigned + board.others).map(::row),
            done = board.done.map { d -> DoneRow(d, d.doneBy?.let(byId::get), d.date ?: d.doneAt?.toLocalDateTime(zone)?.date) },
            deleted = deleted.takeIf { parent },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TasksUiState())

    private fun toRow(o: TaskOccurrence, byId: Map<String, FamilyPerson>, me: String?, today: LocalDate): TaskRow {
        val rotating = TaskScheduler.isRotating(o.task)
        val week = o.date?.isoWeek ?: today.isoWeek
        val offset = if (rotating) ((week.monday.toEpochDays() - today.isoWeek.monday.toEpochDays()) / DAYS_PER_WEEK).toInt() else null
        return TaskRow(
            occurrence = o,
            assignee = o.assigneeId?.let(byId::get),
            mine = me != null && o.assigneeId == me,
            overdue = TaskScheduler.isOverdue(o, today),
            turnWeekOffset = offset,
            turnWeek = if (rotating) week.week else null,
            checklistDone = o.task.checklist.count { it.done },
            checklistTotal = o.task.checklist.size,
        )
    }

    /**
     * Ticks (or unticks) one occurrence. Returns what "Ångra" puts back, or null when nothing was changed. Caregivers may tick
     * the shared tasks they see (the rules allow exactly the done fields).
     */
    fun setDone(occurrence: TaskOccurrence, done: Boolean): TaskUndo? = setDone(occurrence.task, occurrence.date, done)

    /** Unticks a row under "Klart". */
    fun reopen(row: DoneRow): TaskUndo? = setDone(row.done.task, row.done.date, done = false)

    private fun setDone(task: FamilyTask, date: LocalDate?, done: Boolean): TaskUndo? {
        val session = sessions.session.value
        if (session.role != Role.Parent && !task.sharedWithCaregivers) return null
        val undo = TaskCompletion.undoOf(task, date)
        val now = clock.now()
        viewModelScope.launch { tasks.update(task.id) { TaskCompletion.mark(it, date, done, session.memberId, now) } }
        return undo
    }

    fun undo(undo: TaskUndo) {
        viewModelScope.launch { tasks.update(undo.taskId) { TaskCompletion.restore(it, undo) } }
    }

    /** "Ångra" on "… borttagen": saving the task again brings it back (deleting is soft). */
    fun restoreDeleted(task: FamilyTask) {
        deletedTasks.clear(task)
        viewModelScope.launch { tasks.save(task) }
    }

    fun deletedShown(task: FamilyTask) = deletedTasks.clear(task)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val DAYS_PER_WEEK = 7
    }
}
