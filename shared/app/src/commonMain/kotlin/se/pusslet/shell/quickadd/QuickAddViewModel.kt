package se.pusslet.shell.quickadd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.quickadd.ActionKind
import se.pusslet.core.quickadd.QuickAddAction
import se.pusslet.core.quickadd.QuickAddContext
import se.pusslet.core.quickadd.QuickAddEdits
import se.pusslet.core.quickadd.QuickAddResult
import se.pusslet.core.quickadd.SwedishQuickAddParser
import se.pusslet.core.quickadd.UncertainField
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.QuickAddExecutor
import se.pusslet.data.usecase.QuickAddOutcome
import kotlin.time.Clock

data class QuickAddUiState(
    val text: String = "",
    val actions: List<QuickAddAction> = emptyList(),
    val uncertain: Set<UncertainField> = emptySet(),
    val people: List<FamilyPerson> = emptyList(),
    val recent: List<String> = emptyList(),
    val today: LocalDate? = null,
    val saving: Boolean = false,
) {
    val canSave: Boolean get() = actions.isNotEmpty() && !saving && actions.all { a ->
        when (a) {
            is QuickAddAction.CreateEvent -> a.title.isNotBlank()
            is QuickAddAction.CreateTask -> a.title.isNotBlank()
            is QuickAddAction.PlanMeal -> a.title.isNotBlank()
            is QuickAddAction.AddShoppingItems -> a.items.isNotEmpty()
            is QuickAddAction.AssignHandoff -> true
        }
    }
}

/**
 * The global "+" field: parse while typing (rule based, ADR-001), let the user correct any field with one tap,
 * then save everything at once. Corrections are thrown away if the text changes (they belong to the old text).
 */
class QuickAddViewModel(
    private val parser: SwedishQuickAddParser,
    private val executor: QuickAddExecutor,
    private val family: FamilyRepository,
    private val prefs: PreferencesRepository,
    private val sessions: SessionProvider,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val _state = MutableStateFlow(QuickAddUiState())
    val state: StateFlow<QuickAddUiState> = _state.asStateFlow()
    private var parseJob: Job? = null

    init {
        viewModelScope.launch { family.people.collect { p -> _state.update { it.copy(people = p) } } }
        viewModelScope.launch { prefs.observe(Prefs.RecentQuickAdds).collect { r -> _state.update { it.copy(recent = r) } } }
    }

    fun start(prefill: String = "") {
        _state.update { it.copy(text = prefill, actions = emptyList(), uncertain = emptySet(), saving = false) }
        if (prefill.isNotBlank()) parseNow(prefill)
    }

    fun onTextChange(text: String) {
        _state.update { it.copy(text = text) }
        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            delay(PARSE_DEBOUNCE_MS)
            parseNow(text)
        }
    }

    private fun parseNow(text: String) {
        val now = clock.now().toLocalDateTime(zone)
        val result = parser.parse(text, QuickAddContext(now.date, now.time, _state.value.people, sessions.session.value.memberId))
        _state.update { it.copy(actions = result.actions, uncertain = result.uncertain, today = now.date) }
    }

    private fun edit(index: Int, clearField: UncertainField? = null, transform: (QuickAddAction) -> QuickAddAction) {
        _state.update { s ->
            if (index !in s.actions.indices) return@update s
            val next = s.actions.toMutableList().also { it[index] = transform(it[index]) }
            s.copy(actions = next, uncertain = if (clearField != null && index == 0) s.uncertain - clearField else s.uncertain)
        }
    }

    fun convert(index: Int, kind: ActionKind) {
        val today = _state.value.today ?: clock.now().toLocalDateTime(zone).date
        edit(index, UncertainField.Kind) { QuickAddEdits.convert(it, kind, today) }
    }

    fun setTitle(index: Int, title: String) = edit(index, UncertainField.Title) { QuickAddEdits.withTitle(it, title) }

    fun setDate(index: Int, date: LocalDate) = edit(index, UncertainField.Date) { QuickAddEdits.withDate(it, date) }

    fun setStart(index: Int, time: LocalTime?) = edit(index, UncertainField.Time) {
        if (it is QuickAddAction.CreateEvent) QuickAddEdits.withStart(it, time) else it
    }

    fun setEnd(index: Int, time: LocalTime) = edit(index, UncertainField.EndTime) {
        if (it is QuickAddAction.CreateEvent) QuickAddEdits.withEnd(it, time) else it
    }

    fun togglePerson(index: Int, personId: String) = edit(index, UncertainField.Person) { QuickAddEdits.togglePerson(it, personId) }

    fun removeAction(index: Int) {
        _state.update { s -> s.copy(actions = s.actions.filterIndexed { i, _ -> i != index }) }
    }

    fun save(onSaved: (QuickAddOutcome) -> Unit) {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val outcome = executor.execute(QuickAddResult(s.text, s.actions, s.uncertain))
            _state.update { QuickAddUiState(people = it.people, recent = it.recent) }
            onSaved(outcome)
        }
    }

    fun undo(outcome: QuickAddOutcome) {
        viewModelScope.launch { executor.undo(outcome) }
    }

    private companion object {
        const val PARSE_DEBOUNCE_MS = 120L
    }
}
