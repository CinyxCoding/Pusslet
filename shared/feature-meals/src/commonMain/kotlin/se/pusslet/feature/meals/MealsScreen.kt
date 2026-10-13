package se.pusslet.feature.meals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.Child
import se.pusslet.core.model.FavoriteWeek
import se.pusslet.core.model.LunchProviderType
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Recipe
import se.pusslet.ui.components.ColorBadge
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.SmallAction
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.weekLong
import se.pusslet.ui.format.weekdayName
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

enum class MealsSection { Week, Recipes }

/**
 * The Mat tab: the week's meal plan (with school lunches, clash warnings and busy evenings) and the recipe library.
 */
@Composable
fun MealsRoute(
    onOpenRecipe: (String) -> Unit,
    onNewRecipe: () -> Unit,
    onImportRecipe: () -> Unit,
    onShopForWeek: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: MealsViewModel = koinViewModel(),
    library: RecipeLibraryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editor by viewModel.dayEditor.collectAsStateWithLifecycle()
    val libraryState by library.state.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(MealsSection.Week) }

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
        ) {
            val sections = listOf(
                MealsSection.Week to Res.string.meals_section_week,
                MealsSection.Recipes to Res.string.meals_section_recipes,
            )
            sections.forEachIndexed { i, (s, label) ->
                SegmentedButton(
                    selected = section == s,
                    onClick = { section = s },
                    shape = SegmentedButtonDefaults.itemShape(i, sections.size),
                    label = { Text(stringResource(label), maxLines = 1) },
                )
            }
        }
        val bottom = PaddingValues(bottom = contentPadding.calculateBottomPadding())
        when (section) {
            MealsSection.Week -> WeekPlanScreen(
                state = state,
                bottomPadding = bottom,
                actions = WeekActions(
                    onPrevious = viewModel::previousWeek,
                    onNext = viewModel::nextWeek,
                    onThisWeek = viewModel::thisWeek,
                    onOpenDay = viewModel::openDay,
                    onCopyPrevious = viewModel::copyPreviousWeek,
                    onSaveFavorite = viewModel::saveFavorite,
                    onApplyFavorite = viewModel::applyFavorite,
                    onDeleteFavorite = viewModel::deleteFavorite,
                    onSetLunch = viewModel::setLunch,
                    onOpenRecipe = onOpenRecipe,
                    onShopForWeek = onShopForWeek,
                ),
            )
            MealsSection.Recipes -> RecipeLibraryScreen(
                state = libraryState,
                bottomPadding = bottom,
                onQuery = library::setQuery,
                onToggleTag = library::toggleTag,
                onOpen = onOpenRecipe,
                onNew = onNewRecipe,
                onImport = onImportRecipe,
            )
        }
    }
    editor?.let { e ->
        DayPlanSheet(
            state = e,
            canEdit = state.canEdit,
            onDismiss = viewModel::closeDay,
            onSave = { title, recipeId, cookDouble, servings, note ->
                viewModel.plan(e.date, e.slot, title, recipeId, cookDouble, servings, note)
                viewModel.closeDay()
            },
            onClear = {
                viewModel.clear(e.date, e.slot)
                viewModel.closeDay()
            },
            onMove = { to ->
                viewModel.move(e.date, to, e.slot)
                viewModel.closeDay()
            },
            onOpenRecipe = { id ->
                viewModel.closeDay()
                onOpenRecipe(id)
            },
        )
    }
}

data class WeekActions(
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onThisWeek: () -> Unit = {},
    val onOpenDay: (LocalDate, MealSlot) -> Unit = { _, _ -> },
    val onCopyPrevious: () -> Unit = {},
    val onSaveFavorite: (String) -> Unit = {},
    val onApplyFavorite: (FavoriteWeek) -> Unit = {},
    val onDeleteFavorite: (FavoriteWeek) -> Unit = {},
    val onSetLunch: (childId: String, date: LocalDate, dishes: String) -> Unit = { _, _, _ -> },
    val onOpenRecipe: (String) -> Unit = {},
    val onShopForWeek: () -> Unit = {},
)

