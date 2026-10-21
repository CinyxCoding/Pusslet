package se.pusslet.feature.meals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.holidays.Holiday
import se.pusslet.core.holidays.SwedishHolidays
import se.pusslet.core.meals.EveningLoad
import se.pusslet.core.meals.FoodGroup
import se.pusslet.core.meals.MealClash
import se.pusslet.core.meals.MealPlanner
import se.pusslet.core.meals.MealSuggester
import se.pusslet.core.meals.MealSuggestion
import se.pusslet.core.meals.WeekBalance
import se.pusslet.core.meals.WeekBalancer
import se.pusslet.core.meals.WeekFiller
import se.pusslet.core.model.Child
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilySettings
import se.pusslet.core.model.FavoriteWeek
import se.pusslet.core.model.LunchProviderType
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealKind
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

/** Children who eat the same school lunch share one row: "Lunch: Köttbullar (Ella, Simon)". */
data class LunchGroup(val children: List<Child>, val dishes: List<String>)

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
    /** Ids of the children without school this day (weekend, holiday, "lov" or "studiedag"): no school-lunch prompt. */
    val noSchool: Set<String> = emptySet(),
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
    val today: LocalDate? = null,
    /** Everyone in the family by id, for "Johan lagar". */
    val people: Map<String, FamilyPerson> = emptyMap(),
    /** The viewer's member id: "Du lagar". */
    val me: String? = null,
    /** Fish, vegetarian and meat this week, school lunch and dinner together ([WeekBalancer]); null while loading. */
    val balance: WeekBalance? = null,
) {
    val plannedCount: Int get() = days.count { it.dinner != null }

    /** The meal the round + plans: dinner, unless the family only plans other meals. */
    val mainSlot: MealSlot get() = if (MealSlot.Dinner in slots) MealSlot.Dinner else slots.firstOrNull() ?: MealSlot.Dinner

    /** Nothing at all is planned this week. */
    val weekIsEmpty: Boolean get() = days.all { it.entries.isEmpty() }

    /** Children whose school lunch is typed in (their school has no feed); only parents fill it in. */
    val manualLunchChildren: List<Child>
        get() = if (canEdit) children.filter { it.lunch?.provider == LunchProviderType.Manual } else emptyList()

    /** The one "Fyll i skollunch ›" row of the week: someone types in lunches and has school at least one day. */
    val showFillLunch: Boolean
        get() = manualLunchChildren.let { kids -> kids.isNotEmpty() && days.any { d -> kids.any { it.id !in d.noSchool } } }

    /**
     * The empty week's one action, "Planera veckan" (UX plan 4.5): the same planning sheet as the round +. Helpers, who
     * cannot plan, only get the sentence.
     */
    val offerPlanWeek: Boolean get() = canEdit && weekIsEmpty

    /** "Kopiera förra veckan" belongs to an empty week, and only when last week has something to copy. */
    val offerCopyPrevious: Boolean get() = offerPlanWeek && previousWeekPlanned

    /** "Föreslå veckans middagar": parents, with recipes to suggest from and a day from today still without a dinner. */
    val offerSuggestWeek: Boolean
        get() = canEdit && recipes.isNotEmpty() && days.any { d -> d.dinner == null && (today == null || d.date >= today) }
}

/** "Föreslå veckans middagar": one proposal per day still to plan, or why there is none ([empty]). */
data class WeekProposalState(val days: List<ProposedDay>, val empty: ProposalEmpty? = null) {
    val chosen: List<ProposedDay> get() = days.filter { it.included }
}

enum class ProposalEmpty { NoRecipes, AllPlanned }

/** A proposed dinner: [index] is the option shown ("Byt" steps to the next), [included] whether "Lägg in" plans it. */
data class ProposedDay(val date: LocalDate, val options: List<MealSuggestion>, val index: Int = 0, val included: Boolean = true) {
    val suggestion: MealSuggestion get() = options[index]
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
    val today: LocalDate? = null,
    /** The family's adults, for "Vem lagar" (cooked dinners only). */
    val adults: List<FamilyPerson> = emptyList(),
)

