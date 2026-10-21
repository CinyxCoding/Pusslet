package se.pusslet.core.recipes

import se.pusslet.core.model.MainComponent
import se.pusslet.core.model.RecipeTag
import se.pusslet.core.units.MeasureUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pages below are written for the test but follow the structures Swedish recipe sites actually publish
 * (a Yoast-style @graph, HowToSection groups, ImageObject, HTML entities in strings, ISO 8601 durations).
 */
class RecipeJsonLdTest {
    private val graphPage = """
        <html><head>
        <script type="application/ld+json">{"@context":"https://schema.org","@type":"BreadcrumbList","itemListElement":[]}</script>
        <script type='application/ld+json' class="yoast-schema-graph">
        {"@context":"https://schema.org","@graph":[
          {"@type":"WebPage","name":"Krämig kycklinggryta"},
          {"@type":["Recipe","NewsArticle"],
           "name":"Krämig kycklinggryta med curry &amp; äpple",
           "recipeYield":["4","4 portioner"],
           "totalTime":"PT35M","prepTime":"PT10M",
           "image":{"@type":"ImageObject","url":"https://example.se/bild.jpg"},
           "publisher":{"@type":"Organization","name":"Exempelköket"},
           "keywords":"kyckling, snabbt, vardag",
           "recipeIngredient":["600 g kycklingfilé","1 gul lök, hackad","2 dl matlagningsgrädde","1 msk curry","salt och peppar"],
           "recipeInstructions":[
             {"@type":"HowToSection","name":"Gryta","itemListElement":[
               {"@type":"HowToStep","text":"Strimla kycklingen och bryn den i olja."},
               {"@type":"HowToStep","text":"Tillsätt l&ouml;k och curry &ndash; fräs 2 min."}]},
             {"@type":"HowToStep","text":"Häll i grädden och låt sjuda 10 min."}]}
        ]}
        </script></head><body></body></html>
    """.trimIndent()

    @Test
    fun readsRecipeFromGraph() {
        val imported = assertNotNull(RecipeJsonLd.extract(graphPage, "https://www.example.se/recept/gryta"))
        val r = imported.recipe
        assertEquals("Krämig kycklinggryta med curry & äpple", r.title)
        assertEquals(4, r.servings)
        assertEquals(35, r.totalMinutes)
        assertEquals(10, r.prepMinutes)
        assertEquals("https://example.se/bild.jpg", r.imageUrl)
        assertEquals("Exempelköket", r.sourceName)
        assertEquals("https://www.example.se/recept/gryta", r.sourceUrl)
        assertEquals(5, r.ingredients.size)
        assertEquals(MeasureUnit.G, r.ingredients[0].unit)
        assertEquals("gul lök", r.ingredients[1].name)
        assertTrue(r.ingredients[4].optional)
        assertEquals(
            listOf(
                "Gryta:",
                "Strimla kycklingen och bryn den i olja.",
                "Tillsätt lök och curry – fräs 2 min.",
                "Häll i grädden och låt sjuda 10 min.",
            ),
            r.steps,
        )
        assertEquals(MainComponent.Chicken, r.mainComponent)
        assertTrue(RecipeTag.Quick in r.tags)
        assertTrue(imported.warnings.isEmpty())
    }

    @Test
    fun plainStringInstructionsAndNumericYield() {
        val page = """
            <script type="application/ld+json">
            {"@context":"http://schema.org/","@type":"Recipe","name":"Pannkakor","recipeYield":4,
             "recipeIngredient":["3 ägg","6 dl mjölk","2½ dl vetemjöl"],
             "recipeInstructions":"1. Vispa ihop mjöl och hälften av mjölken. 2. Vispa i resten av mjölken och äggen.\n3. Stek tunna pannkakor."}
            </script>
        """.trimIndent()
        val r = assertNotNull(RecipeJsonLd.extract(page, "https://example.se/p")).recipe
        assertEquals(4, r.servings)
        assertEquals(2.5, r.ingredients[2].quantity)
        assertEquals(3, r.steps.size)
        assertEquals("Stek tunna pannkakor.", r.steps[2])
        assertEquals("example.se", r.sourceName)
    }

    @Test
    fun missingYieldIsGuessedAndFlagged() {
        val page = """<script type="application/ld+json">{"@type":"Recipe","name":"Soppa","recipeIngredient":["1 l buljong"]}</script>"""
        val imported = assertNotNull(RecipeJsonLd.extract(page, "https://example.se/s"))
        assertEquals(4, imported.recipe.servings)
        assertTrue(ImportWarning.ServingsGuessed in imported.warnings)
        assertTrue(ImportWarning.NoSteps in imported.warnings)
    }

    @Test
    fun pagesWithoutRecipeDataReturnNull() {
        assertNull(
            RecipeJsonLd.extract("<html><script type=\"application/ld+json\">{\"@type\":\"WebPage\"}</script></html>", "https://e.se"),
        )
        assertNull(RecipeJsonLd.extract("<html><script type=\"application/ld+json\">{ broken </script></html>", "https://e.se"))
        assertNull(RecipeJsonLd.extract("<html>no data</html>", "https://e.se"))
    }

    @Test
    fun aWeekMenuPageGivesEveryRecipeOnIt() {
        val html = """<html><script type="application/ld+json">{"@context":"https://schema.org","@type":"ItemList","itemListElement":[
            {"@type":"ListItem","position":1,"item":{"@type":"Recipe","name":"Fiskgratäng","url":"https://example.se/recept/fiskgratang",
              "recipeIngredient":["600 g torsk"],"recipeInstructions":"Gratinera."}},
            {"@type":"ListItem","position":2,"item":{"@type":"Recipe","name":"Linssoppa","recipeIngredient":["2 dl röda linser"]}},
            {"@type":"ListItem","position":3,"url":"https://example.se/recept/tacos"}
        ]}</script>
        <script type="application/ld+json">{"@type":"Recipe","name":"Fiskgratäng","url":"https://example.se/recept/fiskgratang"}</script></html>"""
        var n = 0
        val all = RecipeJsonLd.extractAll(html, "https://example.se/veckomeny/44") { "r${++n}" }
        assertEquals(listOf("Fiskgratäng", "Linssoppa"), all.map { it.recipe.title })
        // Each keeps its own page when it names one, else the page it was read from; the plain link is not followed.
        assertEquals(listOf("https://example.se/recept/fiskgratang", "https://example.se/veckomeny/44"), all.map { it.recipe.sourceUrl })
        assertEquals(listOf("r1", "r2"), all.map { it.recipe.id })
    }

    @Test
    fun aPageWithoutRecipesGivesNone() {
        assertTrue(RecipeJsonLd.extractAll("<html><p>Veckomeny</p></html>", "https://example.se").isEmpty())
    }
}
