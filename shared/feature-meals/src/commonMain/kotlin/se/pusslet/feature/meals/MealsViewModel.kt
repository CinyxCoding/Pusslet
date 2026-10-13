package se.pusslet.feature.meals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.holidays.Holiday
import se.pusslet.core.holidays.SwedishHolidays
import se.pusslet.core.meals.EveningLoad
import se.pusslet.core.meals.MealClash
import se.pusslet.core.meals.MealPlanner
import se.pusslet.core.meals.MealSuggester
import se.pusslet.core.meals.MealSuggestion
import se.pusslet.core.model.Child
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilySettings
import se.pusslet.core.model.FavoriteWeek
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealPlanWeek
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.PantryItem
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.Role
import se.pusslet.core.model.SchoolLunch
import se.pusslet.core.nutrition.WeekNutrition
import se.pusslet.core.nutrition.WeekNutritionCalculator
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.isoWeek
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.LunchRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.Session
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.RecipeNutrition
import kotlin.time.Clock

/** A child's school lunch on a day, and the dish words it shares with the planned dinner (empty = no clash). */
data class ChildLunch(val child: Child, val dishes: List<String>, val clash: Set<String>)

data class DayPlan(
    val date: LocalDate,
    val entries: Map<MealSlot, MealEntry>,
    val lunches: List<ChildLunch>,
    /** Evening activities (16:30–19:00): suggest something quick. */
    val busy: List<EventOccurrence>,
    val holidays: List<Holiday>,
    /** Sunday or a public holiday (röd dag). */
    val redDay: Boolean,
    val isToday: Boolean,
) {
    val dinner: MealEntry? get() = entries[MealSlot.Dinner]
    val hasClash: Boolean get() = lunches.any { it.clash.isNotEmpty() }
}

data class MealsUiState(
    val loading: Boolean = true,
    val week: IsoWeek? = null,
    val days: List<DayPlan> = emptyList(),
    /** Slots the family plans (settings); dinner only by default. */
    val slots: List<MealSlot> = listOf(MealSlot.Dinner),
    val recipes: List<Recipe> = emptyList(),
    val favorites: List<FavoriteWeek> = emptyList(),
    val children: List<Child> = emptyList(),
    val previousWeekPlanned: Boolean = false,
    /** Helpers (caregivers) can look but not plan (Firestore rules). */
    val canEdit: Boolean = true,
) {
    val plannedCount: Int get() = days.count { it.dinner != null }
}

sealed interface WeekNutritionState {
    data object Idle : WeekNutritionState

    data object Loading : WeekNutritionState

    /** No planned meal uses a recipe, so there is nothing to estimate. */
    data object NoRecipes : WeekNutritionState

    /** The food database has not been downloaded yet and the phone is offline. */
    data object Unavailable : WeekNutritionState

    data class Ready(val week: WeekNutrition) : WeekNutritionState
}

