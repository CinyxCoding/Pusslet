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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import se.pusslet.core.meals.BusyEvenings
import se.pusslet.core.meals.MealClash
import se.pusslet.core.meals.MealPlanner
import se.pusslet.core.meals.MealSuggester
import se.pusslet.core.meals.MealSuggestion
import se.pusslet.core.model.Allergen
import se.pusslet.core.model.Diet
import se.pusslet.core.model.DietProfile
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilySettings
import se.pusslet.core.model.FavoriteWeek
import se.pusslet.core.model.ListKind
import se.pusslet.core.model.LunchProviderType
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealPlanWeek
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.PantryItem
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.Role
import se.pusslet.core.model.SchoolLunch
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.nutrition.NutrientValues
import se.pusslet.core.recipes.DietConflict
import se.pusslet.core.recipes.RecipeDietCheck
import se.pusslet.core.shopping.ShoppingPlan
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.LunchRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.MealShopping
import se.pusslet.data.usecase.RecipeNutrition
import kotlin.time.Clock

/** A child's school lunch on one day. */
data class ChildLunch(val child: FamilyPerson, val dishes: List<String>, val manual: Boolean)

/** "Ella har redan ätit köttbullar till lunch". */
data class LunchClash(val child: FamilyPerson, val dish: String)

/** "Passar inte alla: Fisk (Ella)". Exactly one of [allergen]/[diet] is set. */
data class ConflictNote(val person: FamilyPerson, val allergen: Allergen? = null, val diet: Diet? = null)

data class PlannedMeal(
    val slot: MealSlot,
    val entry: MealEntry?,
    val recipe: Recipe?,
    val clashes: List<LunchClash> = emptyList(),
    val conflicts: List<ConflictNote> = emptyList(),
)

data class MealDay(
    val date: LocalDate,
    val meals: List<PlannedMeal>,
    val lunches: List<ChildLunch>,
    /** Activities in the dinner window ("Fotboll 17:30"). */
    val busy: List<EventOccurrence>,
)

data class DaySheet(
    val date: LocalDate,
    val slot: MealSlot,
    val meal: PlannedMeal,
    val lunches: List<ChildLunch>,
    val busy: List<EventOccurrence>,
    val suggestions: List<MealSuggestion>,
    val recipes: List<Recipe>,
    val conflicts: Map<String, List<ConflictNote>>,
    val defaultServings: Int,
)

data class ShoppingPreview(
    val week: IsoWeek,
    val list: ShoppingList,
    val plan: ShoppingPlan,
    val hasRecipes: Boolean,
    val pantryUsed: Boolean,
)

/** Average nutrition of the week's planned dinners that have a recipe (null values = not computed). */
data class WeekNutrition(val perPortion: NutrientValues, val withRecipe: Int, val dinners: Int)

data class MealsUiState(
    val loading: Boolean = true,
    val week: IsoWeek? = null,
    val today: LocalDate? = null,
    val slots: List<MealSlot> = listOf(MealSlot.Dinner),
    val days: List<MealDay> = emptyList(),
    val favorites: List<FavoriteWeek> = emptyList(),
    val lists: List<ShoppingList> = emptyList(),
    val canEdit: Boolean = true,
    val sheet: DaySheet? = null,
    val shopping: ShoppingPreview? = null,
    val nutrition: WeekNutrition? = null,
    val nutritionLoading: Boolean = false,
)

sealed interface MealsMessage {
    data object Copied : MealsMessage

    data object FavoriteSaved : MealsMessage

    data class FavoriteApplied(val name: String) : MealsMessage

    data class Moved(val to: LocalDate) : MealsMessage

    data class ShoppingDone(val listName: String) : MealsMessage

    data object NutritionOffline : MealsMessage

    data object NoShoppingList : MealsMessage
}

