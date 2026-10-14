package se.pusslet.feature.meals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealSlot
import se.pusslet.ui.components.HeroCard
import se.pusslet.ui.components.InfoPill
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.NavRow
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.weekdayName
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/** What Matsedel can do. A planned meal opens its read view ([onViewDay]); an empty day the planning sheet ([onPlanDay]). */
data class WeekActions(
    val onPlanDay: (LocalDate, MealSlot) -> Unit = { _, _ -> },
    val onViewDay: (LocalDate, MealSlot) -> Unit = { _, _ -> },
    val onCopyPrevious: () -> Unit = {},
    val onFillLunch: () -> Unit = {},
    val onShopForWeek: () -> Unit = {},
    val onOpenNutrition: () -> Unit = {},
)

/**
 * Matsedel: seven calm day cards that open at today's (the screen's one focus surface, UX plan 1.11), then the bridge to
 * Handla ("Handla till veckan"), one "Fyll i skollunch ›" for the week and "Näring för veckan ›" (UX plan, Mat 2, 3 and 6).
 * An empty week offers "Kopiera förra veckan" first when last week has something to copy (UX plan 4.5).
 */
@Composable
fun WeekPlanScreen(state: MealsUiState, bottomPadding: PaddingValues = PaddingValues(), actions: WeekActions = WeekActions()) {
    if (state.loading) {
        LoadingBox()
        return
    }
    val listState = rememberLazyListState()
    // This week opens at today's card (not Monday); other weeks open at Monday. Only once per week shown, so coming back
    // from a recipe keeps the place in the list. An empty week starts at the top, where "Kopiera förra veckan" is.
    var scrolledTo by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.week) {
        val week = state.week?.key
        if (week != scrolledTo) {
            scrolledTo = week
            val todayIndex = if (state.offerCopyPrevious) -1 else state.days.indexOfFirst { it.isToday }
            listState.scrollToItem(todayIndex.coerceAtLeast(0))
        }
    }
    LazyColumn(
        Modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(
            start = Spacing.screenHorizontal,
            end = Spacing.screenHorizontal,
            top = Spacing.xs,
            bottom = Spacing.listBottomInset + bottomPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (state.offerCopyPrevious) {
            item(key = "copy") {
                OutlinedButton(onClick = actions.onCopyPrevious) { Text(stringResource(Res.string.meals_copy_previous)) }
            }
        }
        items(state.days, key = { it.date.toString() }) { day -> DayCard(day, state, actions) }
        if (!state.weekIsEmpty) {
            item(key = "shop") {
                FilledTonalButton(onClick = actions.onShopForWeek, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.meals_shop_for_week))
                }
            }
        }
        if (state.showFillLunch) {
            item(key = "lunch") { NavRow(stringResource(Res.string.meals_fill_lunch), onClick = actions.onFillLunch) }
        }
        if (!state.weekIsEmpty) {
            item(key = "nutrition") { NavRow(stringResource(Res.string.week_nutrition_title), onClick = actions.onOpenNutrition) }
        }
    }
}

/**
 * One day: the weekday, the meal and at most one quiet line (UX plan, Mat 2). A planned meal opens its read view (›); an
 * empty day shows the quiet "Planera". Helpers, who cannot plan, see nothing for an empty day.
 */
@Composable
private fun DayCard(day: DayPlan, state: MealsUiState, actions: WeekActions) {
    DayContainer(focus = day.isToday) {
        DayHeading(day)
        val showSlot = state.slots.size > 1
        state.slots.forEach { slot ->
            val entry = day.entries[slot]
            val note = if (slot == state.mainSlot) dayNote(day, entry) else null
            when {
                entry != null -> PlannedRow(
                    title = mealTitle(entry, slot, showSlot, state),
                    note = note,
                    onClick = { actions.onViewDay(day.date, slot) },
                )
                state.canEdit -> {
                    PlanRow(slot, showSlot, onClick = { actions.onPlanDay(day.date, slot) })
                    note?.let { NoteLine(it) }
                }
                else -> note?.let { NoteLine(it) }
            }
        }
    }
}

