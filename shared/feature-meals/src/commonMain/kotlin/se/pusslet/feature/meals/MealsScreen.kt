package se.pusslet.feature.meals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.Child
import se.pusslet.core.model.FavoriteWeek
import se.pusslet.core.model.LunchProviderType
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Recipe
import se.pusslet.ui.components.InfoPill
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.ListRow
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.RegisterAddButton
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.SelectionColors
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
 * The round + plans the next dinner on Matsedel and adds a recipe on Recept (UX plan 1.2). [onPasteRecipe] opens the
 * recipe editor with the paste dialog; until the shell wires it, "Klistra in text" opens the plain editor.
 */
@Composable
fun MealsRoute(
    onOpenRecipe: (String) -> Unit,
    onNewRecipe: () -> Unit,
    onImportRecipe: () -> Unit,
    onShopForWeek: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    onPasteRecipe: () -> Unit = onNewRecipe,
    viewModel: MealsViewModel = koinViewModel(),
    library: RecipeLibraryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editor by viewModel.dayEditor.collectAsStateWithLifecycle()
    val libraryState by library.state.collectAsStateWithLifecycle()
    val weekNutrition by viewModel.weekNutrition.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(MealsSection.Week) }
    var choosingRecipe by rememberSaveable { mutableStateOf(false) }

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
                    colors = SelectionColors.segmentedButton(),
                    label = { Text(stringResource(label), maxLines = 1) },
                )
            }
        }
        val bottom = PaddingValues(bottom = contentPadding.calculateBottomPadding())
        when (section) {
            MealsSection.Week -> {
                RegisterAddButton(
                    label = stringResource(Res.string.meals_plan_slot, stringResource(Labels.mealSlot(state.mainSlot)).lowercase()),
                    enabled = state.canEdit && !state.loading,
                    onClick = viewModel::openNextToPlan,
                )
                WeekPlanScreen(
                    state = state,
                    weekNutrition = weekNutrition,
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
                        onCalculateNutrition = viewModel::calculateWeekNutrition,
                    ),
                )
            }
            MealsSection.Recipes -> {
                RegisterAddButton(
                    label = stringResource(Res.string.recipe_new),
                    enabled = libraryState.canEdit && !libraryState.loading,
                    onClick = { choosingRecipe = true },
                )
                RecipeLibraryScreen(
                    state = libraryState,
                    bottomPadding = bottom,
                    onQuery = library::setQuery,
                    onToggleTag = library::toggleTag,
                    onOpen = onOpenRecipe,
                )
            }
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
    if (choosingRecipe) {
        NewRecipeSheet(
            onDismiss = { choosingRecipe = false },
            onImport = onImportRecipe,
            onTypeIn = onNewRecipe,
            onPaste = onPasteRecipe,
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
    val onCalculateNutrition: () -> Unit = {},
)

private enum class WeekDialog { None, SaveFavorite, Favorites }

/** Matsedel: the week header stays put and the days scroll, opening at today's card (UX plan 1.11). */
@Composable
fun WeekPlanScreen(
    state: MealsUiState,
    weekNutrition: WeekNutritionState = WeekNutritionState.Idle,
    bottomPadding: PaddingValues = PaddingValues(),
    actions: WeekActions = WeekActions(),
) {
    if (state.loading) {
        LoadingBox()
        return
    }
    var dialog by rememberSaveable { mutableStateOf(WeekDialog.None) }
    var lunchTarget by remember { mutableStateOf<Pair<List<Child>, DayPlan>?>(null) }
    val listState = rememberLazyListState()
    // This week opens at today's card (not Monday); other weeks open at Monday. Only once per week shown, so coming
    // back from a recipe keeps the place in the list.
    var scrolledTo by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.week) {
        val week = state.week?.let { "${it.weekYear}-${it.week}" }
        if (week != scrolledTo) {
            scrolledTo = week
            listState.scrollToItem(state.days.indexOfFirst { it.isToday }.coerceAtLeast(0))
        }
    }
    Column(Modifier.fillMaxSize()) {
        WeekHeader(state, actions, onDialog = { dialog = it }, modifier = Modifier.padding(horizontal = Spacing.screenHorizontal))
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            state = listState,
            contentPadding = PaddingValues(
                start = Spacing.screenHorizontal,
                end = Spacing.screenHorizontal,
                top = Spacing.xs,
                bottom = Spacing.listBottomInset + bottomPadding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            items(state.days, key = { it.date.toString() }) { day ->
                DayCard(
                    day = day,
                    state = state,
                    onOpen = { slot -> actions.onOpenDay(day.date, slot) },
                    onOpenRecipe = actions.onOpenRecipe,
                    onEditLunch = { children -> lunchTarget = children to day },
                )
            }
            if (state.plannedCount > 0) {
                item(key = "nutrition") { WeekNutritionCard(weekNutrition, actions.onCalculateNutrition) }
            }
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
    lunchTarget?.let { (children, day) ->
        LunchDialog(
            children = children,
            day = day,
            onDismiss = { lunchTarget = null },
            onSave = { text ->
                // Children who shared the row get the same lunch.
                children.forEach { actions.onSetLunch(it.id, day.date, text) }
                lunchTarget = null
            },
        )
    }
}

@Composable
private fun WeekHeader(state: MealsUiState, actions: WeekActions, onDialog: (WeekDialog) -> Unit, modifier: Modifier = Modifier) {
    val week = state.week ?: return
    var menu by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
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
    onEditLunch: (List<Child>) -> Unit,
) {
    DayContainer(focus = day.isToday) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                weekdayName(day.date.dayOfWeek).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleMedium,
                color = if (day.redDay) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(formatDateShort(day.date), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (day.isToday) InfoPill(stringResource(Res.string.today), selected = true)
        }
        day.holidays.forEach { h ->
            Text(
                stringResource(Labels.holiday(h.id)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val showSlot = state.slots.size > 1
        state.slots.forEach { slot ->
            val entry = day.entries[slot]
            when {
                entry != null -> PlannedMeal(
                    entry = entry,
                    slot = slot,
                    state = state,
                    showSlot = showSlot,
                    onOpen = { onOpen(slot) },
                    onOpenRecipe = onOpenRecipe,
                )
                state.canEdit -> PlanRow(slot, showSlot, onClick = { onOpen(slot) })
                else -> Text(stringResource(Res.string.meals_nothing_planned), color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** Today's card is the screen's one focus surface (havre); the other days are flat (UX plan 1.11). */
@Composable
private fun DayContainer(focus: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (focus) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm), content = content)
        }
    } else {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            content = content,
        )
    }
}

/** The quiet "Planera" row of an empty day; TalkBack hears "Planera middag". */
@Composable
private fun PlanRow(slot: MealSlot, showSlot: Boolean, onClick: () -> Unit) {
    val slotName = stringResource(Labels.mealSlot(slot)).lowercase()
    val description = stringResource(Res.string.meals_plan_slot, slotName)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (showSlot) description else stringResource(Res.string.meals_plan_short),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
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
    // Only what is special about this dinner; time, portions and tags live in the recipe.
    val details = buildList {
        entry.leftoversFrom?.let { add(stringResource(Res.string.meals_leftovers_from, weekdayName(it.dayOfWeek))) }
        if (entry.cookDouble) add(stringResource(Res.string.meals_cook_double))
        if (entry.note.isNotBlank()) add(entry.note)
    }
    // Parents open the planning sheet; helpers, who cannot plan, open the recipe.
    val onRowClick: (() -> Unit)? = when {
        state.canEdit -> onOpen
        recipe != null -> {
            { onOpenRecipe(recipe.id) }
        }
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .clickable(enabled = onRowClick != null, role = Role.Button) { onRowClick?.invoke() },
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
        if (recipe != null) {
            IconButton(onClick = { onOpenRecipe(recipe.id) }) {
                Icon(
                    PussletIcons.ChevronRight,
                    contentDescription = stringResource(Res.string.meals_open_recipe),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One row per school lunch, shared by the children who eat the same dish, and the clash with dinner said once. Typed-in
 * lunches can be filled in on school days only (not weekends, holidays, "lov" or "studiedag").
 */
@Composable
private fun LunchRows(day: DayPlan, state: MealsUiState, onEditLunch: (List<Child>) -> Unit) {
    val manualIds = state.children.filter { it.lunch?.provider == LunchProviderType.Manual }.map { it.id }.toSet()
    groupLunches(day.lunches).forEach { group ->
        val editable = if (state.canEdit) group.children.filter { it.id in manualIds } else emptyList()
        LunchLine(
            text = stringResource(Res.string.today_lunch, group.children.joinToString(", ") { it.firstName }, group.dishes.first()),
            onClick = if (editable.isNotEmpty()) {
                { onEditLunch(editable) }
            } else {
                null
            },
        )
    }
    val clashing = day.lunches.filter { it.clash.isNotEmpty() }
    if (clashing.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(Res.string.meals_clash, clashing.joinToString(", ") { it.child.firstName }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
    if (!state.canEdit) return
    val withLunch = day.lunches.map { it.child.id }.toSet()
    state.children
        .filter { it.id in manualIds && it.id !in withLunch && it.id !in day.noSchool }
        .forEach { child ->
            LunchLine(text = stringResource(Res.string.meals_add_lunch, child.firstName), onClick = { onEditLunch(listOf(child)) })
        }
}

@Composable
private fun LunchLine(text: String, onClick: (() -> Unit)?) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null, role = Role.Button) { onClick?.invoke() }
            .padding(vertical = Spacing.xxs),
    )
}

/** What the round + on Recept opens: a recipe from a link, typed in, or pasted text split into the fields. */
@Composable
private fun NewRecipeSheet(onDismiss: () -> Unit, onImport: () -> Unit, onTypeIn: () -> Unit, onPaste: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = Spacing.xl).navigationBarsPadding()) {
            Text(
                stringResource(Res.string.recipe_new),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm).semantics { heading() },
            )
            val choices = listOf(
                Res.string.meals_recipe_from_link to onImport,
                Res.string.meals_recipe_type_in to onTypeIn,
                Res.string.meals_recipe_paste_text to onPaste,
            )
            choices.forEach { (label, choose) ->
                ListRow(
                    title = stringResource(label),
                    modifier = Modifier
                        .clickable(role = Role.Button) {
                            onDismiss()
                            choose()
                        }
                        .padding(horizontal = Spacing.screenHorizontal),
                )
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
private fun LunchDialog(children: List<Child>, day: DayPlan, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val ids = children.map { it.id }
    val current = day.lunches.firstOrNull { it.child.id in ids }?.dishes?.joinToString("\n").orEmpty()
    var text by rememberSaveable(ids.joinToString(), day.date.toString()) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val names = children.joinToString(", ") { it.firstName }
            Text(stringResource(Res.string.meals_lunch_title, names, weekdayName(day.date.dayOfWeek)))
        },
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

/**
 * "Näring för veckan": folded to one row until asked for (the first time downloads Livsmedelsverket's food list), then
 * averaged per portion, neutral and with its source.
 */
@Composable
private fun WeekNutritionCard(state: WeekNutritionState, onCalculate: () -> Unit) {
    if (state == WeekNutritionState.Idle) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.primaryTouchTarget)
                .clickable(role = Role.Button, onClick = onCalculate)
                .padding(horizontal = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.week_nutrition_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    SectionCard(title = stringResource(Res.string.week_nutrition_title)) {
        when (state) {
            WeekNutritionState.Idle, WeekNutritionState.Loading -> BodyText(Res.string.nutrition_loading)
            WeekNutritionState.NoRecipes -> BodyText(Res.string.week_nutrition_no_recipes)
            WeekNutritionState.Unavailable -> {
                BodyText(Res.string.nutrition_unavailable)
                TextButton(onClick = onCalculate) { Text(stringResource(Res.string.action_retry)) }
            }
            is WeekNutritionState.Ready -> {
                Text(stringResource(Res.string.week_nutrition_average), style = MaterialTheme.typography.bodyMedium)
                NutrientRows(state.week.averagePerPortion)
                Text(
                    stringResource(Res.string.week_nutrition_coverage, state.week.estimated, state.week.meals),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            stringResource(Res.string.nutrition_source),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BodyText(text: StringResource) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
}