/** The bottom sheet for planning one meal: current entry, explainable suggestions and the day's context. */
data class DayEditorState(
    val date: LocalDate,
    val slot: MealSlot,
    val entry: MealEntry?,
    val suggestions: List<MealSuggestion>,
    val recipes: List<Recipe>,
    val lunches: List<ChildLunch>,
    val busy: List<EventOccurrence>,
    val weekDays: List<LocalDate>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MealsViewModel(
    private val meals: MealRepository,
    recipes: RecipeRepository,
    private val family: FamilyRepository,
    private val lunches: LunchRepository,
    private val calendar: CalendarRepository,
    shopping: ShoppingRepository,
    sessions: SessionProvider,
    private val nutrition: RecipeNutrition,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val today: LocalDate get() = clock.now().toLocalDateTime(zone).date
    private val week = MutableStateFlow(today.isoWeek)
    private val editing = MutableStateFlow<Pair<LocalDate, MealSlot>?>(null)
    private val weekNutritionState = MutableStateFlow<WeekNutritionState>(WeekNutritionState.Idle)

    /** Calculated on request (the first time downloads Livsmedelsverket's food list); reset when the week changes. */
    val weekNutrition: StateFlow<WeekNutritionState> = weekNutritionState

    private data class Household(
        val children: List<Child>,
        val settings: FamilySettings,
        val session: Session,
        val lunches: Map<String, List<SchoolLunch>>,
        val pantry: List<PantryItem>,
    )

    private val household: Flow<Household> = combine(
        family.children,
        family.settings,
        sessions.session,
        lunches.lunches,
        shopping.pantry,
    ) { c, s, se, l, p -> Household(c, s, se, l, p) }

    private val occurrences: Flow<List<EventOccurrence>> = week.flatMapLatest { w -> calendar.occurrences(w.monday, w.sunday, zone) }

    private data class Plans(
        val week: IsoWeek,
        val weeks: List<MealPlanWeek>,
        val recipes: List<Recipe>,
        val favorites: List<FavoriteWeek>,
        val occurrences: List<EventOccurrence>,
    )

    private val plans: Flow<Plans> = combine(week, meals.weeks, recipes.recipes, meals.favorites, occurrences) { w, ws, r, f, o ->
        Plans(w, ws, r, f, o)
    }

    val state: StateFlow<MealsUiState> = combine(plans, household) { p, h -> build(p, h) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MealsUiState())

    val dayEditor: StateFlow<DayEditorState?> = combine(editing, plans, household) { target, p, h ->
        target?.let { (date, slot) -> editorFor(date, slot, p, h) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private fun build(p: Plans, h: Household): MealsUiState {
        val plan = p.weeks.firstOrNull { it.week == p.week } ?: MealPlanWeek(p.week)
        val now = today
        val days = p.week.days.map { date -> dayPlan(date, plan, p, h, now) }
        return MealsUiState(
            loading = false,
            week = p.week,
            days = days,
            slots = MealSlot.entries.filter { it in h.settings.mealSlots }.ifEmpty { listOf(MealSlot.Dinner) },
            recipes = p.recipes,
            favorites = p.favorites.sortedBy { it.name.lowercase() },
            children = h.children,
            previousWeekPlanned = p.weeks.any { it.week == p.week.previous() && it.entries.isNotEmpty() },
            canEdit = h.session.role == Role.Parent,
        )
    }

    private fun dayPlan(date: LocalDate, plan: MealPlanWeek, p: Plans, h: Household, now: LocalDate): DayPlan {
        val entries = MealSlot.entries.mapNotNull { slot -> plan.entry(date, slot)?.let { slot to it } }.toMap()
        val dinnerTitle = entries[MealSlot.Dinner]?.let { e ->
            e.recipeId?.let { id -> p.recipes.firstOrNull { it.id == id }?.title } ?: e.title
        }
        val lunchesToday = h.children.mapNotNull { child ->
            val lunch = h.lunches[child.id].orEmpty().firstOrNull { it.date == date } ?: return@mapNotNull null
            ChildLunch(child, lunch.dishes, dinnerTitle?.let { MealClash.between(lunch.dishes, it) }.orEmpty())
        }
        return DayPlan(
            date = date,
            entries = entries,
            lunches = lunchesToday,
            busy = EveningLoad.busyEvents(p.occurrences.filter { it.overlapsDate(date) }, date),
            holidays = SwedishHolidays.forDate(date),
            redDay = SwedishHolidays.isRedDay(date),
            isToday = date == now,
        )
    }

    private fun editorFor(date: LocalDate, slot: MealSlot, p: Plans, h: Household): DayEditorState {
        val plan = p.weeks.firstOrNull { it.week == date.isoWeek } ?: MealPlanWeek(date.isoWeek)
        val day = dayPlan(date, plan, p, h, today)
        val history = p.weeks.flatMap { it.entries.values }
        val suggestions = if (slot == MealSlot.Dinner) {
            MealSuggester.suggest(
                date = date,
                recipes = p.recipes,
                history = history,
                schoolLunchDishes = day.lunches.mapNotNull { it.dishes.firstOrNull() },
                busyEvening = day.busy.isNotEmpty(),
                pantryNames = if (h.settings.usePantry) h.pantry.map { it.name }.toSet() else emptySet(),
            )
        } else {
            emptyList()
        }
        return DayEditorState(
            date = date,
            slot = slot,
            entry = plan.entry(date, slot),
            suggestions = suggestions,
            recipes = p.recipes,
            lunches = day.lunches,
            busy = day.busy,
            weekDays = date.isoWeek.days,
        )
    }

    private fun showWeek(target: IsoWeek) {
        if (target != week.value) weekNutritionState.value = WeekNutritionState.Idle
        week.value = target
    }

    fun previousWeek() = showWeek(week.value.previous())

    fun nextWeek() = showWeek(week.value.next())

    fun thisWeek() = showWeek(today.isoWeek)

    fun showWeekOf(date: LocalDate) = showWeek(date.isoWeek)

    /** Average per portion over the week's planned recipes (neutral overview, no targets). */
    fun calculateWeekNutrition() {
        val s = state.value
        val byId = s.recipes.associateBy { it.id }
        val meals = s.days.flatMap { it.entries.values }.filter { it.leftoversFrom == null }.mapNotNull { e ->
            e.recipeId?.let(byId::get)?.let { it to (e.servings ?: it.servings) }
        }
        if (meals.isEmpty()) {
            weekNutritionState.value = WeekNutritionState.NoRecipes
            return
        }
        weekNutritionState.value = WeekNutritionState.Loading
        viewModelScope.launch {
            val summaries = meals.map { (recipe, portions) -> nutrition.summary(recipe, portions) }
            weekNutritionState.value = if (summaries.all { it == null }) {
                WeekNutritionState.Unavailable
            } else {
                WeekNutritionState.Ready(WeekNutritionCalculator.average(summaries))
            }
        }
    }

    fun openDay(date: LocalDate, slot: MealSlot = MealSlot.Dinner) {
        editing.value = date to slot
    }

    fun closeDay() {
        editing.value = null
    }

    /** Plans a recipe or free text ("tacos"). "Gör dubbelt" adds leftovers the next free day (see MealRepository). */
    fun plan(date: LocalDate, slot: MealSlot, title: String, recipeId: String?, cookDouble: Boolean, servings: Int?, note: String) {
        if (title.isBlank() && recipeId == null) return
        viewModelScope.launch {
            meals.setEntry(
                MealEntry(
                    date = date,
                    slot = slot,
                    recipeId = recipeId,
                    title = title.trim(),
                    servings = servings,
                    cookDouble = cookDouble,
                    note = note.trim(),
                ),
            )
        }
    }

    fun clear(date: LocalDate, slot: MealSlot) {
        viewModelScope.launch { meals.clearEntry(date, slot) }
    }

    /** "Flytta" (the accessible alternative to drag and drop): swaps with the target day if it is planned. */
    fun move(from: LocalDate, to: LocalDate, slot: MealSlot = MealSlot.Dinner) {
        if (from == to) return
        viewModelScope.launch { meals.move(from, to, slot) }
    }

    fun copyPreviousWeek() {
        val target = week.value
        viewModelScope.launch { meals.copyWeek(target.previous(), target) }
    }

    fun saveFavorite(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val current = meals.getWeek(week.value)
            meals.saveFavorite(MealPlanner.favoriteFrom(current, ids.newId(), name.trim()))
        }
    }

    fun applyFavorite(favorite: FavoriteWeek) {
        val target = week.value
        viewModelScope.launch { meals.applyFavorite(favorite, target) }
    }

    fun deleteFavorite(favorite: FavoriteWeek) {
        viewModelScope.launch { meals.deleteFavorite(favorite.id) }
    }

    /** Typed-in school lunch (when the school has no feed). Blank removes it. */
    fun setLunch(childId: String, date: LocalDate, dishes: String) {
        viewModelScope.launch { lunches.setManual(childId, date, dishes) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