/**
 * The read view of a planned meal (UX plan, Mat 2): the dish, "Visa recept" when it has one, the day's school lunch and,
 * for parents, "Ändra", which opens the planning sheet.
 */
data class DayViewState(
    val date: LocalDate,
    val slot: MealSlot,
    val entry: MealEntry,
    val recipe: Recipe?,
    val lunches: List<ChildLunch>,
    val canEdit: Boolean,
    /** Who cooks ("Johan lagar"), when someone is chosen. */
    val cook: FamilyPerson? = null,
    /** The viewer cooks: "Du lagar". */
    val cookIsMe: Boolean = false,
) {
    val title: String get() = recipe?.title ?: entry.title
}

/** Receipts for changes to the meal plan; each one can be undone with [MealsViewModel.undo]. */
sealed interface MealsMessage {
    val undo: PlanUndo

    /** "Tis: Ugnspannkaka · Ångra": [title] planned (or moved) to [date]. */
    data class Planned(val date: LocalDate, val title: String, override val undo: PlanUndo) : MealsMessage

    /** "Ugnspannkaka borttagen · Ångra". */
    data class Removed(val title: String, override val undo: PlanUndo) : MealsMessage

    /** "Höstvecka inlagd · Ångra". */
    data class FavoriteApplied(val name: String, override val undo: PlanUndo) : MealsMessage

    /** "Förra veckan kopierad · Ångra". */
    data class CopiedPreviousWeek(override val undo: PlanUndo) : MealsMessage

