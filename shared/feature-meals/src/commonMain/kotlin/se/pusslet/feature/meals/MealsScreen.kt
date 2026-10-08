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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.MealSlot
import se.pusslet.core.time.IsoWeek
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.format.weekdayName
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/**
 * The Mat tab: this week's meals with school lunches, clashes and busy evenings, one tap to plan a day.
 * [focusDate] opens a day directly (Today's "Planera middag").
 */
@Composable
fun MealsRoute(
    onOpenRecipe: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    focusDate: LocalDate? = null,
    onFocusHandled: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: MealsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(focusDate) {
        if (focusDate != null) {
            viewModel.openDay(focusDate)
            onFocusHandled()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { m -> snackbar.showSnackbar(messageText(m)) }
    }
    Box(Modifier.fillMaxSize()) {
        MealsScreen(
            state = state,
            contentPadding = contentPadding,
            onPrevious = viewModel::previousWeek,
            onNext = viewModel::nextWeek,
            onThisWeek = viewModel::thisWeek,
            onOpenDay = viewModel::openDay,
            onOpenLibrary = onOpenLibrary,
            onShopWeek = { viewModel.previewShopping() },
            onCopyLastWeek = viewModel::copyLastWeek,
            onSaveFavorite = viewModel::saveFavorite,
            onApplyFavorite = viewModel::applyFavorite,
            onDeleteFavorite = viewModel::deleteFavorite,
            onLoadNutrition = viewModel::loadNutrition,
        )
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + SNACKBAR_LIFT),
        )
    }
    state.sheet?.let { sheet ->
        MealDaySheet(
            sheet = sheet,
            canEdit = state.canEdit,
            weekDays = state.days.map { it.date },
            onDismiss = viewModel::closeDay,
            onPlanRecipe = { viewModel.planRecipe(sheet.date, sheet.slot, it) },
            onPlanText = { viewModel.planText(sheet.date, sheet.slot, it) },
            onServings = { viewModel.setServings(sheet.date, sheet.slot, it) },
            onCookDouble = { viewModel.setCookDouble(sheet.date, sheet.slot, it) },
            onClear = { viewModel.clear(sheet.date, sheet.slot) },
            onMove = { viewModel.move(sheet.date, it, sheet.slot) },
            onSetLunch = { childId, text -> viewModel.setLunch(childId, sheet.date, text) },
            onOpenRecipe = {
                viewModel.closeDay()
                onOpenRecipe(it)
            },
        )
    }
    state.shopping?.let { preview ->
        ShoppingPreviewDialog(
            preview = preview,
            lists = state.lists,
            onPickList = { viewModel.previewShopping(it) },
            onConfirm = viewModel::confirmShopping,
            onDismiss = viewModel::dismissShopping,
        )
    }
}

private suspend fun messageText(m: MealsMessage): String = when (m) {
    MealsMessage.Copied -> getString(Res.string.meals_copied)
    MealsMessage.FavoriteSaved -> getString(Res.string.meals_favorite_saved)
    is MealsMessage.FavoriteApplied -> getString(Res.string.meals_favorite_applied, m.name)
    is MealsMessage.Moved -> getString(Res.string.meals_moved, getStringArray(Res.array.weekdays)[m.to.dayOfWeek.isoDayNumber - 1])
    is MealsMessage.ShoppingDone -> getString(Res.string.meals_shop_done, m.listName)
    MealsMessage.NutritionOffline -> getString(Res.string.nutrition_offline)
    MealsMessage.NoShoppingList -> getString(Res.string.meals_shop_no_list)
}

