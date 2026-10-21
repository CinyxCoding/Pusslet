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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.Ingredient
import se.pusslet.core.nutrition.Food
import se.pusslet.core.nutrition.NutritionSummary
import se.pusslet.core.recipes.RecipeText
import se.pusslet.ui.components.DatePickerSheet
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.NavRow
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.Section
import se.pusslet.ui.components.SheetDragHandle
import se.pusslet.ui.components.showUndo
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * One recipe (UX plan, Mat 7): portions in the ingredients' heading, "Lägg på inköpslistan" and "Lägg i matsedeln" fixed at
 * the bottom, nutrition as one row that opens the details, and the source as a link ("ica.se").
 */
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
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var showMatching by remember { mutableStateOf(false) }
    var showNutrition by remember { mutableStateOf(false) }
    var choosingCollections by remember { mutableStateOf(false) }

    LaunchedEffect(deleted) { if (deleted) onBack() }
    RecipeReceipts(viewModel, snackbar)

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
                                DropdownMenuItem(text = { Text(stringResource(Res.string.recipe_add_to_collection)) }, onClick = {
                                    menu = false
                                    choosingCollections = true
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
        bottomBar = {
            if (recipe != null && !state.loading) {
                RecipeActionBar(canPlan = state.canEdit, onPlan = { pickDate = true }, onAddToList = viewModel::addToShoppingList)
            }
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
            onOpenNutrition = { showNutrition = true },
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
    if (choosingCollections && recipe != null) {
        AddToCollectionSheet(
            state = state,
            onToggle = viewModel::setInCollection,
            onCreate = viewModel::createCollection,
            onDismiss = { choosingCollections = false },
        )
    }
    if (showNutrition && recipe != null) {
        NutritionSheet(
            state = state.nutrition,
            servings = state.servings,
            canMatch = state.canEdit,
            onMatch = {
                showNutrition = false
                showMatching = true
            },
            onRetry = viewModel::retryNutrition,
            onDismiss = { showNutrition = false },
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

/** "2 varor på listan · Ångra", "I matsedeln tor 15 okt · Ångra". */
@Composable
private fun RecipeReceipts(viewModel: RecipeDetailsViewModel, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val weekdays by rememberUpdatedState(stringArrayResource(Res.array.weekdays_short))
    val months by rememberUpdatedState(stringArrayResource(Res.array.months_short))
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            scope.launch {
                val undoLabel = getString(Res.string.action_undo)
                when (message) {
                    is RecipeMessage.AddedToList -> {
                        val outcome = message.outcome
                        if (outcome.isEmpty) {
                            snackbar.currentSnackbarData?.dismiss()
                            snackbar.showSnackbar(getString(Res.string.recipe_already_on_list))
                        } else if (snackbar.showUndo(getString(Res.string.shopping_added, outcome.names.joinToString(", ")), undoLabel)) {
                            viewModel.undoAdd(outcome)
                        }
                    }
                    is RecipeMessage.Planned -> {
                        val day = shortDate(message.date, weekdays, months)
                        val text = getString(Res.string.recipe_planned_on, day)
                        val undo = message.undo
                        if (undo == null) {
                            snackbar.currentSnackbarData?.dismiss()
                            snackbar.showSnackbar(text)
                        } else if (snackbar.showUndo(text, undoLabel)) {
                            viewModel.undoPlan(undo)
                        }
                    }
                }
            }
        }
    }
}

/** "tor 15 okt". */
private suspend fun shortDate(date: LocalDate, weekdays: List<String>, months: List<String>): String =
    weekdays[date.dayOfWeek.isoDayNumber - 1] + " " + getString(Res.string.date_short, date.day, months[date.month.ordinal])

@Composable
fun RecipeDetailsScreen(
    state: RecipeDetailsState,
    padding: PaddingValues = PaddingValues(),
    onServings: (Int) -> Unit = {},
    onOpenNutrition: () -> Unit = {},
) {
    val recipe = state.recipe ?: return
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screenHorizontal,
            end = Spacing.screenHorizontal,
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + Spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        item(key = "meta") { RecipeMeta(state) }
        item(key = "ingredients") {
            Section(
                title = stringResource(Res.string.recipe_ingredients),
                action = { ServingsStepper(state.servings, onServings) },
            ) { Ingredients(state.ingredients) }
        }
        if (recipe.steps.isNotEmpty()) {
            item(key = "steps") {
                Section(title = stringResource(Res.string.recipe_steps)) {
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
                Section(title = stringResource(Res.string.recipe_notes)) {
                    Text(recipe.notes, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        item(key = "nutrition") { NutritionRow(state.nutrition, onOpenNutrition) }
    }
}

/** "45 min · Snabbt · Barnvänligt" and the source as a link: "🔗 ica.se". */
@Composable
private fun RecipeMeta(state: RecipeDetailsState) {
    val recipe = state.recipe ?: return
    val platform = LocalPlatformActions.current
    val meta = buildList {
        recipe.totalMinutes?.let { add(stringResource(Res.string.recipe_minutes, it)) }
        recipe.tags.sortedBy { it.ordinal }.forEach { add(stringResource(tagLabel(it))) }
    }
    val source = recipe.sourceUrl
    if (meta.isEmpty() && source == null) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (meta.isNotEmpty()) {
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (source != null) {
            val openLabel = stringResource(Res.string.recipe_open_source)
            Row(
                Modifier
                    .heightIn(min = Spacing.touchTarget)
                    .clickable(onClickLabel = openLabel, role = Role.Button) { platform.openUrl(source) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(MealsIcons.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(
                    sourceHost(source) ?: recipe.sourceName ?: source,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** − "4 portioner" + in the ingredients' heading. */
@Composable
private fun ServingsStepper(servings: Int, onServings: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onServings(servings - 1) }, enabled = servings > 1) {
            Icon(MealsIcons.Remove, contentDescription = stringResource(Res.string.recipe_fewer_servings))
        }
        Text(
            if (servings ==
                1
            ) {
                stringResource(Res.string.recipe_servings_one)
            } else {
                stringResource(Res.string.recipe_servings_count, servings)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        IconButton(onClick = { onServings(servings + 1) }) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.recipe_more_servings))
        }
    }
}

@Composable
private fun Ingredients(ingredients: List<Ingredient>) {
    var group: String? = null
    ingredients.forEach { ing ->
        if (ing.group != group) {
            group = ing.group
            ing.group?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.sm).semantics { heading() })
            }
        }
        Text(
            RecipeText.line(ing),
            style = MaterialTheme.typography.bodyLarge,
            color = if (ing.optional) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** "Näring · ca 520 kcal per portion ›"; the details, how complete the estimate is and the source open in a sheet. */
@Composable
private fun NutritionRow(state: NutritionState, onOpen: () -> Unit) {
    val kcal = (state as? NutritionState.Ready)?.summary?.perPortion?.energyKcal
    val supporting = when (state) {
        NutritionState.Loading -> stringResource(Res.string.nutrition_loading)
        NutritionState.Unavailable -> stringResource(Res.string.nutrition_unavailable)
        is NutritionState.Ready -> null
    }
    NavRow(
        text = if (kcal !=
            null
        ) {
            stringResource(Res.string.recipe_nutrition_kcal, kcal.roundToInt())
        } else {
            stringResource(Res.string.recipe_nutrition)
        },
        supporting = supporting,
        onClick = onOpen,
    )
}

/** The two things to do with a recipe, always in reach: put it on the plan (tonal) and buy what it needs (filled). */
@Composable
private fun RecipeActionBar(canPlan: Boolean, onPlan: () -> Unit, onAddToList: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        FlowRow(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (canPlan) {
                FilledTonalButton(onClick = onPlan, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.recipe_plan)) }
            }
            Button(onClick = onAddToList, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.recipe_add_to_list)) }
        }
    }
}

/** Neutral and informative (no targets, no judging words); always says how complete the estimate is and its source. */
@Composable
private fun NutritionSheet(
    state: NutritionState,
    servings: Int,
    canMatch: Boolean,
    onMatch: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    MealSheet(onDismiss) {
        SheetTitle(stringResource(Res.string.nutrition_title))
        when (state) {
            NutritionState.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                CircularProgressIndicator(Modifier.padding(Spacing.xs))
                Text(stringResource(Res.string.nutrition_loading), style = MaterialTheme.typography.bodyMedium)
            }
            NutritionState.Unavailable -> {
                Text(stringResource(Res.string.nutrition_unavailable), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
            }
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { SheetDragHandle() }) {
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

/** "ica.se" from "https://www.ica.se/recept/…". */
internal fun sourceHost(
    url: String,
): String? = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://(?:www\.)?([^/:?#]+)""").find(url.trim())?.groupValues?.get(1)
