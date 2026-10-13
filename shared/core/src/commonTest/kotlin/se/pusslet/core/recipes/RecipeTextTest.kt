package se.pusslet.core.recipes

import se.pusslet.core.units.MeasureUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecipeTextTest {
    @Test
    fun headingsDecideTheSections() {
        val text = """
            Pannkakor
            4 portioner

            Ingredienser
            3 ägg
            6 dl mjölk
            2½ dl vetemjöl
            1 krm salt

            Gör så här
            1. Vispa ihop mjöl och hälften av mjölken.
            2. Vispa i resten av mjölken och äggen.
            3) Stek tunna pannkakor i smör.
        """.trimIndent()
        val r = RecipeText.split(text)
        assertEquals("Pannkakor", r.title)
        assertEquals(4, r.servings)
        assertEquals(listOf("ägg", "mjölk", "vetemjöl", "salt"), r.ingredients.map { it.name })
        assertEquals(MeasureUnit.Dl, r.ingredients[1].unit)
        assertEquals(2.5, r.ingredients[2].quantity)
        assertEquals("Vispa ihop mjöl och hälften av mjölken.", r.steps.first())
        assertEquals("Stek tunna pannkakor i smör.", r.steps.last())
        assertEquals(3, r.steps.size)
    }

    @Test
    fun withoutHeadingsAmountsMeanIngredients() {
        val text = """
            Snabb tomatsoppa
            2 burkar krossade tomater
            1 gul lök
            5 dl vatten
            Fräs löken mjuk i lite olja.
            Häll i tomaterna och vattnet och låt koka 10 minuter.
        """.trimIndent()
        val r = RecipeText.split(text)
        assertEquals("Snabb tomatsoppa", r.title)
        assertNull(r.servings)
        assertEquals(3, r.ingredients.size)
        assertEquals(2, r.steps.size)
        assertEquals("Häll i tomaterna och vattnet och låt koka 10 minuter.", r.steps[1])
    }

    @Test
    fun ingredientGroupsAndBullets() {
        val text = """
            Ingredienser (4 personer)
            Sås:
            • 2 dl grädde
            - 1 msk soja
            Till servering:
            * 4 dl ris
            Instruktioner
            Steg 1. Koka riset.
            Steg 2: Värm såsen.
        """.trimIndent()
        val r = RecipeText.split(text)
        assertEquals(4, r.servings)
        assertEquals(listOf("Sås", "Sås", "Till servering"), r.ingredients.map { it.group })
        assertEquals(listOf("grädde", "soja", "ris"), r.ingredients.map { it.name })
        assertEquals(listOf("Koka riset.", "Värm såsen."), r.steps)
    }

    @Test
    fun englishDecimalPointIsNotAStepNumber() {
        val r = RecipeText.split("Ingredients\n2.5 dl milk\nMethod\n1. Mix.")
        assertEquals(1, r.ingredients.size)
        assertEquals(listOf("Mix."), r.steps)
    }

    @Test
    fun editorLinesRoundTrip() {
        val ingredients = listOf(
            "2½ dl vispgrädde",
            "1 gul lök, finhackad",
            "salt",
        ).map { IngredientParser.parse(it) } + listOf("1 msk soja", "1 tsk honung").map { IngredientParser.parse(it, "Sås") }
        val text = RecipeText.formatIngredients(ingredients)
        assertEquals("2 ½ dl vispgrädde\n1 st gul lök, finhackad\nsalt\n\nSås:\n1 msk soja\n1 tsk honung", text)
        val back = RecipeText.parseIngredients(text)
        assertEquals(ingredients.map { it.name to it.amount }, back.map { it.name to it.amount })
        assertEquals(listOf(null, null, null, "Sås", "Sås"), back.map { it.group })
        assertEquals("finhackad", back[1].note)
    }

    @Test
    fun stepsOnePerLine() {
        val steps = RecipeText.parseSteps("1. Sätt ugnen på 200 grader.\n\n2. Grädda i 20 minuter.")
        assertEquals(listOf("Sätt ugnen på 200 grader.", "Grädda i 20 minuter."), steps)
    }
}
