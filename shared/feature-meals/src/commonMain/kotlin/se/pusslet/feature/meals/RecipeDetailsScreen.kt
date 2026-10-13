package se.pusslet.feature.meals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.Ingredient
import se.pusslet.core.nutrition.Food
import se.pusslet.core.nutrition.NutritionSummary
import se.pusslet.core.recipes.RecipeText
import se.pusslet.ui.components.DatePickerSheet
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

@Composable
fun RecipeDetailsRoute(
    recipeId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: RecipeDetailsViewModel = koinViewModel(),
) {
    LaunchedEffect(recipeId) { viewModel.load(recipeId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val match by viewModel.match.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var showMatching by remember { mutableStateOf(false) }

    LaunchedEffect(deleted) { if (deleted) onBack() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            scope.launch {
                when (message) {
                    is RecipeMessage.AddedToList -> {
                        val text = if (message.outcome.isEmpty) {
                            getString(Res.string.recipe_already_on_list)
                        } else {
                            getString(Res.string.shopping_added, message.outcome.names.joinToString(", "))
                        }
                        val undo = if (message.outcome.isEmpty) null else getString(Res.string.action_undo)
                        val result = snackbar.showSnackbar(text, undo, duration = SnackbarDuration.Short)
                        if (result == SnackbarResult.ActionPerformed) viewModel.undoAdd(message.outcome)
                    }
                    is RecipeMessage.Planned -> snackbar.showSnackbar(getString(Res.string.recipe_planned))
                }
            }
        }
    }

    val recipe = state.recipe
    Scaffold(
        topBar = {
            PussletTopBar(
                title = recipe?.title.orEmpty(),
                onBack = onBack,
                actions = {
                    if (recipe != null && state.canEdit) {
                        IconButton(onClick = viewModel::toggleFavorite) {
                            Icon(
                                if (recipe.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = stringResource(
                                    if (recipe.favorite) Res.string.recipe_unfavorite else Res.string.recipe_make_favorite,
                                ),
                            )
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.shopping_more))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(Res.string.action_edit)) }, onClick = {
                                    menu = false
                                    onEdit(recipe.id)
                                })
                                DropdownMenuItem(text = { Text(stringResource(Res.string.recipe_match_foods)) }, onClick = {
                                    menu = false
                                    showMatching = true
                                })
                                DropdownMenuItem(text = { Text(stringResource(Res.string.action_delete)) }, onClick = {
                                    menu = false
                                    confirmDelete = true
                                })
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading || recipe == null) {
            LoadingBox()
            return@Scaffold
        }
        RecipeDetailsScreen(
            state = state,
            padding = padding,
            onServings = viewModel::setServings,
            onAddToList = viewModel::addToShoppingList,
            onPlan = { pickDate = true },
            onMatch = { showMatching = true },
        )
    }

    if (pickDate) {
        state.today?.let { today ->
            DatePickerSheet(initial = today, onDismiss = { pickDate = false }, onPicked = viewModel::planOn)
        }
    }
    if (confirmDelete && recipe != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(Res.string.recipe_delete_confirm, recipe.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
    if (showMatching && recipe != null) {
        FoodMatchingSheet(
            ingredients = recipe.ingredients.filter { !it.optional },
            mappings = state.mappings,
            match = match,
            onPick = viewModel::startMatching,
            onConfirm = viewModel::confirmMatch,
            onBackToList = viewModel::cancelMatching,
            onDismiss = {
                viewModel.cancelMatching()
                showMatching = false
            },
        )
    }
}

@Composable
fun RecipeDetailsScreen(
    state: RecipeDetailsState,
    padding: PaddingValues = PaddingValues(),
    onServings: (Int) -> Unit = {},
    onAddToList: () -> Unit = {},
    onPlan: () -> Unit = {},
    onMatch: () -> Unit = {},
) {
    val recipe = state.recipe ?: return
    val platform = LocalPlatformActions.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screenHorizontal,
            end = Spacing.screenHorizontal,
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + Spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "meta") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                val meta = buildList {
                    recipe.totalMinutes?.let { add(stringResource(Res.string.recipe_minutes, it)) }
                    recipe.tags.sortedBy { it.ordinal }.forEach { add(stringResource(tagLabel(it))) }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        meta.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val source = recipe.sourceUrl
                if (source != null) {
                    Text(
                        stringResource(Res.string.recipe_source, recipe.sourceName ?: source),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .heightIn(min = Spacing.touchTarget)
                            .clickable(role = Role.Button) { platform.openUrl(source) }
                            .padding(vertical = Spacing.sm),
                    )
                }
            }
        }
        item(key = "servings") { ServingsRow(state.servings, onServings) }
        item(key = "actions") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(onClick = onPlan) { Text(stringResource(Res.string.recipe_plan)) }
                OutlinedButton(onClick = onAddToList) { Text(stringResource(Res.string.recipe_add_to_list)) }
            }
        }
        item(key = "ingredients") { IngredientsCard(state.ingredients) }
        if (recipe.steps.isNotEmpty()) {
            item(key = "steps") {
                SectionCard(title = stringResource(Res.string.recipe_steps)) {
                    recipe.steps.forEachIndexed { i, step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            Text("${i + 1}.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text(step, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        if (recipe.notes.isNotBlank()) {
            item(key = "notes") {
                SectionCard(title = stringResource(Res.string.recipe_notes)) {
                    Text(recipe.notes, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        item(key = "nutrition") { NutritionCard(state.nutrition, state.servings, onMatch = onMatch, canMatch = state.canEdit) }
    }
}

@Composable
private fun ServingsRow(servings: Int, onServings: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            stringResource(Res.string.recipe_servings),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { onServings(servings - 1) }, enabled = servings > 1) { Text("−") }
        Text(servings.toString(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Spacing.sm))
        OutlinedButton(onClick = { onServings(servings + 1) }) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.recipe_more_servings))
        }
    }
}

@Composable
private fun IngredientsCard(ingredients: List<Ingredient>) {
    SectionCard(title = stringResource(Res.string.recipe_ingredients)) {
        var group: String? = null
        ingredients.forEach { ing ->
            if (ing.group != group) {
                group = ing.group
                ing.group?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = Spacing.sm).semantics { heading() },
                    )
                }
            }
            Text(
                RecipeText.line(ing),
                style = MaterialTheme.typography.bodyLarge,
                color = if (ing.optional) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Neutral and informative (no targets, no judging words); always says how complete the estimate is and its source. */
@Composable
private fun NutritionCard(state: NutritionState, servings: Int, onMatch: () -> Unit, canMatch: Boolean) {
    SectionCard(title = stringResource(Res.string.nutrition_title)) {
        when (state) {
            NutritionState.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                CircularProgressIndicator(Modifier.padding(Spacing.xs))
                Text(stringResource(Res.string.nutrition_loading), style = MaterialTheme.typography.bodyMedium)
            }
            NutritionState.Unavailable ->
                Text(stringResource(Res.string.nutrition_unavailable), style = MaterialTheme.typography.bodyMedium)
            is NutritionState.Ready -> NutritionValues(state.summary, servings)
        }
        if (canMatch && state is NutritionState.Ready) {
            TextButton(onClick = onMatch) { Text(stringResource(Res.string.recipe_match_foods)) }
        }
        Text(
            stringResource(Res.string.nutrition_source),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NutritionValues(summary: NutritionSummary, servings: Int) {
    Text(stringResource(Res.string.nutrition_per_portion), style = MaterialTheme.typography.bodyMedium)
    NutrientRows(summary.perPortion)
    Text(
        stringResource(Res.string.nutrition_coverage, summary.matchedIngredients, summary.totalIngredients, servings),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Pick which food in Livsmedelsverket's database an ingredient is; the choice is remembered for the family. */
@Composable
private fun FoodMatchingSheet(
    ingredients: List<Ingredient>,
    mappings: Map<String, Int>,
    match: MatchingState?,
    onPick: (Ingredient) -> Unit,
    onConfirm: (Food) -> Unit,
    onBackToList: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (match == null) {
                Text(stringResource(Res.string.recipe_match_foods), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(Res.string.recipe_match_hint), style = MaterialTheme.typography.bodyMedium)
                ingredients.forEach { ing ->
                    val confirmed = ing.foodNumber != null || mappings.containsKey(ing.name.trim().lowercase())
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.touchTarget)
                            .clickable(role = Role.Button) { onPick(ing) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(ing.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (confirmed) Icon(Icons.Filled.Check, contentDescription = stringResource(Res.string.recipe_match_confirmed))
                    }
                }
            } else {
                Text(match.ingredient.name, style = MaterialTheme.typography.titleLarge)
                val candidates = match.candidates
                when {
                    candidates == null -> LoadingBox(Modifier.heightIn(max = Spacing.xxl * 3))
                    candidates.isEmpty() -> Text(stringResource(Res.string.recipe_match_none), style = MaterialTheme.typography.bodyMedium)
                    else -> candidates.forEach { food ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = Spacing.touchTarget)
                                .clickable(role = Role.Button) { onConfirm(food) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(food.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (food.number == match.current) {
                                Icon(Icons.Filled.Check, contentDescription = stringResource(Res.string.recipe_match_confirmed))
                            }
                        }
                    }
                }
                TextButton(onClick = onBackToList) { Text(stringResource(Res.string.navigate_back)) }
            }
        }
    }
}
