package se.pusslet.core.recipes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pages below are written for the test. The recept.se one follows how its weekly menus look (October 2026): an
 * Article and a BreadcrumbList as JSON-LD, and the recipes only in the HTML-escaped `:week` attribute of `<page-week>`.
 */
class RecipeLinksTest {
    private val receptSeWeek = """
        <html><head><title>Veckomeny 44 - 2026</title>
        <script type="application/ld+json">
        {"@context":"http:\/\/schema.org","@type":"Article","headline":"Veckomeny 44 - 2026","url":"https:\/\/recept.se\/veckomeny\/lattlagade-veckomenyer\/2026-44"}
        </script>
        <script type="application/ld+json">
        {"@context":"http:\/\/schema.org","@type":"BreadcrumbList","itemListElement":[{"@type":"ListItem","position":1,"item":{"@id":"\/veckomeny\/lattlagade-veckomenyer","name":"Lättlagade veckomenyer"}}]}
        </script></head><body>
        <page-week
                :menu="{&quot;id&quot;:1,&quot;slug&quot;:&quot;lattlagade-veckomenyer&quot;}"
                :week="{&quot;author&quot;:{&quot;slug&quot;:&quot;en-kock&quot;},&quot;content&quot;:&quot;&lt;p&gt;Fisk &amp;amp; soppa&lt;\/p&gt;&quot;,&quot;recipes&quot;:[{&quot;author&quot;:{&quot;slug&quot;:&quot;en-kock&quot;},&quot;id&quot;:1,&quot;slug&quot;:&quot;fiskgratang-med-dill&quot;,&quot;title&quot;:&quot;Fiskgratäng&quot;},{&quot;id&quot;:2,&quot;slug&quot;:&quot;linssoppa&quot;,&quot;title&quot;:&quot;Linssoppa&quot;},{&quot;id&quot;:3,&quot;slug&quot;:&quot;..\/konto&quot;}],&quot;title&quot;:&quot;Vecka 44&quot;}"
        ></page-week></body></html>
    """.trimIndent()

    private fun itemListPage(vararg links: String): String {
        val items = links.joinToString(",")
        return """
            <html><head><meta property="og:title" content="Veckans middagar"><script type="application/ld+json">
            {"@context":"https://schema.org","@graph":[{"@type":"WebSite","name":"Exempelköket"},
             {"@type":"ItemList","itemListElement":[$items]}]}
            </script></head></html>
            """.trimIndent()
    }

    @Test
    fun receptSeWeeklyMenuListsItsRecipes() {
        val list = RecipeLinks.extract(receptSeWeek, "https://recept.se/veckomeny/lattlagade-veckomenyer/2026-44")
        assertEquals("Veckomeny 44 - 2026", list?.title)
        assertEquals(
            listOf("https://recept.se/recept/fiskgratang-med-dill", "https://recept.se/recept/linssoppa"),
            list?.links,
            "the author's slug and a slug that is not a plain name are skipped",
        )
        assertEquals(list, RecipeLinks.extract(receptSeWeek, "https://www.recept.se/veckomeny/lattlagade-veckomenyer/2026-44"))
    }

    @Test
    fun theWeeklyMenuMarkupCountsOnlyOnReceptSe() {
        assertNull(RecipeLinks.extract(receptSeWeek, "https://example.se/veckomeny/2026-44"))
    }

    @Test
    fun itemListGivesLinksOnTheSameSite() {
        val page = itemListPage(
            """{"@type":"ListItem","position":1,"url":"https://www.example.se/recept/tacos"}""",
            """{"@type":"ListItem","position":2,"item":{"@id":"/recept/pannkakor"}}""",
            """{"@type":"ListItem","position":3,"item":"https://annan.se/recept/lasagne"}""",
            """{"@type":"Recipe","url":"/recept/pannkakor#betyg"}""",
            """"https://example.se/recept/kottbullar"""",
        )
        val list = RecipeLinks.extract(page, "https://example.se/meny/vecka-44")
        assertEquals("Veckans middagar", list?.title, "the page title when the list has no name")
        assertEquals(
            listOf("https://www.example.se/recept/tacos", "https://example.se/recept/pannkakor", "https://example.se/recept/kottbullar"),
            list?.links,
        )
    }

    @Test
    fun aNamedListKeepsItsName() {
        val page = """<script type="application/ld+json">{"@type":"ItemList","name":"Höstens soppor",
            "itemListElement":[{"@type":"ListItem","url":"https://example.se/recept/linssoppa"}]}</script>"""
        assertEquals(
            RecipeList("Höstens soppor", listOf("https://example.se/recept/linssoppa")),
            RecipeLinks.extract(page, "https://example.se/soppor"),
        )
    }

    @Test
    fun longListsAndPagesWithoutAListAreNotMenus() {
        val category = itemListPage(
            *(1..RecipeLinks.MAX_LINKS + 1).map { """{"@type":"ListItem","url":"https://example.se/recept/r$it"}""" }.toTypedArray(),
        )
        assertNull(RecipeLinks.extract(category, "https://example.se/kyckling"), "a category page")
        val breadcrumbsOnly = """<script type="application/ld+json">{"@type":"BreadcrumbList","itemListElement":[
            {"@type":"ListItem","item":{"@id":"https://example.se/recept"}}]}</script>"""
        assertNull(RecipeLinks.extract(breadcrumbsOnly, "https://example.se/om-oss"))
        assertNull(RecipeLinks.extract("<html><body>Inga recept här</body></html>", "https://example.se/"))
    }
}
