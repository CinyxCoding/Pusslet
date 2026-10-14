package se.pusslet.feature.meals

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.time.IsoWeek
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.RegisterAddButton
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.components.WeekSwitcher
import se.pusslet.ui.components.showUndo
import se.pusslet.ui.format.monthName
import se.pusslet.ui.format.monthShort
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

enum class MealsSection { Week, Recipes }

private enum class WeekDialog { None, SaveFavorite, Favorites }

/**
 * The Mat tab: the week's meal plan (with school lunches, clash warnings and busy evenings) and the recipe library, under the
 * same header as Kalender (UX plan, Mat 1). The round + plans the next dinner on Matsedel and adds a recipe on Recept (UX
 * plan 1.2); their empty states ("Planera veckan", "Nytt recept") open the same (UX plan 4.5). Every change to the plan
 * gets a receipt with Ångra. [onPasteRecipe] opens the recipe editor with the paste dialog; until the shell wires it,
 * "Klistra in text" opens the plain editor. [onShopForWeek] leads to "Handla till veckan".
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
    val dayView by viewModel.dayView.collectAsStateWithLifecycle()
    val libraryState by library.state.collectAsStateWithLifecycle()
    val weekNutrition by viewModel.weekNutrition.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var section by rememberSaveable { mutableStateOf(MealsSection.Week) }
    var choosingRecipe by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf(WeekDialog.None) }
    var fillingLunch by rememberSaveable { mutableStateOf(false) }
    var showNutrition by rememberSaveable { mutableStateOf(false) }

    MealReceipts(viewModel, snackbar)
    when (section) {
        MealsSection.Week -> RegisterAddButton(
            label = stringResource(Res.string.meals_plan_slot, stringResource(Labels.mealSlot(state.mainSlot)).lowercase()),
            enabled = state.canEdit && !state.loading,
            onClick = viewModel::openNextToPlan,
        )
        MealsSection.Recipes -> RegisterAddButton(
            label = stringResource(Res.string.recipe_new),
            enabled = libraryState.canEdit && !libraryState.loading,
            onClick = { choosingRecipe = true },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            MealsHeader(
                section = section,
                state = state,
                library = libraryState,
                onSection = { section = it },
                onPrevious = viewModel::previousWeek,
                onNext = viewModel::nextWeek,
                onThisWeek = viewModel::thisWeek,
                onQuery = library::setQuery,
                onDialog = { dialog = it },
            )
            val bottom = PaddingValues(bottom = contentPadding.calculateBottomPadding())
            when (section) {
                MealsSection.Week -> WeekPlanScreen(
                    state = state,
                    bottomPadding = bottom,
                    actions = WeekActions(
                        onPlanDay = viewModel::openDay,
                        onViewDay = viewModel::viewDay,
                        onPlanWeek = viewModel::openNextToPlan,
                        onCopyPrevious = viewModel::copyPreviousWeek,
                        onFillLunch = { fillingLunch = true },
                        onShopForWeek = onShopForWeek,
                        onOpenNutrition = {
                            val calculated = weekNutrition is WeekNutritionState.Ready || weekNutrition == WeekNutritionState.Loading
                            if (!calculated) viewModel.calculateWeekNutrition()
                            showNutrition = true
                        },
                    ),
                )
                MealsSection.Recipes -> RecipeLibraryScreen(
                    state = libraryState,
                    bottomPadding = bottom,
                    onToggleTag = library::toggleTag,
                    onOpen = onOpenRecipe,
                    onAdd = { choosingRecipe = true },
                )
            }
        }
        // Above the round + and the bottom bar, like the shell's own receipts.
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + SNACKBAR_ABOVE_FAB),
        )
    }

    editor?.let { e ->
        DayPlanSheet(
            state = e,
            onDismiss = viewModel::closeDay,
            onSave = viewModel::saveDay,
            onPick = viewModel::pickSuggestion,
            onClear = viewModel::clearDay,
            onMove = viewModel::moveDay,
            onChangeDay = { viewModel.openDay(it, e.slot) },
        )
    }
    dayView?.let { v ->
        DayViewSheet(
            state = v,
            showSlot = state.slots.size > 1,
            onDismiss = viewModel::closeView,
            onOpenRecipe = { id ->
                viewModel.closeView()
                onOpenRecipe(id)
            },
            onEdit = viewModel::editViewed,
        )
    }
    if (fillingLunch && state.showFillLunch) {
        LunchWeekSheet(
            state = state,
            onDismiss = { fillingLunch = false },
            onSave = { children, dishes ->
                viewModel.setWeekLunches(children, dishes)
                fillingLunch = false
            },
        )
    }
    if (showNutrition) {
        WeekNutritionSheet(weekNutrition, onRetry = viewModel::calculateWeekNutrition, onDismiss = { showNutrition = false })
    }
    if (choosingRecipe) {
        NewRecipeSheet(
            onDismiss = { choosingRecipe = false },
            onImport = onImportRecipe,
            onTypeIn = onNewRecipe,
            onPaste = onPasteRecipe,
        )
    }
    when (dialog) {
        WeekDialog.None -> Unit
        WeekDialog.SaveFavorite -> FavoriteNameDialog(
            onDismiss = { dialog = WeekDialog.None },
            onSave = {
                viewModel.saveFavorite(it)
                dialog = WeekDialog.None
            },
        )
        WeekDialog.Favorites -> FavoritesDialog(
            state = state,
            onDismiss = { dialog = WeekDialog.None },
            onApply = {
                viewModel.applyFavorite(it)
                dialog = WeekDialog.None
            },
            onDelete = viewModel::deleteFavorite,
        )
    }
}

/** Receipts for changes to the plan: "Tis: Ugnspannkaka · Ångra" (UX plan, Mat 4–5). The newest replaces the one showing. */
@Composable
private fun MealReceipts(viewModel: MealsViewModel, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val weekdays by rememberUpdatedState(stringArrayResource(Res.array.weekdays_short))
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            // Launched so collecting never waits for the snackbar; showUndo dismisses the one on screen.
            scope.launch {
                val text = when (message) {
                    is MealsMessage.Planned -> getString(Res.string.meals_planned_receipt, dayShort(weekdays, message.date), message.title)
                    is MealsMessage.Removed -> getString(Res.string.meals_removed, message.title)
                    is MealsMessage.FavoriteApplied -> getString(Res.string.meals_favorite_applied, message.name)
                    is MealsMessage.CopiedPreviousWeek -> getString(Res.string.meals_copied)
                }
                if (snackbar.showUndo(text, getString(Res.string.action_undo))) viewModel.undo(message.undo)
            }
        }
    }
}

