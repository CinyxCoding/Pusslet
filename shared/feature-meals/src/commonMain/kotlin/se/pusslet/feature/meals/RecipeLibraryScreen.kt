package se.pusslet.feature.meals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeTag
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.ListRow
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/**
 * Mat › Recept: tag filters and the family's recipes ("45 min · ICA" and a star). The search field sits in the tab's header
 * ([RecipeSearchField]) and new recipes come from the round + (UX plan 1.2), the one way to add here. An empty library
 * tells how to share a recipe from the browser, and its "Nytt recept" ([onAdd]) opens the same choice as the + (UX plan 4.5).
 */
@Composable
fun RecipeLibraryScreen(
    state: RecipeLibraryState,
    bottomPadding: PaddingValues = PaddingValues(),
    onToggleTag: (RecipeTag) -> Unit = {},
    onOpen: (String) -> Unit = {},
    onAdd: () -> Unit = {},
) {
    if (state.loading) {
        LoadingBox()
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screenHorizontal,
            end = Spacing.screenHorizontal,
            top = Spacing.xs,
            bottom = Spacing.listBottomInset + bottomPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (state.total > 0) {
            item(key = "tags") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    RecipeTag.entries.forEach { tag ->
                        FilterChip(
                            selected = tag in state.tags,
                            onClick = { onToggleTag(tag) },
                            label = { Text(stringResource(tagLabel(tag))) },
                            colors = SelectionColors.filterChip(),
                        )
                    }
                }
            }
        }
        when {
            state.total == 0 -> item(key = "empty") { EmptyLibrary(canAdd = state.canEdit, onAdd = onAdd) }
            state.recipes.isEmpty() -> item(key = "none") { EmptyState(message = stringResource(Res.string.recipe_no_match)) }
        }
        items(state.recipes, key = { it.id }) { recipe ->
            RecipeRow(recipe, onClick = { onOpen(recipe.id) })
        }
    }
}

/** "Inga recept än", the tip "Dela recept från webbläsaren: Dela → Pusslet" and, for parents, "Nytt recept". */
@Composable
private fun EmptyLibrary(canAdd: Boolean, onAdd: () -> Unit) {
    EmptyState(
        title = stringResource(Res.string.recipe_empty_title),
        message = stringResource(Res.string.recipe_empty_share),
        action = if (canAdd) {
            { Button(onClick = onAdd) { Text(stringResource(Res.string.recipe_new)) } }
        } else {
            null
        },
    )
}

/** "Sök recept" (titles and ingredients), in the place where Matsedel has the week. */
@Composable
internal fun RecipeSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(Res.string.recipe_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
    )
}

@Composable
private fun RecipeRow(recipe: Recipe, onClick: () -> Unit) {
    // "45 min · ICA": portions and tags live in the recipe itself.
    val details = buildList {
        recipe.totalMinutes?.let { add(stringResource(Res.string.recipe_minutes, it)) }
        recipe.sourceName?.let { add(it) }
    }
    ListRow(
        title = recipe.title,
        supporting = details.joinToString(" · ").ifEmpty { null },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        trailing = if (recipe.favorite) {
            {
                val description = stringResource(Res.string.recipe_favorite)
                Icon(Icons.Filled.Star, contentDescription = description, tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            null
        },
    )
}

internal fun tagLabel(tag: RecipeTag): StringResource = when (tag) {
    RecipeTag.Quick -> Res.string.tag_quick
    RecipeTag.Vegetarian -> Res.string.tag_vegetarian
    RecipeTag.Vegan -> Res.string.tag_vegan
    RecipeTag.KidFriendly -> Res.string.tag_kid_friendly
    RecipeTag.Lunchbox -> Res.string.tag_lunchbox
    RecipeTag.FreezerFriendly -> Res.string.tag_freezer
    RecipeTag.Weekend -> Res.string.tag_weekend
    RecipeTag.Budget -> Res.string.tag_budget
}