@Composable
fun MealsScreen(
    state: MealsUiState,
    contentPadding: PaddingValues = PaddingValues(),
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onThisWeek: () -> Unit = {},
    onOpenDay: (LocalDate, MealSlot) -> Unit = { _, _ -> },
    onOpenLibrary: () -> Unit = {},
    onShopWeek: () -> Unit = {},
    onCopyLastWeek: () -> Unit = {},
    onSaveFavorite: (String) -> Unit = {},
    onApplyFavorite: (se.pusslet.core.model.FavoriteWeek) -> Unit = {},
    onDeleteFavorite: (String) -> Unit = {},
    onLoadNutrition: () -> Unit = {},
) {
    val week = state.week
    if (state.loading || week == null) {
        LoadingBox()
        return
    }
    var dialog by rememberSaveable { mutableStateOf<WeekDialog?>(null) }
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        WeekHeader(week, state, onPrevious, onNext, onThisWeek, onCopyLastWeek, onOpenDialog = { dialog = it })
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.screenHorizontal,
                end = Spacing.screenHorizontal,
                top = Spacing.xs,
                bottom = Spacing.xxl * 3 + contentPadding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "actions") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    AssistChip(
                        onClick = onOpenLibrary,
                        label = { Text(stringResource(Res.string.meals_recipes)) },
                        leadingIcon = { Icon(PussletIcons.Restaurant, contentDescription = null, Modifier.size(18.dp)) },
                    )
                    if (state.canEdit) {
                        AssistChip(
                            onClick = onShopWeek,
                            label = { Text(stringResource(Res.string.meals_shop_week)) },
                            leadingIcon = { Icon(Icons.Filled.ShoppingCart, contentDescription = null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            items(state.days, key = { it.date.toString() }) { day ->
                DayCard(day, isToday = day.date == state.today, canEdit = state.canEdit, onOpen = { slot -> onOpenDay(day.date, slot) })
            }
            item(key = "nutrition") { WeekNutritionCard(state, onLoadNutrition) }
        }
    }
    when (val d = dialog) {
        WeekDialog.SaveFavorite -> SaveFavoriteDialog(
            defaultName = stringResource(Res.string.meals_favorite_default_name, week.week),
            onSave = {
                onSaveFavorite(it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        WeekDialog.ApplyFavorite -> ApplyFavoriteDialog(
            favorites = state.favorites,
            onApply = {
                onApplyFavorite(it)
                dialog = null
            },
            onDelete = onDeleteFavorite,
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

internal enum class WeekDialog { SaveFavorite, ApplyFavorite }

@Composable
private fun WeekHeader(
    week: IsoWeek,
    state: MealsUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onThisWeek: () -> Unit,
    onCopyLastWeek: () -> Unit,
    onOpenDialog: (WeekDialog) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(Res.string.meals_previous_week))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(Res.string.meals_week_title, week.week),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(Res.string.meals_week_range, formatDateShort(week.monday), formatDateShort(week.sunday)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.today != null && state.today !in week) {
            TextButton(onClick = onThisWeek) { Text(stringResource(Res.string.meals_this_week), maxLines = 1) }
        }
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(Res.string.meals_next_week))
        }
        if (state.canEdit) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.meals_week_menu))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.meals_copy_last_week)) }, onClick = {
                        menu = false
                        onCopyLastWeek()
                    })
                    DropdownMenuItem(text = { Text(stringResource(Res.string.meals_save_favorite)) }, onClick = {
                        menu = false
                        onOpenDialog(WeekDialog.SaveFavorite)
                    })
                    DropdownMenuItem(text = { Text(stringResource(Res.string.meals_apply_favorite)) }, onClick = {
                        menu = false
                        onOpenDialog(WeekDialog.ApplyFavorite)
                    })
                }
            }
        }
    }
}

@Composable
private fun DayCard(day: MealDay, isToday: Boolean, canEdit: Boolean, onOpen: (MealSlot) -> Unit) {
    val colors = if (isToday) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    }
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(Modifier.padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDateLong(day.date).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (isToday) {
                    Text(
                        stringResource(Res.string.meals_today_badge),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            day.meals.forEach { meal -> MealRow(meal, showSlot = day.meals.size > 1, canEdit = canEdit, onClick = { onOpen(meal.slot) }) }
            if (day.busy.isNotEmpty()) Note(busyText(day.busy), PussletIcons.CalendarMonth)
            val withDishes = day.lunches.filter { it.dishes.isNotEmpty() }
            if (withDishes.isNotEmpty()) {
                Column(Modifier.padding(horizontal = Spacing.lg)) {
                    Text(
                        stringResource(Res.string.meals_school_lunch),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Siblings at the same school usually eat the same thing: one line for both.
                    withDishes.groupBy { it.dishes.first() }.forEach { (dish, list) ->
                        Text(
                            stringResource(Res.string.meals_lunch_line, joinNames(list.map { it.child.name }), dish),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MealRow(meal: PlannedMeal, showSlot: Boolean, canEdit: Boolean, onClick: () -> Unit) {
    val entry = meal.entry
    Column(
        // Padding rather than a minimum height keeps short rows tidy while staying above 48 dp.
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (showSlot) {
                Text(
                    stringResource(Labels.mealSlot(meal.slot)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry == null) {
                Text(
                    if (canEdit) stringResource(Res.string.meals_plan) else "–",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.servings?.let {
                    Text(
                        stringResource(Res.string.meals_servings_short, it),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (entry?.cookDouble == true) {
            Text(
                stringResource(Res.string.meals_cook_double),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        entry?.leftoversFrom?.let {
            Text(
                stringResource(Res.string.meals_leftovers_from, weekdayName(it.dayOfWeek)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ClashLines(meal.clashes)
        if (meal.conflicts.isNotEmpty()) WarningLine(conflictText(meal.conflicts))
    }
}

/** "Fotboll 17:30 och Kundmöte 15:45 – ta något snabbt". */
@Composable
internal fun busyText(busy: List<se.pusslet.core.model.EventOccurrence>): String = stringResource(
    Res.string.meals_busy,
    joinNames(busy.sortedBy { it.start }.map { stringResource(Res.string.meals_activity, it.event.title, formatTime(it.start.time)) }),
)

/** "Ella och Simon har redan ätit köttbullar till lunch" – one line per dish, not per child. */
@Composable
internal fun ClashLines(clashes: List<LunchClash>) {
    clashes.groupBy { it.dish.lowercase() }.forEach { (dish, list) ->
        WarningLine(stringResource(Res.string.today_clash, joinNames(list.map { it.child.name }), dish))
    }
}

@Composable
internal fun conflictText(conflicts: List<ConflictNote>): String {
    val parts = conflicts.map { c ->
        val what = c.allergen?.let { stringResource(Labels.allergen(it)) } ?: c.diet?.let { stringResource(Labels.diet(it)) }.orEmpty()
        stringResource(Res.string.meals_conflict_item, what, c.person.name)
    }
    return stringResource(Res.string.meals_diet_warning, joinNames(parts))
}

@Composable
internal fun WarningLine(text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(16.dp).padding(top = 2.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun Note(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        Modifier.padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val SNACKBAR_LIFT = 72.dp