/** "Tis". */
private fun dayShort(weekdays: List<String>, date: LocalDate): String =
    weekdays[date.dayOfWeek.isoDayNumber - 1].replaceFirstChar { it.uppercase() }

/**
 * One control area, like Kalender's: on Matsedel the week ("Vecka 41 · oktober" with ‹ ›, "Idag" on another week), on Recept
 * the search field in the same place, so "Matsedel | Recept" never moves. ⋮ holds only the favourite weeks.
 */
@Composable
private fun MealsHeader(
    section: MealsSection,
    state: MealsUiState,
    library: RecipeLibraryState,
    onSection: (MealsSection) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onThisWeek: () -> Unit,
    onQuery: (String) -> Unit,
    onDialog: (WeekDialog) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screenHorizontal)) {
        val week = state.week
        when {
            section == MealsSection.Week && week != null -> WeekSwitcher(
                title = weekTitle(week, state.today),
                onPrevious = onPrevious,
                onNext = onNext,
                onToday = if (state.days.any { it.isToday }) null else onThisWeek,
            )
            section == MealsSection.Recipes && library.total > 0 -> RecipeSearchField(library.query, onQuery)
            else -> Spacer(Modifier.height(Spacing.primaryTouchTarget))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow {
                val sections = listOf(
                    MealsSection.Week to Res.string.meals_section_week,
                    MealsSection.Recipes to Res.string.meals_section_recipes,
                )
                sections.forEachIndexed { i, (s, label) ->
                    SegmentedButton(
                        selected = section == s,
                        onClick = { onSection(s) },
                        shape = SegmentedButtonDefaults.itemShape(i, sections.size),
                        colors = SelectionColors.segmentedButton(),
                        label = { Text(stringResource(label), maxLines = 1) },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (section == MealsSection.Week && state.canEdit && !state.loading) FavoritesMenu(state, onDialog)
        }
    }
}

/** ⋮ on Matsedel: only the favourite weeks (rare, so out of the way). */
@Composable
private fun FavoritesMenu(state: MealsUiState, onDialog: (WeekDialog) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.meals_week_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.meals_save_favorite)) },
                enabled = !state.weekIsEmpty,
                onClick = {
                    open = false
                    onDialog(WeekDialog.SaveFavorite)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.meals_favorites)) },
                enabled = state.favorites.isNotEmpty(),
                onClick = {
                    open = false
                    onDialog(WeekDialog.Favorites)
                },
            )
        }
    }
}

/** "Vecka 41 · oktober", "Vecka 44 · okt–nov", the same title as Kalender's; the year only when it is not this year. */
@Composable
private fun weekTitle(week: IsoWeek, today: LocalDate?): String {
    val monday = week.monday
    val sunday = week.sunday
    val year = if (today != null && sunday.year != today.year) " " + sunday.year else ""
    val months = if (monday.month == sunday.month) {
        monthName(monday.month.ordinal + 1)
    } else {
        stringResource(Res.string.calendar_range, monthShort(monday.month.ordinal + 1), monthShort(sunday.month.ordinal + 1))
    }
    return stringResource(Res.string.calendar_week_title, week.week, months + year)
}

private val SNACKBAR_ABOVE_FAB: Dp = 80.dp
