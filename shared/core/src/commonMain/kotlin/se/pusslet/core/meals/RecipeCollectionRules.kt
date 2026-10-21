package se.pusslet.core.meals

import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeCollection

/**
 * Rules for the family's recipe collections ("Vardagsfavoriter", "Kalas", "Matlådor"): tidy names that are unique
 * whatever the case, and recipe lists that skip recipes deleted since. Deleting a collection never deletes a recipe.
 */
object RecipeCollectionRules {
    const val MAX_NAME_LENGTH = 60

    /** " kalas  för barnen " -> "kalas för barnen"; blank -> null. */
    fun cleanName(name: String): String? =
        name.trim().replace(WHITESPACE, " ").take(MAX_NAME_LENGTH).trim().ifEmpty { null }

    /** Another collection than [exceptId] already has this name ("Kalas" and "kalas" are the same). */
    fun nameTaken(collections: List<RecipeCollection>, name: String, exceptId: String? = null): Boolean {
        val wanted = cleanName(name)?.lowercase() ?: return false
        return collections.any { it.id != exceptId && it.name.trim().lowercase() == wanted }
    }

    /** The collection with this name, whatever the case. */
    fun named(collections: List<RecipeCollection>, name: String): RecipeCollection? {
        val wanted = cleanName(name)?.lowercase() ?: return null
        return collections.firstOrNull { it.name.trim().lowercase() == wanted }
    }

    /** By name, the way the chips show them. */
    fun sorted(collections: List<RecipeCollection>): List<RecipeCollection> =
        collections.sortedWith(compareBy<RecipeCollection> { it.name.lowercase() }.thenBy { it.id })

    /** The recipes of [collection] that still exist, in the order of [recipes]. */
    fun recipesIn(collection: RecipeCollection, recipes: List<Recipe>): List<Recipe> = recipes.filter { it.id in collection }

    /** How many of the collection's recipes still exist. */
    fun count(collection: RecipeCollection, recipes: List<Recipe>): Int = recipes.count { it.id in collection }

    /** The collections [recipeId] is in. */
    fun collectionsOf(recipeId: String, collections: List<RecipeCollection>): List<RecipeCollection> =
        sorted(collections.filter { recipeId in it })

    private val WHITESPACE = Regex("\\s+")
}
