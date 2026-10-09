package se.pusslet.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import se.pusslet.core.model.Completion
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Role
import se.pusslet.core.model.TaskKind
import se.pusslet.core.tasks.KidChores
import se.pusslet.core.tasks.PendingApproval
import se.pusslet.core.tasks.TaskOccurrence
import se.pusslet.core.tasks.TaskScheduler
import se.pusslet.core.time.minusDays
import se.pusslet.core.time.plusDays
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.KidChoreActions
import kotlin.time.Clock

enum class TaskFilter { Mine, All, Chores }

data class TasksUiState(
    val loading: Boolean = true,
    val today: LocalDate? = null,
    val filter: TaskFilter = TaskFilter.Mine,
    val overdue: List<TaskOccurrence> = emptyList(),
    val todayItems: List<TaskOccurrence> = emptyList(),
    val upcoming: List<TaskOccurrence> = emptyList(),
    val undated: List<TaskOccurrence> = emptyList(),
    val done: List<TaskOccurrence> = emptyList(),
    val people: Map<String, FamilyPerson> = emptyMap(),
    val me: String? = null,
    val canEdit: Boolean = true,
    /** Children's "Klar!" waiting for a parent (barnläge). */
    val approvals: List<PendingApproval> = emptyList(),
    val hasChildren: Boolean = false,
) {
    val isEmpty: Boolean get() = overdue.isEmpty() && todayItems.isEmpty() && upcoming.isEmpty() && undated.isEmpty()
}

/**
 * "Att göra" and chores: what is overdue, due today, coming up (two weeks) or undated. Recurring things show today's
 * occurrence and the next one – not a wall of daily repeats. Rotating chores show who is up that week.
 */
class TasksViewModel(
    private val tasks: TaskRepository,
    family: FamilyRepository,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val filter = MutableStateFlow(TaskFilter.Mine)
    private val kid = KidChoreActions(tasks, clock)
    private val me get() = sessions.session.value.memberId
    private val role get() = sessions.session.value.role

    val state: StateFlow<TasksUiState> = combine(tasks.tasks, family.people, filter) { all, people, f -> build(all, people, f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TasksUiState())

    private fun build(all: List<FamilyTask>, people: List<FamilyPerson>, f: TaskFilter): TasksUiState {
        val today = clock.todayIn(zone)
        val visible = all.filter { t -> role != Role.Caregiver || t.sharedWithCaregivers }
        val occurrences = visible.flatMap { t -> relevant(t, today) }.filter { o ->
            when (f) {
                TaskFilter.Mine -> o.assigneeId == me || (o.assigneeId == null && o.task.kind == TaskKind.Task)
                TaskFilter.All -> true
                TaskFilter.Chores -> o.task.kind == TaskKind.Chore
            }
        }
        val open = occurrences.filter { !it.done }
        val order = compareBy<TaskOccurrence>({ it.date }, { it.task.title.lowercase() })
        return TasksUiState(
            loading = false,
            today = today,
            filter = f,
            overdue = open.filter { it.isBefore(today) }.sortedWith(order),
            todayItems = open.filter { it.date == today }.sortedWith(order),
            upcoming = open.filter { it.isAfter(today) }.sortedWith(order),
            undated = open.filter { it.date == null }.sortedBy { it.task.title.lowercase() },
            done = occurrences.filter { it.done }.sortedByDescending { it.task.doneAt ?: it.task.completions[it.key]?.doneAt },
            people = people.associateBy { it.id },
            me = me,
            canEdit = role != Role.Caregiver,
            approvals = if (role == Role.Parent) {
                KidChores.pending(all, people.filter { it.isChild }.map { it.id }.toSet(), zone)
            } else {
                emptyList()
            },
            hasChildren = people.any { it.isChild },
        )
    }

    /** Overdue (a week back), today and the next occurrence of a recurring task; one-off tasks as they are. */
    private fun relevant(t: FamilyTask, today: LocalDate): List<TaskOccurrence> {
        if (t.rrule == null) {
            // One-off tasks are always listed (whatever their date) until they have been done for a week.
            val doneLongAgo = t.done && (t.doneAt?.toLocalDateTime(zone)?.date ?: LocalDate.fromEpochDays(0)) < today.minusDays(DONE_DAYS)
            return if (doneLongAgo) emptyList() else listOf(TaskOccurrence(t, t.due, t.assigneeId, t.done))
        }
        val window = TaskScheduler.occurrences(t, today.minusDays(OVERDUE_DAYS), today.plusDays(HORIZON_DAYS))
        // Only the latest missed turn is overdue, and a missed daily habit is simply missed.
        val daily = t.rrule.orEmpty().contains("FREQ=DAILY")
        val past = listOfNotNull(window.lastOrNull { it.isBefore(today) && !it.done }?.takeIf { !daily })
        val current = window.filter { it.date == today }
        val next = window.firstOrNull { it.isAfter(today) }
        return past + current + listOfNotNull(next.takeIf { current.isEmpty() || current.all { it.done } })
    }

    private fun TaskOccurrence.isBefore(d: LocalDate) = date?.let { it < d } ?: false

    private fun TaskOccurrence.isAfter(d: LocalDate) = date?.let { it > d } ?: false

    fun approve(p: PendingApproval) {
        viewModelScope.launch { kid.approve(p) }
    }

    fun reject(p: PendingApproval) {
        viewModelScope.launch { kid.reject(p) }
    }

    fun setFilter(f: TaskFilter) {
        filter.value = f
    }

    fun setDone(o: TaskOccurrence, done: Boolean) {
        val now = clock.now()
        viewModelScope.launch {
            tasks.update(o.task.id) { t ->
                if (t.rrule != null && o.date != null) {
                    t.copy(completions = if (done) t.completions + (o.key to Completion(me, now)) else t.completions - o.key)
                } else {
                    t.copy(done = done, doneAt = if (done) now else null, doneBy = if (done) me else null)
                }
            }
        }
    }

    /** "Fixa cykeln" typed at the top: a task for me, no date. */
    fun quickAdd(title: String) {
        val text = title.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            tasks.save(
                FamilyTask(
                    id = ids.newId(),
                    title = text.replaceFirstChar { it.uppercase() },
                    assigneeId = me,
                    createdBy = me,
                    createdAt = clock.now(),
                ),
            )
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val HORIZON_DAYS = 14
        const val OVERDUE_DAYS = 7
        const val DONE_DAYS = 7
    }
}