    /** "Veckans förslag inlagda · Ångra". */
    data class WeekSuggested(override val undo: PlanUndo) : MealsMessage
}

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
    private val viewing = MutableStateFlow<Pair<LocalDate, MealSlot>?>(null)
    private val weekNutritionState = MutableStateFlow<WeekNutritionState>(WeekNutritionState.Idle)
    private val proposalState = MutableStateFlow<WeekProposalState?>(null)
    private val history = MealPlanHistory(meals)
    private val messageChannel = Channel<MealsMessage>(Channel.BUFFERED)

    /** Calculated on request (the first time downloads Livsmedelsverket's food list); reset when the week changes. */
    val weekNutrition: StateFlow<WeekNutritionState> = weekNutritionState

    /** "Föreslå veckans middagar" while its sheet is open. */
    val weekProposal: StateFlow<WeekProposalState?> = proposalState

    /** One receipt per change to the plan, with what Ångra needs ([undo]). */
    val messages: Flow<MealsMessage> = messageChannel.receiveAsFlow()

    private data class Household(
        val children: List<Child>,
        val people: List<FamilyPerson>,
        val settings: FamilySettings,
        val session: Session,
        val lunches: Map<String, List<SchoolLunch>>,
        val pantry: List<PantryItem>,
    )

    private val household: Flow<Household> = combine(
        combine(family.children, family.people) { c, p -> c to p },
        family.settings,
        sessions.session,
        lunches.lunches,
        shopping.pantryAtHome,
    ) { (c, people), s, se, l, p -> Household(c, people, s, se, l, p) }

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

    /** The read view of a planned meal; null when closed, or when the meal is no longer planned. */
    val dayView: StateFlow<DayViewState?> = combine(viewing, plans, household) { target, p, h ->
        target?.let { (date, slot) -> viewFor(date, slot, p, h) }
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
            today = now,
            people = h.people.associateBy { it.id },
            me = h.session.memberId,
            balance = WeekBalancer.balance(p.week, dinnerGroups(p.week, plan, p), lunchGroups(p.week, h)),
        )
    }

    /** The week's eaten dinners by group, without the one on [without] (the day being planned again). */
    private fun dinnerGroups(week: IsoWeek, plan: MealPlanWeek, p: Plans, without: LocalDate? = null): Map<LocalDate, FoodGroup?> {
        val recipes = p.recipes.associateBy { it.id }
        return week.days.filter { it != without }.mapNotNull { d ->
            plan.entry(d, MealSlot.Dinner)?.takeIf(WeekBalancer::eaten)?.let { d to WeekBalancer.dinnerGroup(it, recipes) }
        }.toMap()
    }

    /** Each child's school lunches of the week by group. */
    private fun lunchGroups(week: IsoWeek, h: Household): Map<String, Map<LocalDate, FoodGroup?>> = h.children.associate { c ->
        c.id to h.lunches[c.id].orEmpty()
            .filter { it.date in week.days && it.dishes.isNotEmpty() }
            .associate { it.date to WeekBalancer.lunchGroup(it.dishes) }
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
        val dayOccurrences = p.occurrences.filter { it.overlapsDate(date) }
        return DayPlan(
            date = date,
            entries = entries,
            lunches = lunchesToday,
            busy = EveningLoad.busyEvents(dayOccurrences, date),
            holidays = SwedishHolidays.forDate(date),
            redDay = SwedishHolidays.isRedDay(date),
            isToday = date == now,
            noSchool = childrenWithoutSchool(date, h.children, dayOccurrences),
        )
    }

    private fun editorFor(date: LocalDate, slot: MealSlot, p: Plans, h: Household): DayEditorState {
        val plan = p.weeks.firstOrNull { it.week == date.isoWeek } ?: MealPlanWeek(date.isoWeek)
        val now = today
        val day = dayPlan(date, plan, p, h, now)
        val history = p.weeks.flatMap { it.entries.values }
        val suggestions = if (slot == MealSlot.Dinner) {
            MealSuggester.suggest(
                date = date,
                recipes = p.recipes,
                history = history,
                schoolLunchDishes = day.lunches.mapNotNull { it.dishes.firstOrNull() },
                busyEvening = day.busy.isNotEmpty(),
                pantryNames = pantryNames(h),
                week = WeekBalancer.balance(
                    date.isoWeek,
                    dinnerGroups(date.isoWeek, plan, p, without = date),
                    lunchGroups(date.isoWeek, h),
                ).needs,
                limit = MAX_SUGGESTIONS,
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
            today = now,
            adults = h.people.filter { !it.isChild },
        )
    }

    private fun pantryNames(h: Household): Set<String> = if (h.settings.usePantry) h.pantry.map { it.name }.toSet() else emptySet()

    /**
     * "Föreslå veckans middagar": the days of the shown week from today without a dinner, filled one by one so the week leans
     * towards Livsmedelsverket's advice around the school lunches ([WeekFiller]).
     */
    fun proposeWeek() {
        viewModelScope.launch {
            val (p, h) = combine(plans, household) { p, h -> p to h }.first()
            proposalState.value = proposalFor(p, h)
        }
    }

    private fun proposalFor(p: Plans, h: Household): WeekProposalState {
        if (p.recipes.isEmpty()) return WeekProposalState(emptyList(), ProposalEmpty.NoRecipes)
        val plan = p.weeks.firstOrNull { it.week == p.week } ?: MealPlanWeek(p.week)
        val now = today
        val days = p.week.days.filter { it >= now && plan.entry(it, MealSlot.Dinner) == null }
        if (days.isEmpty()) return WeekProposalState(emptyList(), ProposalEmpty.AllPlanned)
        val dayPlans = days.associateWith { dayPlan(it, plan, p, h, now) }
        val proposals = WeekFiller.fill(
            days = days,
            recipes = p.recipes,
            history = p.weeks.flatMap { it.entries.values },
            planned = dinnerGroups(p.week, plan, p),
            lunchGroups = lunchGroups(p.week, h),
            lunchDishes = dayPlans.mapValues { (_, d) -> d.lunches.mapNotNull { it.dishes.firstOrNull() } },
            busy = dayPlans.filterValues { it.busy.isNotEmpty() }.keys,
            pantryNames = pantryNames(h),
        )
        return WeekProposalState(proposals.map { ProposedDay(it.date, it.options) })
    }

    /** "Byt": the next option for [date]. */
    fun swapProposal(date: LocalDate) = updateProposal(date) { it.copy(index = (it.index + 1) % it.options.size) }

    /** Leaves [date] out of "Lägg in", or takes it back. */
    fun toggleProposal(date: LocalDate) = updateProposal(date) { it.copy(included = !it.included) }

    private fun updateProposal(date: LocalDate, change: (ProposedDay) -> ProposedDay) {
        proposalState.update { s -> s?.copy(days = s.days.map { if (it.date == date) change(it) else it }) }
    }

    fun dismissProposal() {
        proposalState.value = null
    }

    /** "Lägg in": plans the chosen proposals in one change ("Veckans förslag inlagda · Ångra"). */
    fun applyProposal() {
        val chosen = proposalState.value?.chosen.orEmpty()
        proposalState.value = null
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val undo = history.record(chosen.map { it.date }) {
                chosen.forEach { d ->
                    val recipe = d.suggestion.recipe
                    meals.setEntry(MealEntry(date = d.date, slot = MealSlot.Dinner, recipeId = recipe.id, title = recipe.title))
                }
            } ?: return@launch
            changed(MealsMessage.WeekSuggested(undo))
        }
    }

    private fun viewFor(date: LocalDate, slot: MealSlot, p: Plans, h: Household): DayViewState? {
        val plan = p.weeks.firstOrNull { it.week == date.isoWeek } ?: return null
        val entry = plan.entry(date, slot) ?: return null
        val day = dayPlan(date, plan, p, h, today)
        val cook = entry.cookId?.takeIf { entry.kind.cooks }?.let { id -> h.people.firstOrNull { it.id == id } }
        return DayViewState(
            date = date,
            slot = slot,
            entry = entry,
            recipe = entry.recipeId?.takeIf { entry.kind.cooks }?.let { id -> p.recipes.firstOrNull { it.id == id } },
            lunches = day.lunches,
            canEdit = h.session.role == Role.Parent,
            cook = cook,
            cookIsMe = cook != null && cook.id == h.session.memberId,
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
        val meals = s.days.flatMap { it.entries.values }.filter { it.cooksRecipe }.mapNotNull { e ->
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

    /** Opens the planning sheet for [date]. A planned meal is normally shown in the read view first ([viewDay]). */
    fun openDay(date: LocalDate, slot: MealSlot = MealSlot.Dinner) {
        viewing.value = null
        editing.value = date to slot
    }

    /** The round + on Matsedel: opens the planning sheet for the first day still to plan (see [nextDayToPlan]). */
    fun openNextToPlan() {
        val s = state.value
        nextDayToPlan(s.days, s.mainSlot)?.let { openDay(it, s.mainSlot) }
    }

    fun closeDay() {
        editing.value = null
    }

    /** A tap on a planned meal: the read view with the dish, its recipe and the day's lunch. */
    fun viewDay(date: LocalDate, slot: MealSlot = MealSlot.Dinner) {
        editing.value = null
        viewing.value = date to slot
    }

    fun closeView() {
        viewing.value = null
    }

    /** "Ändra" in the read view: the planning sheet for the same meal. */
    fun editViewed() {
        val target = viewing.value ?: return
        viewing.value = null
        editing.value = target
    }

    /**
     * "Spara" in the planning sheet: plans [title] (a recipe when [recipeId] is set) for the day being planned and closes the
     * sheet. Portions chosen earlier are kept while the recipe stays the same. Takeaway and no cooking ([kind]) may be saved
     * without a text: then [kindLabel] ("Hämtmat") becomes the title, so every place that shows the meal still reads well.
     * [cookId] is "Vem lagar" (cooked meals only).
     */
    fun saveDay(
        title: String,
        recipeId: String?,
        cookDouble: Boolean,
        note: String,
        kind: MealKind = MealKind.Cooked,
        cookId: String? = null,
        kindLabel: String = "",
    ) {
        val (date, slot) = editing.value ?: return
        val text = title.trim().ifEmpty { if (kind.cooks) "" else kindLabel.trim() }
        if (text.isEmpty() && (recipeId == null || !kind.cooks)) return
        val current = dayEditor.value?.entry?.takeIf { it.date == date && it.slot == slot }
        val servings = current?.servings?.takeIf { current.recipeId == recipeId }
        editing.value = null
        plan(date, slot, text, recipeId, cookDouble, servings, note, kind, cookId)
    }

    /** One tap on a suggestion plans it and closes the sheet ("Tis: Ugnspannkaka · Ångra"); [cookId] is "Vem lagar". */
    fun pickSuggestion(recipe: Recipe, cookDouble: Boolean = false, note: String = "", cookId: String? = null) =
        saveDay(recipe.title, recipe.id, cookDouble, note, MealKind.Cooked, cookId)

    /** "Ta bort" in the planning sheet. */
    fun clearDay() {
        val (date, slot) = editing.value ?: return
        editing.value = null
        clear(date, slot)
    }

    /** "Flytta till…" in the planning sheet. */
    fun moveDay(to: LocalDate) {
        val (date, slot) = editing.value ?: return
        editing.value = null
        move(date, to, slot)
    }

    /**
     * Plans a recipe or free text ("tacos"), or takeaway / no cooking ([kind]), which keep no recipe, portions, "Gör dubbelt"
     * or cook. "Gör dubbelt" adds leftovers the next free day (see MealRepository).
     */
    fun plan(
        date: LocalDate,
        slot: MealSlot,
        title: String,
        recipeId: String?,
        cookDouble: Boolean,
        servings: Int?,
        note: String,
        kind: MealKind = MealKind.Cooked,
        cookId: String? = null,
    ) {
        val entry = MealEntry(
            date = date,
            slot = slot,
            recipeId = recipeId,
            title = title.trim(),
            servings = servings,
            cookDouble = cookDouble,
            note = note.trim(),
            kind = kind,
            cookId = cookId?.takeIf { it.isNotBlank() },
        ).normalized()
        if (entry.title.isBlank() && entry.recipeId == null) return
        viewModelScope.launch {
            val undo = history.record(listOf(date)) { meals.setEntry(entry) } ?: return@launch
            changed(MealsMessage.Planned(date, titleOf(entry), undo))
        }
    }

    fun clear(date: LocalDate, slot: MealSlot) {
        viewModelScope.launch {
            val removed = meals.getWeek(date.isoWeek).entry(date, slot) ?: return@launch
            val undo = history.record(listOf(date)) { meals.clearEntry(date, slot) } ?: return@launch
            changed(MealsMessage.Removed(titleOf(removed), undo))
        }
    }

    /** "Flytta" (the accessible alternative to drag and drop): swaps with the target day if it is planned. */
    fun move(from: LocalDate, to: LocalDate, slot: MealSlot = MealSlot.Dinner) {
        if (from == to) return
        viewModelScope.launch {
            val moved = meals.getWeek(from.isoWeek).entry(from, slot) ?: return@launch
            val undo = history.record(listOf(from, to)) { meals.move(from, to, slot) } ?: return@launch
            changed(MealsMessage.Planned(to, titleOf(moved), undo))
        }
    }

    /**
     * "Kopiera förra veckan": last week's meals on the same weekdays of the shown week. Days already planned keep their meal
     * (see [MealRepository.copyWeek]); the receipt's "Ångra" empties the copied days again.
     */
    fun copyPreviousWeek() {
        val target = week.value
        viewModelScope.launch {
            val undo = history.record(target.days) { meals.copyWeek(target.previous(), target) } ?: return@launch
            changed(MealsMessage.CopiedPreviousWeek(undo))
        }
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
        viewModelScope.launch {
            val undo = history.record(target.days) { meals.applyFavorite(favorite, target) } ?: return@launch
            changed(MealsMessage.FavoriteApplied(favorite.name, undo))
        }
    }

    fun deleteFavorite(favorite: FavoriteWeek) {
        viewModelScope.launch { meals.deleteFavorite(favorite.id) }
    }

    /** "Ångra" on a receipt: puts back the meals [undo] replaced, unless someone has changed them again since. */
    fun undo(undo: PlanUndo) {
        viewModelScope.launch {
            history.undo(undo)
            weekNutritionState.value = WeekNutritionState.Idle
        }
    }

    /** Typed-in school lunch (when the school has no feed). Blank removes it. */
    fun setLunch(childId: String, date: LocalDate, dishes: String) {
        viewModelScope.launch { lunches.setManual(childId, date, dishes) }
    }

    /**
     * "Fyll i skollunch": the same typed-in lunches for [childIds], one text per day (dishes one per line, blank removes).
     * Written one after the other, since every day of a child's week is one document; days that did not change are skipped.
     */
    fun setWeekLunches(childIds: List<String>, dishes: Map<LocalDate, String>) {
        val current = state.value.days.associate { day -> day.date to day.lunches.associate { it.child.id to it.dishes } }
        viewModelScope.launch {
            for ((date, text) in dishes) {
                val typed = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                for (childId in childIds) {
                    if (current[date]?.get(childId).orEmpty() != typed) lunches.setManual(childId, date, typed.joinToString("\n"))
                }
            }
        }
    }

    /** The receipt for a change; the week's nutrition is then out of date and is calculated again when opened. */
    private suspend fun changed(message: MealsMessage) {
        weekNutritionState.value = WeekNutritionState.Idle
        messageChannel.send(message)
    }

    private fun titleOf(entry: MealEntry): String =
        entry.title.ifBlank { entry.recipeId?.let { id -> state.value.recipes.firstOrNull { it.id == id }?.title }.orEmpty() }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** Three show at first; "Visa fler" shows the rest. */
        const val MAX_SUGGESTIONS = 8
    }
}

/**
 * The day the round + plans: the first day from today without [slot] planned (from Monday in another week). When
 * everything is planned it is today (or Monday), so the sheet still opens on a sensible day.
 */
internal fun nextDayToPlan(days: List<DayPlan>, slot: MealSlot = MealSlot.Dinner): LocalDate? {
    val todayIndex = days.indexOfFirst { it.isToday }
    val from = if (todayIndex >= 0) days.drop(todayIndex) else days
    return (from.firstOrNull { slot !in it.entries } ?: from.firstOrNull())?.date
}

/**
 * Children without school on [date]: everyone on weekends and holidays, and the children a "lov" or "studiedag" event
 * is for (an event without participants counts for every child). [occurrences] are the day's events.
 */
internal fun childrenWithoutSchool(date: LocalDate, children: List<Child>, occurrences: List<EventOccurrence>): Set<String> {
    if (SwedishHolidays.isNonWorkingDay(date)) return children.mapTo(LinkedHashSet()) { it.id }
    val off = occurrences.filter { o -> o.event.tags.any { tag -> NO_SCHOOL_TAGS.any { it.equals(tag, ignoreCase = true) } } }
    return children.filter { c -> off.any { o -> o.event.participantIds.isEmpty() || c.id in o.event.participantIds } }
        .mapTo(LinkedHashSet()) { it.id }
}

private val NO_SCHOOL_TAGS = listOf(EventTags.HOLIDAY_BREAK, EventTags.STUDY_DAY)

/**
 * One row per school lunch: children with the same dish share it. The day card compares the first dish (the one it
 * shows); [wholeMenu] compares every dish, for the planning sheet that lists them all.
 */
internal fun groupLunches(lunches: List<ChildLunch>, wholeMenu: Boolean = false): List<LunchGroup> =
    lunches.filter { it.dishes.isNotEmpty() }
        .groupBy { lunch ->
            val dishes = if (wholeMenu) lunch.dishes else lunch.dishes.take(1)
            dishes.joinToString("\n") { it.trim().lowercase() }
        }
        .values
        .map { same -> LunchGroup(same.map { it.child }, same.first().dishes) }
