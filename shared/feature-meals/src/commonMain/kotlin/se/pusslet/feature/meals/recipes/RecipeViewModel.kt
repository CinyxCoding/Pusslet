package se.pusslet.feature.meals.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import se.pusslet.core.meals.RecipeScaler
import se.pusslet.core.model.Ingredient
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.Role
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.nutrition.Food
import se.pusslet.core.nutrition.NutritionSummary
import se.pusslet.core.recipes.DietConflict
import se.pusslet.core.recipes.RecipeDietCheck
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.IngredientMatch
import se.pusslet.data.usecase.MealShopping
import se.pusslet.data.usecase.RecipeNutrition
import se.pusslet.feature.meals.ConflictNote
import kotlin.time.Clock

sealed interface NutritionState {
    data object Idle : NutritionState

    data object Loading : NutritionState

    data object Offline : NutritionState

    data class Ready(val summary: NutritionSummary, val matches: List<IngredientMatch>) : NutritionState
}

/** Choosing which food an ingredient is ("grädde" -> "Grädde 40 % fett"). */
data class MatchDialog(val ingredient: String, val candidates: List<Food>? = null)

data class RecipeUiState(
    val loading: Boolean = true,
    val recipe: Recipe? = null,
    val servings: Int = 4,
    val ingredients: List<Ingredient> = emptyList(),
    val conflicts: List<ConflictNote> = emptyList(),
    val nutrition: NutritionState = NutritionState.Idle,
    val match: MatchDialog? = null,
    val lists: List<ShoppingList> = emptyList(),
    val planDays: List<LocalDate> = emptyList(),
    val canEdit: Boolean = true,
    val deleted: Boolean = false,
)

sealed interface RecipeMessage {
    data class Planned(val date: LocalDate) : RecipeMessage

    data class AddedToList(val count: Int, val listName: String) : RecipeMessage
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeViewModel(
    private val recipes: RecipeRepository,
    private val meals: MealRepository,
    private val shopping: ShoppingRepository,
    private val family: FamilyRepository,
    private val mealShopping: MealShopping,
    private val nutrition: RecipeNutrition,
    sessions: SessionProvider,
    clock: Clock,
    zone: TimeZone,
) : ViewModel() {
    private val recipeId = MutableStateFlow<String?>(null)
    private val servings = MutableStateFlow<Int?>(null)
    private val nutritionState = MutableStateFlow<NutritionState>(NutritionState.Idle)
    private val match = MutableStateFlow<MatchDialog?>(null)
    private val deleted = MutableStateFlow(false)
    private val today = clock.todayIn(zone)
    private val canEdit = sessions.session.value.role != Role.Caregiver
    private val messageChannel = Channel<RecipeMessage>(Channel.BUFFERED)
    val messages: Flow<RecipeMessage> = messageChannel.receiveAsFlow()

    private val recipe: Flow<Recipe?> = recipeId.filterNotNull().flatMapLatest { recipes.recipe(it) }

    private data class Extra(val servings: Int?, val nutrition: NutritionState, val match: MatchDialog?, val deleted: Boolean)

    private val extra = combine(servings, nutritionState, match, deleted) { s, n, m, d -> Extra(s, n, m, d) }

    val state: StateFlow<RecipeUiState> = combine(recipe, extra, family.people, family.diets, shopping.lists) {
        r,
        e,
        people,
        diets,
        lists,
        ->
        val portions = e.servings ?: r?.servings ?: DEFAULT_SERVINGS
        val byId = people.associateBy { it.id }
        RecipeUiState(
            loading = false,
            recipe = r,
            servings = portions,
            ingredients = r?.let { RecipeScaler.scale(it, portions) }.orEmpty(),
            conflicts = r?.let { RecipeDietCheck.conflicts(it, diets) }.orEmpty().mapNotNull { c ->
                val person = byId[c.personId] ?: return@mapNotNull null
                when (c) {
                    is DietConflict.ContainsAllergen -> ConflictNote(person, allergen = c.allergen)
                    is DietConflict.BreaksDiet -> ConflictNote(person, diet = c.diet)
                }
            },
            nutrition = e.nutrition,
            match = e.match,
            lists = lists,
            planDays = (0 until PLAN_DAYS).map { today.plusDays(it) },
            canEdit = canEdit,
            deleted = e.deleted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RecipeUiState())

    fun load(id: String) {
        if (recipeId.value == id) return
        recipeId.value = id
        servings.value = null
        nutritionState.value = NutritionState.Idle
    }

    fun setServings(n: Int) {
        servings.value = n.coerceIn(1, MAX_SERVINGS)
        if (nutritionState.value is NutritionState.Ready) loadNutrition()
    }

    fun toggleFavorite() = withRecipe { r -> recipes.save(r.copy(favorite = !r.favorite)) }

    fun delete() = withRecipe { r ->
        recipes.delete(r.id)
        deleted.value = true
    }

    fun planOn(date: LocalDate) = withRecipe { r ->
        val existing = meals.getWeek(date.isoWeek).entry(date, MealSlot.Dinner)
        meals.setEntry(
            MealEntry(date, MealSlot.Dinner, r.id, r.title, servings = servings.value, cookDouble = existing?.cookDouble ?: false),
        )
        messageChannel.send(RecipeMessage.Planned(date))
    }

    fun addToList(listId: String) = withRecipe { r ->
        val list = shopping.lists.first().firstOrNull { it.id == listId } ?: return@withRecipe
        val count = mealShopping.addRecipe(listId, r, servings.value ?: r.servings)
        messageChannel.send(RecipeMessage.AddedToList(count, list.name))
    }

    fun loadNutrition() = withRecipe { r ->
        nutritionState.value = NutritionState.Loading
        val portions = servings.value ?: r.servings
        val summary = nutrition.summary(r, portions)
        nutritionState.value = if (summary == null) NutritionState.Offline else NutritionState.Ready(summary, nutrition.matches(r))
    }

    fun openMatch(ingredient: String) {
        match.value = MatchDialog(ingredient)
        viewModelScope.launch { match.value = MatchDialog(ingredient, nutrition.candidates(ingredient)) }
    }

    fun closeMatch() {
        match.value = null
    }

    /** Remembers the choice for the family and stores it on this recipe's matching ingredients. */
    fun confirmMatch(ingredient: String, food: Food) = withRecipe { r ->
        match.value = null
        nutrition.confirm(ingredient, food.number)
        recipes.save(r.copy(ingredients = r.ingredients.map { if (it.name == ingredient) it.copy(foodNumber = food.number) else it }))
        loadNutrition()
    }

    private fun withRecipe(block: suspend (Recipe) -> Unit) {
        viewModelScope.launch {
            val id = recipeId.value ?: return@launch
            val r = recipes.get(id) ?: return@launch
            block(r)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val DEFAULT_SERVINGS = 4
        const val MAX_SERVINGS = 30
        const val PLAN_DAYS = 7
    }
}
