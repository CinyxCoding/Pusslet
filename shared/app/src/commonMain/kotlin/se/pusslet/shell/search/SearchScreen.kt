package se.pusslet.shell.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.EventOccurrence
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.usecase.FamilySearch
import se.pusslet.data.usecase.SearchResults
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.relativeDay
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.action_close
import se.pusslet.ui.resources.all_day
import se.pusslet.ui.resources.recipe_minutes
import se.pusslet.ui.resources.search_contacts
import se.pusslet.ui.resources.search_done
import se.pusslet.ui.resources.search_events
import se.pusslet.ui.resources.search_hint
import se.pusslet.ui.resources.search_in_trolley
import se.pusslet.ui.resources.search_items
import se.pusslet.ui.resources.search_line
import se.pusslet.ui.resources.search_nothing
import se.pusslet.ui.resources.search_recipes
import se.pusslet.ui.resources.search_start
import se.pusslet.ui.resources.search_tasks
import se.pusslet.ui.resources.search_title
import se.pusslet.ui.theme.Spacing
import kotlin.time.Clock

data class SearchUiState(val query: String = "", val results: SearchResults = SearchResults(), val today: LocalDate? = null)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val search: FamilySearch,
    private val prefs: PreferencesRepository,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val query = MutableStateFlow("")
    val text: StateFlow<String> = query.asStateFlow()

    val state: StateFlow<SearchUiState> = query
        .debounce(DEBOUNCE_MS)
        .mapLatest { q -> SearchUiState(q, search.search(q), clock.todayIn(zone)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SearchUiState())

    fun setQuery(q: String) {
        query.value = q
    }

    /** The Handla tab opens on the list a hit is on. */
    fun showList(listId: String) {
        viewModelScope.launch { prefs.set(Prefs.ActiveShoppingList, listId) }
    }

    private companion object {
        const val DEBOUNCE_MS = 150L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Where a hit leads; the shell turns these into navigation. */
interface SearchNavigation {
    fun event(occurrence: EventOccurrence)

    fun recipe(id: String)

    fun task(id: String)

    fun list(id: String)

    fun child(id: String)
}

@Composable
fun SearchRoute(onBack: () -> Unit, open: SearchNavigation, viewModel: SearchViewModel = koinViewModel()) {
    val text by viewModel.text.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigation = remember(open) {
        object : SearchNavigation by open {
            override fun list(id: String) {
                viewModel.showList(id)
                open.list(id)
            }
        }
    }
    SearchScreen(text, state, onBack, viewModel::setQuery, navigation)
}

@Composable
fun SearchScreen(text: String, state: SearchUiState, onBack: () -> Unit, onQuery: (String) -> Unit, open: SearchNavigation) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.search_title), onBack = onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            item(key = "field") {
                OutlinedTextField(
                    value = text,
                    onValueChange = onQuery,
                    placeholder = { Text(stringResource(Res.string.search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (text.isNotEmpty()) {
                            IconButton(onClick = { onQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_close))
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            val r = state.results
            when {
                state.query.isBlank() -> item(key = "start") { Hint(stringResource(Res.string.search_start)) }
                r.isEmpty -> item(key = "nothing") { Hint(stringResource(Res.string.search_nothing, state.query)) }
                else -> results(r, state.today, open)
            }
        }
    }
}

private fun LazyListScope.results(r: SearchResults, today: LocalDate?, open: SearchNavigation) {
    if (r.events.isNotEmpty()) {
        section("events", Res.string.search_events)
        items(r.events, key = { "e-" + it.occurrence.key + it.occurrence.source }) { hit ->
            val o = hit.occurrence
            Hit(o.event.title, eventWhen(o, today)) { open.event(o) }
        }
    }
    if (r.recipes.isNotEmpty()) {
        section("recipes", Res.string.search_recipes)
        items(r.recipes, key = { "r-" + it.recipeId }) { hit ->
            Hit(hit.title, hit.minutes?.let { stringResource(Res.string.recipe_minutes, it) }) { open.recipe(hit.recipeId) }
        }
    }
    if (r.tasks.isNotEmpty()) {
        section("tasks", Res.string.search_tasks)
        items(r.tasks, key = { "t-" + it.taskId }) { hit ->
            val sub = line(
                listOfNotNull(
                    hit.due?.let { due -> today?.let { relativeDay(due, it) } },
                    if (hit.done) stringResource(Res.string.search_done) else null,
                ),
            )
            Hit(hit.title, sub) { open.task(hit.taskId) }
        }
    }
    if (r.items.isNotEmpty()) {
        section("items", Res.string.search_items)
        items(r.items, key = { "i-" + it.itemId }) { hit ->
            val sub = line(listOfNotNull(hit.listName, if (hit.checked) stringResource(Res.string.search_in_trolley) else null))
            Hit(hit.name, sub) { open.list(hit.listId) }
        }
    }
    if (r.contacts.isNotEmpty()) {
        section("contacts", Res.string.search_contacts)
        items(r.contacts, key = { "c-" + it.childId + it.name }) { hit ->
            val sub = line(listOf(hit.childName, hit.relation, hit.phone.orEmpty()))
            Hit(hit.name, sub) { open.child(hit.childId) }
        }
    }
}

private fun LazyListScope.section(key: String, title: org.jetbrains.compose.resources.StringResource) {
    item(key = "h-$key") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = Spacing.md).semantics { heading() },
        )
    }
}

@Composable
private fun Hit(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).clickable(onClick = onClick).padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.md),
    )
}

/** "Imorgon · 14:30 · Skolans kalender" – parts joined with the language's separator; blank parts are left out. */
@Composable
private fun line(parts: List<String>): String? {
    val kept = parts.filter { it.isNotBlank() }
    if (kept.isEmpty()) return null
    var text = kept.first()
    for (p in kept.drop(1)) text = stringResource(Res.string.search_line, text, p)
    return text
}

@Composable
private fun eventWhen(o: EventOccurrence, today: LocalDate?): String? {
    val day = today?.let { relativeDay(o.start.date, it) } ?: formatDateShort(o.start.date)
    val time = if (o.allDay) stringResource(Res.string.all_day) else formatTime(o.start.time)
    return line(listOfNotNull(day, time, o.sourceName))
}
