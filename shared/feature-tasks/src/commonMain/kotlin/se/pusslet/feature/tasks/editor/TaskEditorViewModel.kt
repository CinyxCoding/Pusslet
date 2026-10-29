package se.pusslet.feature.tasks.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import se.pusslet.core.model.ChecklistItem
import se.pusslet.core.model.ChoreRotation
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Role
import se.pusslet.core.model.TaskCategory
import se.pusslet.core.model.TaskKind
import se.pusslet.core.tasks.TaskRepeat
import se.pusslet.core.tasks.TaskRepeats
import se.pusslet.core.tasks.TaskScheduler
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.isoWeek
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.feature.tasks.DeletedTasks
import kotlin.time.Clock

/** What the editor shows and changes. Turned into a [FamilyTask] by [TaskEditorViewModel.taskOf]. */
data class TaskDraft(
    val title: String = "",
    val kind: TaskKind = TaskKind.Task,
    val assigneeId: String? = null,
    val due: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val repeat: TaskRepeat = TaskRepeat.None,
    /** A rule the simple choices cannot show ("mån och tors"), kept as it is while [repeat] is [TaskRepeat.Custom]. */
    val customRule: String? = null,
    /** "Turas om" (chores only). */
    val rotationOn: Boolean = false,
    val rotationMembers: List<String> = emptyList(),
    val everyWeeks: Int = 1,
    /** The week the order starts in; a changed order starts this week. */
    val anchorWeek: IsoWeek? = null,
    /** "Byt vecka". */
    val turnOverrides: Map<String, String> = emptyMap(),
    val checklist: List<ChecklistItem> = emptyList(),
    val note: String = "",
    val sharedWithCaregivers: Boolean = false,
) {
    val rotating: Boolean get() = kind == TaskKind.Chore && rotationOn && rotationMembers.isNotEmpty()
}

enum class TaskEditorError { TitleRequired }

data class TaskEditorState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val draft: TaskDraft? = null,
    /** The draft as loaded: Back asks before throwing away anything else. */
    val initial: TaskDraft? = null,
    /** Who can be responsible or take turns: the adults, then the children. */
    val adults: List<FamilyPerson> = emptyList(),
    val children: List<FamilyPerson> = emptyList(),
    val me: String? = null,
    val thisWeek: IsoWeek? = null,
    /** Where the date picker opens when no date is set. */
    val today: LocalDate? = null,
    val errors: Set<TaskEditorError> = emptySet(),
    val saving: Boolean = false,
    val done: Boolean = false,
    /** Caregivers cannot change tasks (the rules refuse it); they never get here, but the editor says nothing then. */
    val canEdit: Boolean = true,
) {
    val hasChanges: Boolean get() = draft != null && draft != initial

    val everyone: List<FamilyPerson> get() = adults + children
}

/**
 * Creates and edits tasks and chores: title, who (or "Ingen"), due date and time, repeat, a rotation with "Byt vecka", a
 * checklist, a note and "Visa för hjälpare". Deleting keeps a copy in [DeletedTasks] for "Ångra" on the list.
 */