private enum class WeekDialog { None, SaveFavorite, Favorites }

@Composable
fun WeekPlanScreen(
    state: MealsUiState,
    bottomPadding: PaddingValues = PaddingValues(),
    actions: WeekActions = WeekActions(),
) {
    if (state.loading) {
        LoadingBox()
        return
    }
    var dialog by rememberSaveable { mutableStateOf(WeekDialog.None) }
    var lunchTarget by remember { mutableStateOf<Pair<Child, DayPlan>?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screenHorizontal,
            end = Spacing.screenHorizontal,
            top = Spacing.xs,
            bottom = Spacing.xxl * 3 + bottomPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "header") { WeekHeader(state, actions, onDialog = { dialog = it }) }
        items(state.days, key = { it.date.toString() }) { day ->
            DayCard(
                day = day,
                state = state,
                onOpen = { slot -> actions.onOpenDay(day.date, slot) },
                onOpenRecipe = actions.onOpenRecipe,
                onEditLunch = { child -> lunchTarget = child to day },
            )
        }
    }
    when (dialog) {
        WeekDialog.None -> Unit
        WeekDialog.SaveFavorite -> FavoriteNameDialog(
            onDismiss = { dialog = WeekDialog.None },
            onSave = {
                actions.onSaveFavorite(it)
                dialog = WeekDialog.None
            },
        )
        WeekDialog.Favorites -> FavoritesDialog(
            state = state,
            onDismiss = { dialog = WeekDialog.None },
            onApply = {
                actions.onApplyFavorite(it)
                dialog = WeekDialog.None
            },
            onDelete = actions.onDeleteFavorite,
        )
    }
    lunchTarget?.let { (child, day) ->
        LunchDialog(
            child = child,
            day = day,
            onDismiss = { lunchTarget = null },
            onSave = { text ->
                actions.onSetLunch(child.id, day.date, text)
                lunchTarget = null
            },
        )
    }
}

