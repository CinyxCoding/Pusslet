package se.pusslet.feature.meals.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeTag
import se.pusslet.core.model.Role
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.ListRow
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

data class RecipeLibraryState(
    val loading: Boolean = true,
    val all: Int = 0,
    val recipes: List<Recipe> = emptyList(),
    val query: String = "",
    val tags: Set<RecipeTag> = emptySet(),
    val favoritesOnly: Boolean = false,
    val canEdit: Boolean = true,
)

class RecipeLibraryViewModel(recipes: RecipeRepository, sessions: SessionProvider) : ViewModel() {
    private val query = MutableStateFlow("")
    private val tags = MutableStateFlow(emptySet<RecipeTag>())
    private val favoritesOnly = MutableStateFlow(false)
    private val canEdit = sessions.session.value.role != Role.Caregiver

    val state: StateFlow<RecipeLibraryState> = combine(recipes.recipes, query, tags, favoritesOnly) { all, q, t, fav ->
        val needle = q.trim().lowercase()
        val filtered = all.filter { r ->
            (needle.isEmpty() || needle in r.title.lowercase() || r.ingredients.any { needle in it.name.lowercase() }) &&
                r.tags.containsAll(t) &&
                (!fav || r.favorite)
        }.sortedWith(compareByDescending<Recipe> { it.favorite }.thenBy { it.title.lowercase() })
        RecipeLibraryState(false, all.size, filtered, q, t, fav, canEdit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RecipeLibraryState())

    fun setQuery(q: String) {
        query.value = q
    }

    fun toggleTag(tag: RecipeTag) = tags.update { if (tag in it) it - tag else it + tag }

    fun toggleFavorites() = favoritesOnly.update { !it }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun RecipeLibraryRoute(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onPaste: () -> Unit,
    viewModel: RecipeLibraryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RecipeLibraryScreen(
        state,
        onBack,
        onOpen,
        onNew,
        onImport,
        onPaste,
        viewModel::setQuery,
        viewModel::toggleTag,
        viewModel::toggleFavorites,
    )
}

@Composable
fun RecipeLibraryScreen(
    state: RecipeLibraryState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onPaste: () -> Unit,
    onQuery: (String) -> Unit,
    onToggleTag: (RecipeTag) -> Unit,
    onToggleFavorites: () -> Unit,
) {
    Scaffold(
        topBar = { PussletTopBar(stringResource(Res.string.recipes_title), onBack = onBack) },
        floatingActionButton = {
            if (state.canEdit) {
                ExtendedFloatingActionButton(
                    onClick = onNew,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(Res.string.recipes_new)) },
                )
            }
        },
    ) { padding ->
        if (state.loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.xxl * 3),
        ) {
            item(key = "search") {
                Column(Modifier.padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (state.canEdit) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            AssistChip(onClick = onImport, label = { Text(stringResource(Res.string.recipes_import)) })
                            AssistChip(onClick = onPaste, label = { Text(stringResource(Res.string.recipes_paste)) })
                        }
                    }
                    if (state.all > 0) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = onQuery,
                            label = { Text(stringResource(Res.string.recipes_search)) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (state.all > 0) {
                item(key = "tags") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        item {
                            FilterChip(
                                selected = state.favoritesOnly,
                                onClick = onToggleFavorites,
                                label = { Text(stringResource(Res.string.recipes_favorites_only)) },
                                leadingIcon = { Icon(Icons.Filled.Favorite, contentDescription = null, Modifier.size(18.dp)) },
                            )
                        }
                        items(RecipeTag.entries) { tag ->
                            FilterChip(
                                selected = tag in state.tags,
                                onClick = { onToggleTag(tag) },
                                label = { Text(stringResource(Labels.recipeTag(tag))) },
                            )
                        }
                    }
                }
            }
            when {
                state.all == 0 -> item(key = "empty") { EmptyState(stringResource(Res.string.recipes_empty)) }
                state.recipes.isEmpty() -> item(key = "none") {
                    EmptyState(stringResource(Res.string.recipes_no_match))
                }
            }
            items(state.recipes, key = { it.id }) { r ->
                Column {
                    ListRow(
                        title = r.title,
                        supporting = recipeSummary(r),
                        modifier = Modifier.clickable { onOpen(r.id) }.padding(horizontal = Spacing.screenHorizontal),
                        trailing = if (r.favorite) {
                            {
                                Icon(
                                    Icons.Filled.Favorite,
                                    contentDescription = stringResource(Res.string.reason_favorite),
                                    tint = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        } else {
                            null
                        },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = Spacing.screenHorizontal))
                }
            }
        }
    }
}

/** "30 min · Snabbt · Barnvänligt" */
@Composable
internal fun recipeSummary(r: Recipe): String? {
    val parts = buildList {
        r.totalMinutes?.let { add(stringResource(Res.string.recipe_minutes, it)) }
        r.tags.take(MAX_TAGS).forEach { add(stringResource(Labels.recipeTag(it))) }
        r.sourceName?.let { add(it) }
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

private const val MAX_TAGS = 3