class TaskEditorViewModel(
    private val tasks: TaskRepository,
    private val family: FamilyRepository,
    private val sessions: SessionProvider,
    private val deleted: DeletedTasks,
    private val clock: Clock,
    private val zone: TimeZone,
    private val ids: IdGenerator,
) : ViewModel() {
    private val _state = MutableStateFlow(TaskEditorState())
    val state: StateFlow<TaskEditorState> = _state.asStateFlow()
    private var original: FamilyTask? = null

    fun load(taskId: String?) {
        if (_state.value.loaded) return
        viewModelScope.launch {
            val people = family.people.first()
            val adults = people.filter { !it.isChild }
            val children = people.filter { it.isChild }
            val today = clock.todayIn(zone)
            val existing = taskId?.let { tasks.get(it) }
            original = existing
            val draft = existing?.let { draftOf(it, today, people.map(FamilyPerson::id).toSet()) } ?: TaskDraft()
            val session = sessions.session.value
            _state.value = TaskEditorState(
                loaded = true,
                isNew = existing == null,
                draft = draft,
                initial = draft,
                adults = adults,
                children = children,
                me = session.memberId,
                thisWeek = today.isoWeek,
                today = today,
                canEdit = session.role == Role.Parent,
            )
        }
    }

    fun edit(transform: (TaskDraft) -> TaskDraft) = _state.update { s ->
        val next = s.draft?.let(transform) ?: return@update s
        s.copy(draft = next, errors = if (next.title.isNotBlank()) s.errors - TaskEditorError.TitleRequired else s.errors)
    }

    fun setTitle(title: String) = edit { it.copy(title = title) }

    fun setKind(kind: TaskKind) = edit { d ->
        // Turning a task into a chore offers the parents' rotation, ready to switch on.
        if (kind == TaskKind.Chore && d.rotationMembers.isEmpty()) d.copy(kind = kind, rotationMembers = parents()) else d.copy(kind = kind)
    }

    fun setAssignee(id: String?) = edit { it.copy(assigneeId = id) }

    /** A repeating task needs a first day: today, when none is chosen. */
    fun setRepeat(repeat: TaskRepeat) = edit { d ->
        d.copy(repeat = repeat, due = if (repeat != TaskRepeat.None && d.due == null) today() else d.due)
    }

    /** Without a date there is no time and no repeat. */
    fun setDue(date: LocalDate?) = edit { d ->
        if (date == null) d.copy(due = null, dueTime = null, repeat = TaskRepeat.None, customRule = null) else d.copy(due = date)
    }

    fun setDueTime(time: LocalTime?) = edit { it.copy(dueTime = time) }

    fun setRotationOn(on: Boolean) = edit { d ->
        val members = d.rotationMembers.ifEmpty { parents() }
        rotationChanged(d.copy(rotationOn = on, rotationMembers = members))
    }

    fun toggleRotationMember(id: String) = edit { d ->
        rotationChanged(d.copy(rotationMembers = if (id in d.rotationMembers) d.rotationMembers - id else d.rotationMembers + id))
    }

    fun moveRotationMember(index: Int, delta: Int) = edit { d ->
        rotationChanged(d.copy(rotationMembers = d.rotationMembers.moved(index, delta)))
    }

    fun setEveryWeeks(weeks: Int) = edit { d -> rotationChanged(d.copy(everyWeeks = weeks.coerceIn(1, MAX_EVERY_WEEKS))) }

    /** "Byt vecka": this week's turn and next week's change places (saved with the rest). */
    fun swapWeek() = edit { d ->
        val week = _state.value.thisWeek ?: return@edit d
        val swapped = TaskScheduler.swapWeeks(taskOf(d), week)
        d.copy(turnOverrides = swapped.turnOverrides)
    }

    fun addChecklistItem(text: String) = edit { d ->
        if (text.isBlank()) d else d.copy(checklist = d.checklist + ChecklistItem(ids.newId(), text.trim()))
    }

    fun removeChecklistItem(id: String) = edit { d -> d.copy(checklist = d.checklist.filterNot { it.id == id }) }

    fun moveChecklistItem(index: Int, delta: Int) = edit { d -> d.copy(checklist = d.checklist.moved(index, delta)) }

    fun toggleChecklistItem(id: String) = edit { d ->
        d.copy(checklist = d.checklist.map { if (it.id == id) it.copy(done = !it.done) else it })
    }

    /** The draft as a task (for the turn preview, and what [save] writes). */
    fun taskOf(d: TaskDraft): FamilyTask = apply(original ?: FamilyTask(id = "", title = ""), d)

    fun save() {
        val d = _state.value.draft ?: return
        if (!_state.value.canEdit) return
        if (d.title.isBlank()) {
            _state.update { it.copy(errors = it.errors + TaskEditorError.TitleRequired) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val base = original ?: FamilyTask(
                id = ids.newId(),
                title = "",
                category = if (d.kind == TaskKind.Chore) TaskCategory.Household else TaskCategory.General,
                createdBy = sessions.session.value.memberId,
                createdAt = clock.now(),
            )
            tasks.save(apply(base, d))
            _state.update { it.copy(saving = false, done = true) }
        }
    }

    /** Deletes the task; the list it returns to offers "Ångra". */
    fun delete() {
        val task = original ?: return
        if (!_state.value.canEdit) return
        viewModelScope.launch {
            tasks.delete(task.id)
            deleted.put(task)
            _state.update { it.copy(done = true) }
        }
    }

    private fun apply(base: FamilyTask, d: TaskDraft): FamilyTask {
        val rotating = d.rotating
        val due = d.due
        // A changed kind moves an everyday category along; gifts, school and the like stay.
        val category = when {
            d.kind == base.kind -> base.category
            d.kind == TaskKind.Chore && base.category == TaskCategory.General -> TaskCategory.Household
            d.kind == TaskKind.Task && base.category == TaskCategory.Household -> TaskCategory.General
            else -> base.category
        }
        return base.copy(
            title = d.title.trim(),
            kind = d.kind,
            category = category,
            assigneeId = if (rotating) null else d.assigneeId,
            due = due,
            dueTime = if (due == null) null else d.dueTime,
            // The series starts on the due date; without one nothing repeats ([setRepeat] picks today).
            rrule = due?.let { TaskRepeats.ruleFor(d.repeat, it, d.customRule) },
            rotation = if (rotating) ChoreRotation(d.rotationMembers, d.everyWeeks, d.anchorWeek ?: today().isoWeek) else null,
            turnOverrides = if (rotating) d.turnOverrides else emptyMap(),
            checklist = d.checklist,
            note = d.note.trim(),
            sharedWithCaregivers = d.sharedWithCaregivers,
        )
    }

    /** A changed rotation starts this week, in the new order; earlier "Byt vecka" no longer fits it. */
    private fun rotationChanged(d: TaskDraft): TaskDraft = d.copy(anchorWeek = today().isoWeek, turnOverrides = emptyMap())

    private fun parents(): List<String> = _state.value.adults.filter { it.role == Role.Parent }.map { it.id }

    private fun today(): LocalDate = clock.todayIn(zone)

    companion object {
        const val MAX_EVERY_WEEKS = 4

        /**
         * The draft for a stored task. A recurring task without a date (made before the editor set one) shows the occurrence
         * it is on now; a rotation keeps its start and only lists people who are still in the family.
         */
        fun draftOf(task: FamilyTask, today: LocalDate, people: Set<String>): TaskDraft {
            val due = task.due ?: if (TaskScheduler.isRecurring(task)) TaskScheduler.current(task, today)?.date else null
            val (repeat, custom) = TaskRepeats.repeatOf(task.rrule, due)
            val rotation = task.rotation
            return TaskDraft(
                title = task.title,
                kind = task.kind,
                assigneeId = task.assigneeId,
                due = due,
                dueTime = task.dueTime,
                repeat = repeat,
                customRule = custom,
                rotationOn = rotation != null && rotation.memberIds.isNotEmpty(),
                rotationMembers = rotation?.memberIds?.filter { it in people }.orEmpty(),
                everyWeeks = rotation?.everyWeeks?.coerceIn(1, MAX_EVERY_WEEKS) ?: 1,
                anchorWeek = rotation?.anchorWeek,
                turnOverrides = task.turnOverrides,
                checklist = task.checklist,
                note = task.note,
                sharedWithCaregivers = task.sharedWithCaregivers,
            )
        }

        private fun <T> List<T>.moved(index: Int, delta: Int): List<T> {
            val target = index + delta
            if (index !in indices || target !in indices) return this
            return toMutableList().apply { add(target, removeAt(index)) }
        }
    }
}
