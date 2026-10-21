package se.pusslet.core.meals

import kotlinx.serialization.json.Json
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeCollection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeCollectionsTest {
    private val tacos = Recipe("r-tacos", "Tacos")
    private val pesto = Recipe("r-pesto", "Pasta med pesto")
    private val soup = Recipe("r-soup", "Linssoppa")

    @Test
    fun addAndRemoveRecipes() {
        val party = RecipeCollection("c1", "Kalas").with("r-tacos").with("r-pesto")
        assertTrue("r-tacos" in party)
        assertTrue("r-pesto" in party)
        val fewer = party.without("r-tacos")
        assertFalse("r-tacos" in fewer)
        assertEquals(mapOf("r-pesto" to true), fewer.recipeIds)
        assertEquals(fewer, fewer.without("r-unknown"))
    }

    @Test
    fun deletedRecipesAreSkipped() {
        val party = RecipeCollection("c1", "Kalas", mapOf("r-tacos" to true, "r-gone" to true, "r-soup" to true))
        assertEquals(listOf("r-tacos", "r-soup"), RecipeCollectionRules.recipesIn(party, listOf(tacos, pesto, soup)).map { it.id })
        assertEquals(2, RecipeCollectionRules.count(party, listOf(tacos, pesto, soup)))
    }

    @Test
    fun namesAreTidyAndUniqueWhateverTheCase() {
        assertEquals("Kalas för barnen", RecipeCollectionRules.cleanName("  Kalas   för\tbarnen "))
        assertNull(RecipeCollectionRules.cleanName("   "))
        assertEquals(RecipeCollectionRules.MAX_NAME_LENGTH, RecipeCollectionRules.cleanName("x".repeat(100))?.length)
        val all = listOf(RecipeCollection("c1", "Kalas"), RecipeCollection("c2", "Matlådor"))
        assertTrue(RecipeCollectionRules.nameTaken(all, " kalas "))
        assertFalse(RecipeCollectionRules.nameTaken(all, "kalas", exceptId = "c1"), "renaming to its own name in another case")
        assertFalse(RecipeCollectionRules.nameTaken(all, "Vardagsfavoriter"))
        assertFalse(RecipeCollectionRules.nameTaken(all, " "))
    }

    @Test
    fun aRecipesCollectionsAreSortedByName() {
        val all = listOf(
            RecipeCollection("c1", "Matlådor", mapOf("r-soup" to true)),
            RecipeCollection("c2", "kalas", mapOf("r-soup" to true, "r-tacos" to true)),
            RecipeCollection("c3", "Vardagsfavoriter", mapOf("r-tacos" to true)),
        )
        assertEquals(listOf("kalas", "Matlådor"), RecipeCollectionRules.collectionsOf("r-soup", all).map { it.name })
        assertEquals(listOf("kalas", "Matlådor", "Vardagsfavoriter"), RecipeCollectionRules.sorted(all).map { it.name })
    }

    @Test
    fun aCollectionWithoutRecipesDecodes() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(
            RecipeCollection("c1", "Kalas"),
            json.decodeFromString(RecipeCollection.serializer(), """{"id":"c1","name":"Kalas"}"""),
        )
    }
}
