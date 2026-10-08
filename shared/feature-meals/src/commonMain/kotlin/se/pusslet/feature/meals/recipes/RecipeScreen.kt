package se.pusslet.feature.meals.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.isoDayNumber
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.Ingredient
import se.pusslet.feature.meals.NutritionSource
import se.pusslet.feature.meals.NutritionTable
import se.pusslet.feature.meals.WarningLine
import se.pusslet.feature.meals.conflictText
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.ListRow
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.Stepper
import se.pusslet.ui.format.formatQuantity
import se.pusslet.ui.format.relativeDay
import se.pusslet.ui.format.weekdayName
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

@Composable
fun RecipeRoute(
    recipeId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: RecipeViewModel = koinViewModel(),
) {
    LaunchedEffect(recipeId) { viewModel.load(recipeId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { m ->
            val text = when (m) {
                is RecipeMessage.Planned -> getString(
                    Res.string.recipe_planned,
                    getStringArray(Res.array.weekdays)[m.date.dayOfWeek.isoDayNumber - 1],
                )
                is RecipeMessage.AddedToList -> getString(Res.string.recipe_added_to_list, m.count, m.listName)
            }
            snackbar.showSnackbar(text)
        }
    }
    RecipeScreen(
        state = state,
        snackbar = snackbar,
        onBack = onBack,
        onEdit = { onEdit(recipeId) },
        onServings = viewModel::setServings,
        onToggleFavorite = viewModel::toggleFavorite,
        onDelete = viewModel::delete,
        onPlan = viewModel::planOn,
        onAddToList = viewModel::addToList,
        onLoadNutrition = viewModel::loadNutrition,
        onOpenMatch = viewModel::openMatch,
        onCloseMatch = viewModel::closeMatch,
        onConfirmMatch = viewModel::confirmMatch,
    )
}

@Composable
fun RecipeScreen(
    state: RecipeUiState,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit = {},
    onEdit: () -> Unit = {},
    onServings: (Int) -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onDelete: () -> Unit = {},
    onPlan: (kotlinx.datetime.LocalDate) -> Unit = {},
    onAddToList: (String) -> Unit = {},
    onLoadNutrition: () -> Unit = {},
    onOpenMatch: (String) -> Unit = {},
    onCloseMatch: () -> Unit = {},
    onConfirmMatch: (String, se.pusslet.core.nutrition.Food) -> Unit = { _, _ -> },
) {
    val recipe = state.recipe
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            PussletTopBar(
                title = recipe?.title.orEmpty(),
                onBack = onBack,
                actions = {
                    if (recipe != null && state.canEdit) {
                        IconButton(onClick = onToggleFavorite) {
                            Icon(
                                if (recipe.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = stringResource(
                                    if (recipe.favorite) Res.string.recipe_unfavorite else Res.string.recipe_favorite,
                                ),
                            )
                        }
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(Res.string.action_edit))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))
            recipe == null -> EmptyState(stringResource(Res.string.recipe_not_found), Modifier.padding(padding))
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Meta(recipe)
                if (state.conflicts.isNotEmpty()) {
                    Column {
                        WarningLine(conflictText(state.conflicts))
                        Text(
                            stringResource(Res.string.meals_diet_disclaimer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.canEdit) Actions(state, onPlan, onAddToList)
                Ingredients(state, onServings)
                Steps(recipe.steps)
                if (recipe.notes.isNotBlank()) {
                    SectionCard(stringResource(Res.string.recipe_notes)) { Text(recipe.notes, style = MaterialTheme.typography.bodyLarge) }
                }
                Nutrition(state, onLoadNutrition, onOpenMatch)
                recipe.sourceUrl?.let { url -> Source(recipe.sourceName ?: url, url) }
                if (state.canEdit) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.action_delete))
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(Res.string.recipe_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
    state.match?.let { m -> MatchDialogView(m, onCloseMatch, onConfirmMatch) }
}

@Composable
private fun Meta(recipe: se.pusslet.core.model.Recipe) {
    val chips = buildList {
        recipe.totalMinutes?.let { add(stringResource(Res.string.recipe_minutes, it)) }
        recipe.mainComponent?.let { add(stringResource(Labels.component(it))) }
        recipe.tags.forEach { add(stringResource(Labels.recipeTag(it))) }
    }
    if (chips.isEmpty()) return
    Text(chips.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Actions(state: RecipeUiState, onPlan: (kotlinx.datetime.LocalDate) -> Unit, onAddToList: (String) -> Unit) {
    var planMenu by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }
    val today = state.planDays.firstOrNull()
    Box {
        Button(onClick = { planMenu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)) {
            Text(stringResource(Res.string.recipe_plan))
        }
        DropdownMenu(expanded = planMenu, onDismissRequest = { planMenu = false }) {
            Text(
                stringResource(Res.string.recipe_plan_pick_day),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(Spacing.md),
            )
            state.planDays.forEach { d ->
                val soon = today != null && d.toEpochDays() - today.toEpochDays() < 2
                val label = if (soon && today != null) relativeDay(d, today) else weekdayName(d.dayOfWeek)
                DropdownMenuItem(text = { Text(label.replaceFirstChar { it.uppercase() }) }, onClick = {
                    planMenu = false
                    onPlan(d)
                })
            }
        }
    }
    if (state.lists.isNotEmpty()) {
        Box {
            OutlinedButton(
                onClick = { if (state.lists.size == 1) onAddToList(state.lists.first().id) else listMenu = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget),
            ) {
                Text(stringResource(Res.string.recipe_add_to_list))
            }
            DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                state.lists.forEach { l ->
                    DropdownMenuItem(text = { Text(l.name) }, onClick = {
                        listMenu = false
                        onAddToList(l.id)
                    })
                }
            }
        }
    }
}

@Composable
private fun Ingredients(state: RecipeUiState, onServings: (Int) -> Unit) {
    SectionCard(
        stringResource(Res.string.recipe_ingredients),
        action = {
            Stepper(
                value = state.servings,
                onValueChange = onServings,
                decreaseLabel = stringResource(Res.string.meals_servings_decrease),
                increaseLabel = stringResource(Res.string.meals_servings_increase),
                valueLabel = stringResource(Res.string.meals_servings_short, state.servings),
            )
        },
    ) {
        var group: String? = null
        state.ingredients.forEach { ing ->
            val g = ing.group
            if (g != null && g != group) {
                group = g
                Text(
                    stringResource(Res.string.recipe_group, g),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
            IngredientLine(ing)
        }
    }
}

@Composable
private fun IngredientLine(ing: Ingredient) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(
            ing.amount?.let { formatQuantity(it) }.orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(ing.name, style = MaterialTheme.typography.bodyLarge)
            val extra =
                listOfNotNull(ing.note.takeIf { it.isNotBlank() }, if (ing.optional) stringResource(Res.string.recipe_optional) else null)
            if (extra.isNotEmpty()) {
                Text(
                    extra.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Steps(steps: List<String>) {
    if (steps.isEmpty()) return
    SectionCard(stringResource(Res.string.recipe_steps)) {
        steps.forEachIndexed { i, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.padding(vertical = Spacing.xs)) {
                Text("${i + 1}.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(step, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Nutrition(state: RecipeUiState, onLoad: () -> Unit, onOpenMatch: (String) -> Unit) {
    var showMatches by remember { mutableStateOf(false) }
    SectionCard(stringResource(Res.string.nutrition_title)) {
        when (val n = state.nutrition) {
            NutritionState.Idle -> OutlinedButton(onClick = onLoad, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
                Text(stringResource(Res.string.nutrition_show))
            }
            NutritionState.Loading -> CircularProgressIndicator(Modifier.padding(Spacing.sm))
            NutritionState.Offline -> {
                Text(stringResource(Res.string.nutrition_offline), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onLoad) { Text(stringResource(Res.string.action_retry)) }
            }
            is NutritionState.Ready -> {
                NutritionTable(n.summary.perPortion)
                Text(
                    if (n.summary.complete) {
                        stringResource(Res.string.nutrition_complete)
                    } else {
                        stringResource(Res.string.nutrition_partial, n.summary.matchedIngredients, n.summary.totalIngredients)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.canEdit) {
                    TextButton(onClick = { showMatches = !showMatches }) { Text(stringResource(Res.string.nutrition_match)) }
                    if (showMatches) {
                        n.matches.forEach { m ->
                            ListRow(
                                title = m.ingredient,
                                supporting = m.food?.name ?: stringResource(Res.string.nutrition_not_counted),
                                modifier = Modifier.clickable { onOpenMatch(m.ingredient) },
                            )
                        }
                    }
                }
                NutritionSource()
            }
        }
    }
}

@Composable
private fun Source(name: String, url: String) {
    val platform = LocalPlatformActions.current
    SectionCard(null) {
        Text(stringResource(Res.string.recipe_source, name), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(Res.string.recipe_source_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { platform.openUrl(url) }) { Text(stringResource(Res.string.recipe_open_source)) }
    }
}

@Composable
private fun MatchDialogView(m: MatchDialog, onClose: () -> Unit, onConfirm: (String, se.pusslet.core.nutrition.Food) -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(Res.string.nutrition_match_title, m.ingredient)) },
        text = {
            Column {
                val candidates = m.candidates
                when {
                    candidates == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    candidates.isEmpty() -> Text(stringResource(Res.string.nutrition_no_candidates))
                    else -> candidates.forEach { food ->
                        ListRow(title = food.name, modifier = Modifier.clickable { onConfirm(m.ingredient, food) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