/** Today's card is the screen's one focus surface (havre); the other days are flat (UX plan 1.11). */
@Composable
private fun DayContainer(focus: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (focus) {
        HeroCard(content = content)
    } else {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            content = content,
        )
    }
}

/** "Fredag 9 okt", a red day's name and the "Idag" pill, read as one heading. */
@Composable
private fun DayHeading(day: DayPlan) {
    FlowRow(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            weekdayName(day.date.dayOfWeek).replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.titleMedium,
            color = if (day.redDay) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(formatDateShort(day.date), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        day.holidays.firstOrNull()?.let { InfoPill(stringResource(Labels.holiday(it.id))) }
        if (day.isToday) InfoPill(stringResource(Res.string.today), selected = true)
    }
}

/** The dish; "Middag: Tacos" when the family plans more than one meal a day. */
@Composable
private fun mealTitle(entry: MealEntry, slot: MealSlot, showSlot: Boolean, state: MealsUiState): String {
    val title = entry.recipeId?.let { id -> state.recipes.firstOrNull { it.id == id }?.title } ?: entry.title
    return if (showSlot) stringResource(Res.string.meals_slot_dish, stringResource(Labels.mealSlot(slot)), title) else title
}

/** A planned meal: the dish in bodyLarge, its one quiet line and ›. The whole row opens the read view. */
@Composable
private fun PlannedRow(title: String, note: DayNote?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            note?.let { NoteLine(it) }
        }
        Icon(PussletIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** The one quiet line under a day's meal. [clash] marks "Samma som lunchen", which gets a terracotta icon. */
internal data class DayNote(val text: String, val clash: Boolean = false)

/**
 * What the one line under the meal says (UX plan, Mat 2–3): a clash with the school lunch wins, then the lunch itself (one
 * row for all children), then what is special about the dinner. An empty day says the busy evening first.
 */
@Composable
private fun dayNote(day: DayPlan, entry: MealEntry?): DayNote? {
    val clashing = day.lunches.filter { it.clash.isNotEmpty() }
    val lunch = lunchLine(groupLunches(day.lunches))
    val busy = day.busy.firstOrNull()
    return when {
        entry != null && clashing.isNotEmpty() ->
            DayNote(stringResource(Res.string.meals_clash, clashing.joinToString(", ") { it.child.firstName }), clash = true)
        entry == null && busy != null -> DayNote(
            stringResource(Res.string.meals_busy_evening, busy.event.title, formatTime(busy.start.time)),
        )
        lunch != null -> DayNote(lunch)
        entry != null -> mealDetails(entry)?.let { DayNote(it) }
        else -> null
    }
}

/** "Lunch: Köttbullar (Ella, Simon)"; children who eat something else follow after " · ": "Fiskgratäng (Ida)". */
@Composable
internal fun lunchLine(groups: List<LunchGroup>, wholeMenu: Boolean = false): String? {
    val first = groups.firstOrNull() ?: return null

    fun dishes(group: LunchGroup) = if (wholeMenu) group.dishes.joinToString(", ") else group.dishes.first()

    fun names(group: LunchGroup) = group.children.joinToString(", ") { it.firstName }
    val rest = groups.drop(1).map { stringResource(Res.string.today_item_for, dishes(it), names(it)) }
    return (listOf(stringResource(Res.string.today_lunch, names(first), dishes(first))) + rest).joinToString(" · ")
}

/** What is special about this dinner: "Rester från söndag · Gör dubbelt · med potatis". Time and portions live in the recipe. */
@Composable
internal fun mealDetails(entry: MealEntry): String? {
    val parts = buildList {
        entry.leftoversFrom?.let { add(stringResource(Res.string.meals_leftovers_from, weekdayName(it.dayOfWeek))) }
        if (entry.cookDouble) add(stringResource(Res.string.meals_cook_double))
        if (entry.note.isNotBlank()) add(entry.note)
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

@Composable
internal fun NoteLine(note: DayNote) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (note.clash) {
            Icon(
                PussletIcons.Restaurant,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(note.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
