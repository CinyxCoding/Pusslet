package se.pusslet.shell.quickadd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.quickadd.ActionKind
import se.pusslet.core.quickadd.EventPrefill
import se.pusslet.core.quickadd.QuickAddAction
import se.pusslet.core.quickadd.QuickAddContext
import se.pusslet.core.quickadd.QuickAddEdits
import se.pusslet.core.quickadd.QuickAddResult
import se.pusslet.core.quickadd.SwedishQuickAddParser
import se.pusslet.core.quickadd.UncertainField
import se.pusslet.core.time.plusDays
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
    /** Example chips built from the family's own names, until this phone has saved 3 entries (UX plan 4.6). */
    val examples: List<QuickAddExample> = emptyList(),
    /** The child named in the placeholder ("T.ex. Noah fotboll tis 17:30"); null when the family has no children. */
    val hintName: String? = null,
    /** Actions whose fields are open after "Ändra"; the others show only their sentence. */
    val editing: Set<Int> = emptySet(),
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
 * The global "+" field: parse while typing (rule based, ADR-001), show each action as one sentence whose fields open with
 * "Ändra", then save everything at once. Corrections are thrown away if the text changes (they belong to the old text).
 * Until this phone has saved 3 entries, example chips with the family's own names show how to write (UX plan 4.6).
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

    /** The day the opening screen shows (Kalender's selected day), used when the text names no date of its own. */
    private var presetDate: LocalDate? = null

    init {
        viewModelScope.launch {
            combine(family.people, prefs.observe(Prefs.QuickAddSaves), prefs.observe(Prefs.RecentQuickAdds)) { people, saves, recent ->
                Triple(people, QuickAddExamples.visible(people, saves, recent.size), QuickAddExamples.hintName(people))
            }.collect { (people, examples, hint) -> _state.update { it.copy(people = people, examples = examples, hintName = hint) } }
        }
    }

    /**
     * Opens quick add. [initialText] prefills the field (text shared from another app). [date] is the day the opening
     * screen shows: "simskola 17:00" typed on Kalender's Saturday lands on Saturday, while "imorgon" or "fredag" still win.
     */
    fun start(initialText: String? = null, date: LocalDate? = null) {
        val prefill = initialText.orEmpty()
        presetDate = date
        _state.update { it.copy(text = prefill, actions = emptyList(), uncertain = emptySet(), editing = emptySet(), saving = false) }
        if (prefill.isNotBlank()) parseNow(prefill)
    }

    /** Today in the family's time zone, for the confirmation ("I kalendern lör 14:00"). */
    fun today(): LocalDate = clock.now().toLocalDateTime(zone).date

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
        val ctx = QuickAddContext(now.date, now.time, _state.value.people, sessions.session.value.memberId)
        val parsed = parser.parse(text, ctx)
        val preset = presetDate
        val result = if (preset != null) QuickAddPresetDate.apply(parser, parsed, ctx, preset) else parsed
        _state.update {
            it.copy(
                actions = result.actions,
                uncertain = result.uncertain,
                // An open "Ändra" stays open while the user keeps typing, as long as that action is still there.
                editing = it.editing.filterTo(mutableSetOf()) { i -> i < result.actions.size },
                today = now.date,
            )
        }
    }

    /** "Ändra" opens the fields of one action; tapping it again ("Klar") closes them. */
    fun toggleEditing(index: Int) {
        _state.update { s ->
            if (index !in s.actions.indices) return@update s
            s.copy(editing = if (index in s.editing) s.editing - index else s.editing + index)
        }
    }

    /**
     * "Fyll i själv": what the full event editor starts from. The text is parsed again first if typing is still waiting
     * for the debounce, so the editor never gets an older reading than the field shows.
     */
    fun prefill(): EventPrefill {
        if (parseJob?.isActive == true) {
            parseJob?.cancel()
            parseNow(_state.value.text)
        }
        val s = _state.value
        return EventPrefill.from(s.actions, s.text, presetDate ?: s.today ?: today())
    }

    private fun edit(index: Int, clearField: UncertainField? = null, transform: (QuickAddAction) -> QuickAddAction) {
        _state.update { s ->
            if (index !in s.actions.indices) return@update s
            val next = s.actions.toMutableList().also { it[index] = transform(it[index]) }
            s.copy(actions = next, uncertain = if (clearField != null && index == 0) s.uncertain - clearField else s.uncertain)
        }
    }

    fun convert(index: Int, kind: ActionKind) {
        val fallbackDate = presetDate ?: _state.value.today ?: today()
        edit(index, UncertainField.Kind) { QuickAddEdits.convert(it, kind, fallbackDate) }
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
        _state.update { s ->
            s.copy(
                actions = s.actions.filterIndexed { i, _ -> i != index },
                editing = s.editing.filter { it != index }.mapTo(mutableSetOf()) { if (it > index) it - 1 else it },
            )
        }
    }

    fun save(onSaved: (QuickAddOutcome) -> Unit) {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val outcome = executor.execute(QuickAddResult(s.text, s.actions, s.uncertain))
            // Counted per save on this phone; the example chips go away after 3 (UX plan 4.6).
            prefs.update(Prefs.QuickAddSaves) { it + 1 }
            _state.update { QuickAddUiState(people = it.people, examples = it.examples, hintName = it.hintName) }
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

/**
 * Quick add opened from a screen that shows a day uses that day when the text names no date of its own (UX plan 1.2).
 * Without a written date the parser falls back to today (or tomorrow when the time has passed) and marks the date
 * uncertain. Such a date moves along when "today" moves one day; a written one ("fredag", "14 okt") does not, so it
 * is left alone ("imorgon" is never uncertain). Opening from today's own screen changes nothing.
 */
internal object QuickAddPresetDate {
    fun apply(parser: SwedishQuickAddParser, result: QuickAddResult, ctx: QuickAddContext, preset: LocalDate): QuickAddResult {
        val primary = result.actions.firstOrNull() ?: return result
        val parsedDate = QuickAddEdits.dateOf(primary)
        if (preset == ctx.today || parsedDate == null || UncertainField.Date !in result.uncertain) return result
        val shifted = parser.parse(result.input, ctx.copy(today = ctx.today.plusDays(1))).actions.firstOrNull()
        if (shifted == null || QuickAddEdits.dateOf(shifted) != parsedDate.plusDays(1)) return result
        val actions = result.actions.toMutableList().also { it[0] = QuickAddEdits.withDate(primary, preset) }
        return result.copy(actions = actions, uncertain = result.uncertain - UncertainField.Date)
    }
}