@OptIn(ExperimentalCoroutinesApi::class)
class MealsViewModel(
    private val meals: MealRepository,
    private val recipes: RecipeRepository,
    private val lunches: LunchRepository,
    private val family: FamilyRepository,
    private val calendar: CalendarRepository,
    private val shopping: ShoppingRepository,
    private val mealShopping: MealShopping,
    private val nutrition: RecipeNutrition,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val today = clock.todayIn(zone)
    private val week = MutableStateFlow(today.isoWeek)
    private val selected = MutableStateFlow<Pair<LocalDate, MealSlot>?>(null)
    private val shoppingPreview = MutableStateFlow<ShoppingPreview?>(null)
    private val weekNutrition = MutableStateFlow<Pair<IsoWeek, WeekNutrition>?>(null)
    private val nutritionLoading = MutableStateFlow(false)
    private val messageChannel = Channel<MealsMessage>(Channel.BUFFERED)
    val messages: Flow<MealsMessage> = messageChannel.receiveAsFlow()

    private data class Household(
        val people: List<FamilyPerson>,
        val diets: List<DietProfile>,
        val settings: FamilySettings,
        val lunches: Map<String, List<SchoolLunch>>,
        val manualChildren: Set<String>,
    )

    private val household: Flow<Household> = combine(
        family.people,
        family.diets,
        family.settings,
        lunches.lunches,
        family.children,
    ) { p, d, s, l, c ->
        // Children without a feed can have their lunch typed in; feed lunches are read-only here.
        Household(p, d, s, l, c.filter { it.lunch == null || it.lunch?.provider == LunchProviderType.Manual }.map { it.id }.toSet())
    }

    private data class Plans(
        val weeks: List<MealPlanWeek>,
        val recipes: List<Recipe>,
        val favorites: List<FavoriteWeek>,
        val lists: List<ShoppingList>,
        val pantry: List<PantryItem>,
    )

    private val plans: Flow<Plans> =
        combine(meals.weeks, recipes.recipes, meals.favorites, shopping.lists, shopping.pantry) { w, r, f, l, p ->
            Plans(w, r, f, l, p)
        }

    private val evenings: Flow<Map<LocalDate, List<EventOccurrence>>> = week.flatMapLatest { w ->
        calendar.occurrences(w.monday, w.sunday.plusDays(1), zone)
    }.map { BusyEvenings.of(it) }

    private data class Ui(
        val selected: Pair<LocalDate, MealSlot>?,
        val shopping: ShoppingPreview?,
        val nutrition: Pair<IsoWeek, WeekNutrition>?,
        val nutritionLoading: Boolean,
    )

    private val ui: Flow<Ui> = combine(selected, shoppingPreview, weekNutrition, nutritionLoading) { s, sh, n, l -> Ui(s, sh, n, l) }

    val state: StateFlow<MealsUiState> = combine(week, household, plans, evenings, ui) { w, h, p, busy, u ->
        build(w, h, p, busy, u)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MealsUiState())

    private fun build(w: IsoWeek, h: Household, p: Plans, busy: Map<LocalDate, List<EventOccurrence>>, u: Ui): MealsUiState {
        val people = h.people.associateBy { it.id }
        val children = h.people.filter { it.isChild }
        val recipeById = p.recipes.associateBy { it.id }
        val plan = p.weeks.firstOrNull { it.week == w } ?: MealPlanWeek(w)
        val slots = MealSlot.entries.filter { it in h.settings.mealSlots }.ifEmpty { listOf(MealSlot.Dinner) }

        fun lunchesOn(date: LocalDate): List<ChildLunch> = children.mapNotNull { c ->
            val l = h.lunches[c.id].orEmpty().firstOrNull { it.date == date }
            when {
                l != null -> ChildLunch(c, l.dishes, c.id in h.manualChildren)
                c.id in h.manualChildren -> ChildLunch(c, emptyList(), manual = true)
                else -> null
            }
        }

        fun conflictsOf(recipe: Recipe?): List<ConflictNote> = recipe?.let { r ->
            RecipeDietCheck.conflicts(r, h.diets).mapNotNull { c ->
                val person = people[c.personId] ?: return@mapNotNull null
                when (c) {
                    is DietConflict.ContainsAllergen -> ConflictNote(person, allergen = c.allergen)
                    is DietConflict.BreaksDiet -> ConflictNote(person, diet = c.diet)
                }
            }
        }.orEmpty()

        fun planned(date: LocalDate, slot: MealSlot, dayLunches: List<ChildLunch>): PlannedMeal {
            val entry = plan.entry(date, slot)
            val recipe = entry?.recipeId?.let(recipeById::get)
            val clashes = if (entry == null || slot != MealSlot.Dinner) {
                emptyList()
            } else {
                dayLunches.mapNotNull { l ->
                    if (MealClash.between(l.dishes, entry.title).isEmpty()) null else LunchClash(l.child, l.dishes.first())
                }
            }
            return PlannedMeal(slot, entry, recipe, clashes, conflictsOf(recipe))
        }

        val days = w.days.map { date ->
            val dayLunches = lunchesOn(date)
            MealDay(date, slots.map { planned(date, it, dayLunches) }, dayLunches, busy[date].orEmpty())
        }

        val sheet = u.selected?.let { (date, slot) ->
            val dayLunches = lunchesOn(date)
            val history = p.weeks.flatMap { it.entries.values }
            val suggestions = if (slot == MealSlot.Dinner || slot == MealSlot.Lunch) {
                MealSuggester.suggest(
                    date = date,
                    recipes = p.recipes,
                    history = history,
                    schoolLunchDishes = dayLunches.mapNotNull { it.dishes.firstOrNull() },
                    busyEvening = busy[date].orEmpty().isNotEmpty(),
                    pantryNames = p.pantry.map { it.name }.toSet(),
                    dietProfiles = h.diets,
                )
            } else {
                emptyList()
            }
            DaySheet(
                date = date,
                slot = slot,
                meal = planned(date, slot, dayLunches),
                lunches = dayLunches,
                busy = busy[date].orEmpty(),
                suggestions = suggestions,
                recipes = p.recipes,
                conflicts = p.recipes.associate { it.id to conflictsOf(it) }.filterValues { it.isNotEmpty() },
                defaultServings = h.people.count { it.role != Role.Caregiver }.coerceAtLeast(1),
            )
        }

        return MealsUiState(
            loading = false,
            week = w,
            today = today,
            slots = slots,
            days = days,
            favorites = p.favorites.sortedBy { it.name.lowercase() },
            lists = p.lists,
            canEdit = sessions.session.value.role != Role.Caregiver,
            sheet = sheet,
            shopping = u.shopping,
            nutrition = u.nutrition?.takeIf { it.first == w }?.second,
            nutritionLoading = u.nutritionLoading,
        )
    }

    fun previousWeek() = week.update { it.previous() }

    fun nextWeek() = week.update { it.next() }

    fun thisWeek() {
        week.value = today.isoWeek
    }

    /** Opens the day sheet; used when Today's "Planera middag" is tapped too. */
    fun openDay(date: LocalDate, slot: MealSlot = MealSlot.Dinner) {
        week.value = date.isoWeek
        selected.value = date to slot
    }

    fun closeDay() {
        selected.value = null
    }

    fun planRecipe(date: LocalDate, slot: MealSlot, recipe: Recipe) = launch {
        val current = meals.getWeek(date.isoWeek).entry(date, slot)
        meals.setEntry(
            MealEntry(date, slot, recipe.id, recipe.title, servings = current?.servings, cookDouble = current?.cookDouble ?: false),
        )
    }

    fun planText(date: LocalDate, slot: MealSlot, text: String) = launch {
        val title = text.trim()
        if (title.isEmpty()) return@launch
        val recipe = recipes.all().firstOrNull { it.title.equals(title, ignoreCase = true) }
        val current = meals.getWeek(date.isoWeek).entry(date, slot)
        meals.setEntry(
            MealEntry(
                date,
                slot,
                recipe?.id,
                recipe?.title ?: title.replaceFirstChar { it.uppercase() },
                current?.servings,
                current?.cookDouble ?: false,
            ),
        )
    }

    fun setServings(date: LocalDate, slot: MealSlot, servings: Int) = update(date, slot) {
        it.copy(servings = servings.coerceIn(1, MAX_SERVINGS))
    }

    fun setCookDouble(date: LocalDate, slot: MealSlot, on: Boolean) = launch {
        val entry = meals.getWeek(date.isoWeek).entry(date, slot) ?: return@launch
        if (!on) {
            // Remove the leftovers day this entry created, if nobody changed it.
            val next = date.plusDays(1)
            val leftovers = meals.getWeek(next.isoWeek).entry(next, MealSlot.Dinner)
            if (leftovers?.leftoversFrom == date) meals.clearEntry(next, MealSlot.Dinner)
        }
        meals.setEntry(entry.copy(cookDouble = on))
    }

    fun clear(date: LocalDate, slot: MealSlot) = launch { meals.clearEntry(date, slot) }

    fun move(from: LocalDate, to: LocalDate, slot: MealSlot) = launch {
        if (from == to) return@launch
        meals.move(from, to, slot)
        selected.value = null
        messageChannel.send(MealsMessage.Moved(to))
    }

    fun setLunch(childId: String, date: LocalDate, dishes: String) = launch { lunches.setManual(childId, date, dishes) }

    fun copyLastWeek() = launch {
        val target = week.value
        meals.copyWeek(target.previous(), target)
        messageChannel.send(MealsMessage.Copied)
    }

    fun saveFavorite(name: String) = launch {
        val plan = meals.getWeek(week.value)
        val fav = MealPlanner.favoriteFrom(plan, ids.newId(), name.trim().ifEmpty { plan.week.key })
        meals.saveFavorite(fav)
        messageChannel.send(MealsMessage.FavoriteSaved)
    }

    fun applyFavorite(favorite: FavoriteWeek) = launch {
        meals.applyFavorite(favorite, week.value)
        messageChannel.send(MealsMessage.FavoriteApplied(favorite.name))
    }

    fun deleteFavorite(id: String) = launch { meals.deleteFavorite(id) }

    /** "Handla till veckan": shows what would change before anything is written. */
    fun previewShopping(listId: String? = null) = launch {
        val lists = shopping.lists.first()
        val settings = family.settings.first()
        val list = lists.firstOrNull { it.id == listId }
            ?: lists.firstOrNull { it.storeId != null && it.storeId == settings.defaultStoreId }
            ?: lists.firstOrNull { it.kind == ListKind.Grocery }
            ?: lists.firstOrNull()
        if (list == null) {
            messageChannel.send(MealsMessage.NoShoppingList)
            return@launch
        }
        val w = week.value
        val plan = mealShopping.preview(list.id, w)
        val hasRecipes = meals.getWeek(w).entries.values.any { it.recipeId != null && it.leftoversFrom == null }
        shoppingPreview.value = ShoppingPreview(w, list, plan, hasRecipes, settings.usePantry)
    }

    fun confirmShopping() = launch {
        val preview = shoppingPreview.value ?: return@launch
        mealShopping.generate(preview.list.id, preview.week)
        shoppingPreview.value = null
        messageChannel.send(MealsMessage.ShoppingDone(preview.list.name))
    }

    fun dismissShopping() {
        shoppingPreview.value = null
    }

    /** Average per dinner portion for the shown week. Downloads the food database the first time. */
    fun loadNutrition() = launch {
        val w = week.value
        nutritionLoading.value = true
        try {
            val dinners = meals.getWeek(w).entries.values.filter { it.slot == MealSlot.Dinner && it.leftoversFrom == null }
            val withRecipe = dinners.mapNotNull { e -> e.recipeId?.let { recipes.get(it) }?.let { e to it } }
            var total = NutrientValues.ZERO
            var counted = 0
            for ((_, recipe) in withRecipe) {
                val summary = nutrition.summary(recipe)
                if (summary == null) {
                    messageChannel.send(MealsMessage.NutritionOffline)
                    return@launch
                }
                total += summary.perPortion
                counted++
            }
            val avg = if (counted == 0) NutrientValues() else total.scaled(1.0 / counted)
            weekNutrition.value = w to WeekNutrition(avg, withRecipe.size, dinners.size)
        } finally {
            nutritionLoading.value = false
        }
    }

    private fun update(date: LocalDate, slot: MealSlot, transform: (MealEntry) -> MealEntry) = launch {
        val entry = meals.getWeek(date.isoWeek).entry(date, slot) ?: return@launch
        meals.setEntry(transform(entry))
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MAX_SERVINGS = 30
    }
}
