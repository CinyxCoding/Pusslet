package se.pusslet.data.usecase

import kotlinx.coroutines.flow.first
import se.pusslet.core.model.Recipe
import se.pusslet.core.nutrition.Food
import se.pusslet.core.nutrition.FoodMatcher
import se.pusslet.core.nutrition.GramEstimator
import se.pusslet.core.nutrition.NutrientValues
import se.pusslet.core.nutrition.NutritionCalculator
import se.pusslet.core.nutrition.NutritionSummary
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.sources.FoodDatabase

/**
 * Approximate nutrition per portion from Livsmedelsverket's food database (CC BY 4.0). Ingredients are matched to foods
 * by name (confirmed matches are remembered for the family); unmatched ones are reported, never guessed silently.
 */
class RecipeNutrition(private val foods: FoodDatabase, private val recipes: RecipeRepository) {
    /** Null when the database has never been downloaded and the phone is offline. */
    suspend fun summary(recipe: Recipe, servings: Int = recipe.servings): NutritionSummary? {
        if (!foods.ensureLoaded()) return null
        val numbers = numbers(recipe)
        val values: Map<Int, NutrientValues> = numbers.values.filterNotNull().distinct()
            .mapNotNull { n -> foods.nutrients(n)?.let { n to it } }
            .toMap()
        return NutritionCalculator.forRecipe(recipe, { numbers[it.name] }, values::get, servings)
    }

    /** Which food each ingredient is counted as, so the user can check and correct it. Empty when offline. */
    suspend fun matches(recipe: Recipe): List<IngredientMatch> {
        if (!foods.ensureLoaded()) return emptyList()
        val numbers = numbers(recipe)
        return recipe.ingredients.filter { !it.optional }.map { ing ->
            IngredientMatch(ing.name, numbers[ing.name]?.let { foods.food(it) }, GramEstimator.grams(ing) != null)
        }
    }

    private suspend fun numbers(recipe: Recipe): Map<String, Int?> {
        val mappings = recipes.ingredientMappings.first()
        return recipe.ingredients.filter { !it.optional }.associate { ing ->
            ing.name to (ing.foodNumber ?: mappings[ing.name.trim().lowercase()] ?: bestMatch(ing.name)?.number)
        }
    }

    suspend fun candidates(ingredientName: String): List<Food> {
        if (!foods.ensureLoaded()) return emptyList()
        return FoodMatcher.best(ingredientName, foods.search(ingredientName, SEARCH_LIMIT), limit = 8)
    }

    suspend fun confirm(ingredientName: String, foodNumber: Int) = recipes.rememberMapping(ingredientName.lowercase(), foodNumber)

    private suspend fun bestMatch(name: String): Food? = FoodMatcher.best(name, foods.search(name, SEARCH_LIMIT), limit = 1).firstOrNull()

    private companion object {
        const val SEARCH_LIMIT = 30
    }
}

/** [food] null = no match; [convertible] false = the amount cannot be turned into grams ("1 förp"). */
data class IngredientMatch(val ingredient: String, val food: Food?, val convertible: Boolean)
