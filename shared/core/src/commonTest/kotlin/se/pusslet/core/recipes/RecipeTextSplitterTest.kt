package se.pusslet.core.recipes

import se.pusslet.core.units.MeasureUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeTextSplitterTest {
    @Test
    fun headingsSplitIngredientsAndSteps() {
        val text = """
            Krämig pasta med kyckling
            4 portioner

            Ingredienser
            400 g pasta
            2 kycklingfiléer
            2 dl matlagningsgrädde
            1 gul lök, finhackad
            Salt och peppar

            Gör så här
            1. Koka pastan enligt anvisningen på förpackningen.
            2. Skär kycklingen i bitar och stek den gyllene.
            3. Häll i grädden och låt puttra i 5 minuter.
        """.trimIndent()
        val r = RecipeTextSplitter.split(text)
        assertEquals("Krämig pasta med kyckling", r.title)
        assertEquals(4, r.servings)
        assertEquals(listOf("pasta", "kycklingfiléer", "matlagningsgrädde", "gul lök", "Salt och peppar"), r.ingredients.map { it.name })
        assertEquals(MeasureUnit.Dl, r.ingredients[2].unit)
        assertEquals(3, r.steps.size)
        assertEquals("Koka pastan enligt anvisningen på förpackningen.", r.steps[0])
    }

    @Test
    fun withoutHeadingsLinesAreClassified() {
        val text = """
            Pannkakor
            3 dl vetemjöl
            6 dl mjölk
            3 ägg
            1 krm salt
            Vispa ihop mjöl och hälften av mjölken till en slät smet.
            Vispa i resten av mjölken och äggen.
            Stek tunna pannkakor i smör.
        """.trimIndent()
        val r = RecipeTextSplitter.split(text)
        assertEquals("Pannkakor", r.title)
        assertNull(r.servings)
        assertEquals(4, r.ingredients.size)
        assertEquals(3, r.steps.size)
        assertEquals(3.0, r.ingredients[2].quantity)
    }

    @Test
    fun subGroupsAndStepsWithoutHeadingAfterIngredients() {
        val text = """
            Ingredienser (till 4 personer)
            Köttbullar:
            500 g blandfärs
            1 ägg
            Sås:
            2 dl grädde
            2 msk soja
            Blanda färsen med ägget och rulla små bullar som du steker i smör.
            Koka upp grädde och soja.
        """.trimIndent()
        val r = RecipeTextSplitter.split(text)
        assertEquals("", r.title)
        assertEquals(4, r.servings)
        assertEquals(listOf("Köttbullar", "Köttbullar", "Sås", "Sås"), r.ingredients.map { it.group })
        assertEquals(2, r.steps.size)
    }

    @Test
    fun wrappedStepLinesAreJoined() {
        val text = """
            Gör så här
            Sätt ugnen på 200 grader och lägg
            potatisen i en ugnsform
            Rosta i 30 minuter.
        """.trimIndent()
        val r = RecipeTextSplitter.split(text)
        assertEquals(listOf("Sätt ugnen på 200 grader och lägg potatisen i en ugnsform", "Rosta i 30 minuter."), r.steps)
    }

    @Test
    fun englishHeadingsAndServesWork() {
        val text = """
            Serves 2
            Ingredients
            200 g spaghetti
            Method
            Boil the pasta.
        """.trimIndent()
        val r = RecipeTextSplitter.split(text)
        assertEquals(2, r.servings)
        assertEquals("spaghetti", r.ingredients.single().name)
        assertEquals(listOf("Boil the pasta."), r.steps)
    }

    @Test
    fun ingredientLikeLines() {
        assertTrue(RecipeTextSplitter.isIngredientLike("2 dl grädde"))
        assertTrue(RecipeTextSplitter.isIngredientLike("• salt"))
        assertFalse(RecipeTextSplitter.isIngredientLike("1. Hacka löken fint."))
        assertFalse(RecipeTextSplitter.isIngredientLike("Låt allt koka ihop under lock i tio minuter."))
    }
}
