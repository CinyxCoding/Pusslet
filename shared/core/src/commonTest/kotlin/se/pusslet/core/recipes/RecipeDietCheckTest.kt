package se.pusslet.core.recipes

import kotlinx.datetime.LocalDate
import se.pusslet.core.meals.MealSuggester
import se.pusslet.core.meals.SuggestionReason
import se.pusslet.core.model.Allergen
import se.pusslet.core.model.Diet
import se.pusslet.core.model.DietProfile
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeDietCheckTest {
    private fun recipe(id: String, vararg lines: String, tags: Set<RecipeTag> = emptySet()) =
        Recipe(id, id, 4, ingredients = lines.map { IngredientParser.parse(it) }, tags = tags)

    private val pasta = recipe("pasta", "400 g spaghetti", "2 dl vispgrädde", "1 dl riven parmesan", "2 msk soja")
    private val tacos = recipe("tacos", "500 g nötfärs", "8 majstortillas", "1 gurka", "2 dl gräddfil")
    private val curry = recipe("curry", "4 dl kokosmjölk", "1 burk kikärtor", "2 dl ris", "1 rostad lök", tags = setOf(RecipeTag.Vegan))
    private val salmon = recipe("lax", "600 g laxfilé", "1 kg potatis", "1 msk dijonsenap")

    @Test
    fun allergensAreFoundInCompoundWords() {
        val a = RecipeDietCheck.allergens(pasta)
        assertEquals("vispgrädde", a[Allergen.Milk])
        assertEquals("vispgrädde", a[Allergen.Lactose])
        assertEquals("spaghetti", a[Allergen.Gluten])
        assertEquals(Allergen.Fish to "laxfilé", RecipeDietCheck.allergens(salmon).entries.first { it.key == Allergen.Fish }.toPair())
        assertEquals("dijonsenap", RecipeDietCheck.allergens(salmon)[Allergen.Mustard])
    }

    @Test
    fun noFalsePositivesForLookAlikes() {
        val a = RecipeDietCheck.allergens(curry)
        assertNull(a[Allergen.Milk], "kokosmjölk is not milk, rostad lök is not cheese")
        assertNull(a[Allergen.Gluten])
        assertNull(RecipeDietCheck.allergens(tacos)[Allergen.Gluten], "majstortillas are gluten free")
    }

    @Test
    fun soySauceCountsAsGluten() {
        val r = recipe("wok", "2 msk soja", "300 g risnudlar")
        assertEquals("soja", RecipeDietCheck.allergens(r)[Allergen.Gluten])
    }

    @Test
    fun dietsAreChecked() {
        assertEquals("nötfärs", RecipeDietCheck.breaks(tacos, Diet.Vegetarian))
        assertNull(RecipeDietCheck.breaks(curry, Diet.Vegan))
        assertEquals("laxfilé", RecipeDietCheck.breaks(salmon, Diet.Vegetarian))
        assertNull(RecipeDietCheck.breaks(salmon, Diet.Pescetarian))
        assertEquals("vispgrädde", RecipeDietCheck.breaks(pasta, Diet.Vegan))
        assertNull(RecipeDietCheck.breaks(pasta, Diet.Vegetarian))
        val bacon = recipe("carbonara", "150 g bacon", "400 g pasta")
        assertEquals("bacon", RecipeDietCheck.breaks(bacon, Diet.NoPork))
        assertNull(RecipeDietCheck.breaks(tacos, Diet.NoPork))
    }

    @Test
    fun vegetarianStockAndMinceAreFine() {
        val r = recipe("chili", "400 g sojafärs", "1 tärning grönsaksbuljong")
        assertNull(RecipeDietCheck.breaks(r, Diet.Vegetarian))
    }

    @Test
    fun conflictsNameThePersonAndTheIngredient() {
        val profiles = listOf(
            DietProfile("c-ella", allergies = setOf(Allergen.Fish)),
            DietProfile("m-sara", diets = setOf(Diet.Vegetarian)),
        )
        val c = RecipeDietCheck.conflicts(salmon, profiles)
        assertEquals(2, c.size)
        val allergy = assertIs<DietConflict.ContainsAllergen>(c.first { it.personId == "c-ella" })
        assertEquals("laxfilé", allergy.ingredient)
        assertTrue(RecipeDietCheck.conflicts(curry, profiles).isEmpty())
        assertTrue(RecipeDietCheck.conflicts(salmon, emptyList()).isEmpty())
    }

    @Test
    fun suggestionsNeverIncludeAllergensAndPreferWhatSuitsEveryone() {
        val profiles =
            listOf(DietProfile("c-ella", allergies = setOf(Allergen.Fish)), DietProfile("m-sara", diets = setOf(Diet.Vegetarian)))
        val s = MealSuggester.suggest(LocalDate(2026, 10, 14), listOf(pasta, tacos, curry, salmon), emptyList(), dietProfiles = profiles)
        assertFalse(s.any { it.recipe.id == "lax" })
        assertEquals("curry", s.first().recipe.id, "fits the vegetarian too")
        assertTrue(SuggestionReason.SuitsEveryone in s.first().reasons)
        assertFalse(SuggestionReason.SuitsEveryone in s.first { it.recipe.id == "tacos" }.reasons)
    }
}
