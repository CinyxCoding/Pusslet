package se.pusslet.feature.tasks

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
import se.pusslet.core.recurrence.RepeatOption
import se.pusslet.core.recurrence.SimpleRepeat
import se.pusslet.core.time.isoWeek
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.SessionProvider
import kotlin.time.Clock

data class TaskDraft(
    val title: String = "",
    val kind: TaskKind = TaskKind.Task,
    val category: TaskCategory = TaskCategory.General,
    val assigneeId: String? = null,
    val due: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val repeat: RepeatOption = RepeatOption.None,
    val customRule: String? = null,
    /** Chores: who takes turns, in order. Empty or one person = no rotation. */
    val rotation: List<String> = emptyList(),
    val rotationWeeks: Int = 1,
    val checklist: List<ChecklistItem> = emptyList(),
    val sharedWithCaregivers: Boolean = false,
    val note: String = "",
)

enum class TaskError { TitleRequired }

data class TaskEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val draft: TaskDraft = TaskDraft(),
    val adults: List<FamilyPerson> = emptyList(),
    val linkedEventTitle: String? = null,
    val today: LocalDate? = null,
    val errors: Set<TaskError> = emptySet(),
    val saving: Boolean = false,
    val done: Boolean = false,
)

class TaskEditorViewModel(
    private val tasks: TaskRepository,
    private val family: FamilyRepository,
    private val calendar: CalendarRepository,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val _state = MutableStateFlow(TaskEditorState())
    val state: StateFlow<TaskEditorState> = _state.asStateFlow()
    private var original: FamilyTask? = null
    private var loaded = false

    fun load(taskId: String?, chore: Boolean = false) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val adults = family.people.first().filter { !it.isChild }
            val task = taskId?.let { tasks.get(it) }
            original = task
            val draft = task?.let(::draftOf) ?: TaskDraft(
                kind = if (chore) TaskKind.Chore else TaskKind.Task,
                category = if (chore) TaskCategory.Household else TaskCategory.General,
                assigneeId = sessions.session.value.memberId,
                repeat = if (chore) RepeatOption.Weekly else RepeatOption.None,
                due = if (chore) clock.todayIn(zone) else null,
            )
            val linked = task?.linkedEventId?.let { calendar.event(it)?.title }
            _state.value = TaskEditorState(false, task == null, draft, adults, linked, clock.todayIn(zone))
        }
    }

    fun edit(transform: (TaskDraft) -> TaskDraft) = _state.update { it.copy(draft = transform(it.draft), errors = emptySet()) }

    /** Rotation order: tapping adds a person last, tapping again removes them. */
    fun toggleRotation(personId: String) = edit { d ->
        d.copy(rotation = if (personId in d.rotation) d.rotation - personId else d.rotation + personId)
    }

    fun addChecklistItem(text: String) {
        if (text.isBlank()) return
        edit { it.copy(checklist = it.checklist + ChecklistItem(ids.newId(), text.trim())) }
    }

    fun removeChecklistItem(id: String) = edit { d -> d.copy(checklist = d.checklist.filter { it.id != id }) }

    fun save() {
        val s = _state.value
        val d = s.draft
        if (d.title.isBlank()) {
            _state.update { it.copy(errors = setOf(TaskError.TitleRequired)) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            tasks.save(build(d))
            _state.update { it.copy(saving = false, done = true) }
        }
    }

    fun delete() {
        val id = original?.id ?: return
        viewModelScope.launch {
            tasks.delete(id)
            _state.update { it.copy(done = true) }
        }
    }

    private fun build(d: TaskDraft): FamilyTask {
        val base = original ?: FamilyTask(
            id = ids.newId(),
            title = "",
            createdBy = sessions.session.value.memberId,
            createdAt = clock.now(),
        )
        // A repeating task needs a start; without a chosen date it starts today.
        val start = d.due ?: clock.todayIn(zone).takeIf { d.repeat != RepeatOption.None }
        val rrule = start?.let { SimpleRepeat.ruleFor(d.repeat, it, d.customRule) }
        val rotation = if (d.kind == TaskKind.Chore && d.rotation.size >= 2 && start != null && rrule != null) {
            val anchor = base.rotation?.takeIf { it.memberIds == d.rotation && it.everyWeeks == d.rotationWeeks }?.anchorWeek
                ?: start.isoWeek
            ChoreRotation(d.rotation, d.rotationWeeks, anchor)
        } else {
            null
        }
        val rotates = rotation != null
        return base.copy(
            title = d.title.trim(),
            kind = d.kind,
            category = d.category,
            assigneeId = if (rotates) d.rotation.first() else d.assigneeId,
            due = start,
            dueTime = d.dueTime,
            rrule = rrule,
            checklist = d.checklist,
            rotation = rotation,
            sharedWithCaregivers = d.sharedWithCaregivers,
            note = d.note.trim(),
            // Turning a one-off task into a repeating one starts it fresh.
            done = if (rrule != null) false else base.done,
        )
    }

    private fun draftOf(t: FamilyTask): TaskDraft {
        val start = t.due ?: t.rotation?.anchorWeek?.monday
        val (repeat, custom) = if (start == null) RepeatOption.None to null else SimpleRepeat.repeatOf(t.rrule, start)
        return TaskDraft(
            title = t.title,
            kind = t.kind,
            category = t.category,
            assigneeId = t.assigneeId,
            due = start,
            dueTime = t.dueTime,
            repeat = repeat,
            customRule = custom,
            rotation = t.rotation?.memberIds.orEmpty(),
            rotationWeeks = t.rotation?.everyWeeks ?: 1,
            checklist = t.checklist,
            sharedWithCaregivers = t.sharedWithCaregivers,
            note = t.note,
        )
    }

    /** Helpers may only tick off shared tasks; the editor is for parents. */
    val canEdit: Boolean get() = sessions.session.value.role != Role.Caregiver
}