@Composable
private fun WeekHeader(state: MealsUiState, actions: WeekActions, onDialog: (WeekDialog) -> Unit) {
    val week = state.week ?: return
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onPrevious) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(Res.string.meals_previous_week))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(weekLong(week.week), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(
                stringResource(Res.string.time_range, formatDateShort(week.monday), formatDateShort(week.sunday)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = actions.onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(Res.string.meals_next_week))
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.meals_week_menu))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (state.days.none { it.isToday }) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.meals_this_week)) }, onClick = {
                        menu = false
                        actions.onThisWeek()
                    })
                }
                DropdownMenuItem(text = { Text(stringResource(Res.string.meals_shop_for_week)) }, onClick = {
                    menu = false
                    actions.onShopForWeek()
                })
                if (state.canEdit) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.meals_copy_previous)) },
                        enabled = state.previousWeekPlanned,
                        onClick = {
                            menu = false
                            actions.onCopyPrevious()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.meals_save_favorite)) },
                        enabled = state.plannedCount > 0,
                        onClick = {
                            menu = false
                            onDialog(WeekDialog.SaveFavorite)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.meals_favorites)) },
                        enabled = state.favorites.isNotEmpty(),
                        onClick = {
                            menu = false
                            onDialog(WeekDialog.Favorites)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCard(
    day: DayPlan,
    state: MealsUiState,
    onOpen: (MealSlot) -> Unit,
    onOpenRecipe: (String) -> Unit,
    onEditLunch: (Child) -> Unit,
) {
    SectionCard(title = null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                weekdayName(day.date.dayOfWeek).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (day.isToday) FontWeight.Bold else null,
                color = if (day.redDay) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(formatDateShort(day.date), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (day.isToday) {
                Text(
                    stringResource(Res.string.today),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        day.holidays.forEach { h ->
            Text(
                stringResource(Labels.holiday(h.id)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.slots.forEach { slot ->
            val entry = day.entries[slot]
            if (entry != null) {
                PlannedMeal(
                    entry = entry,
                    slot = slot,
                    state = state,
                    showSlot = state.slots.size > 1,
                    onOpen = { onOpen(slot) },
                    onOpenRecipe = onOpenRecipe,
                )
            } else if (state.canEdit) {
                TextButton(onClick = { onOpen(slot) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = Spacing.xs))
                    Text(stringResource(Res.string.meals_plan_slot, stringResource(Labels.mealSlot(slot)).lowercase()))
                }
            } else {
                Text(stringResource(Res.string.meals_nothing_planned), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (day.busy.isNotEmpty() && day.dinner == null) {
            val first = day.busy.first()
            Text(
                stringResource(Res.string.meals_busy_evening, first.event.title, formatTime(first.start.time)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        LunchRows(day, state, onEditLunch)
    }
}

@Composable
private fun PlannedMeal(
    entry: MealEntry,
    slot: MealSlot,
    state: MealsUiState,
    showSlot: Boolean,
    onOpen: () -> Unit,
    onOpenRecipe: (String) -> Unit,
) {
    val recipe: Recipe? = entry.recipeId?.let { id -> state.recipes.firstOrNull { it.id == id } }
    val details = buildList {
        entry.leftoversFrom?.let { add(stringResource(Res.string.meals_leftovers_from, weekdayName(it.dayOfWeek))) }
        if (entry.cookDouble) add(stringResource(Res.string.meals_cook_double))
        recipe?.totalMinutes?.let { add(stringResource(Res.string.recipe_minutes, it)) }
        if (entry.note.isNotBlank()) add(entry.note)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .clickable(enabled = state.canEdit, role = Role.Button, onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (showSlot) {
                Text(
                    stringResource(Labels.mealSlot(slot)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(recipe?.title ?: entry.title, style = MaterialTheme.typography.bodyLarge)
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (recipe != null) SmallAction(stringResource(Res.string.meals_open_recipe)) { onOpenRecipe(recipe.id) }
    }
}

@Composable
private fun LunchRows(day: DayPlan, state: MealsUiState, onEditLunch: (Child) -> Unit) {
    val byChild = day.lunches.associateBy { it.child.id }
    state.children.forEach { child ->
        val lunch = byChild[child.id]
        val manual = child.lunch?.provider == LunchProviderType.Manual
        if (lunch == null && !(manual && state.canEdit)) return@forEach
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = manual && state.canEdit, role = Role.Button) { onEditLunch(child) }
                .padding(vertical = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            ColorBadge(child.color, child.avatar ?: child.firstName.take(1).uppercase(), child.firstName, size = 20.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    if (lunch != null) {
                        stringResource(Res.string.today_lunch, child.firstName, lunch.dishes.first())
                    } else {
                        stringResource(Res.string.meals_add_lunch, child.firstName)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (lunch != null && lunch.clash.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            stringResource(Res.string.meals_clash, child.firstName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteNameDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.meals_save_favorite)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.meals_favorite_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Composable
private fun FavoritesDialog(
    state: MealsUiState,
    onDismiss: () -> Unit,
    onApply: (FavoriteWeek) -> Unit,
    onDelete: (FavoriteWeek) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.meals_favorites)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(Res.string.meals_favorites_hint), style = MaterialTheme.typography.bodyMedium)
                state.favorites.forEach { fav ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable(role = Role.Button) { onApply(fav) }
                                .padding(vertical = Spacing.sm),
                        ) {
                            Text(fav.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                fav.days.entries.sortedBy { it.key }.joinToString(", ") { it.value.title },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        SmallAction(stringResource(Res.string.action_delete)) { onDelete(fav) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) } },
    )
}

@Composable
private fun LunchDialog(child: Child, day: DayPlan, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val current = day.lunches.firstOrNull { it.child.id == child.id }?.dishes?.joinToString("\n").orEmpty()
    var text by rememberSaveable(child.id, day.date.toString()) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.meals_lunch_title, child.firstName, weekdayName(day.date.dayOfWeek))) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(Res.string.meals_lunch_hint)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(Res.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
