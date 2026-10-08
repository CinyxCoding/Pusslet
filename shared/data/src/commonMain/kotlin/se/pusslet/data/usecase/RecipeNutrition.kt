package se.pusslet.data.usecase

import kotlinx.coroutines.flow.first
import se.pusslet.core.model.Recipe
import se.pusslet.core.nutrition.Food
import se.pusslet.core.nutrition.FoodMatcher
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
        val mappings = recipes.ingredientMappings.first()
        val numbers = recipe.ingredients.filter { !it.optional }.associate { ing ->
            ing.name to (ing.foodNumber ?: mappings[ing.name.trim().lowercase()] ?: bestMatch(ing.name)?.number)
        }
        val values: Map<Int, NutrientValues> = numbers.values.filterNotNull().distinct()
            .mapNotNull { n -> foods.nutrients(n)?.let { n to it } }
            .toMap()
        return NutritionCalculator.forRecipe(recipe, { numbers[it.name] }, values::get, servings)
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
