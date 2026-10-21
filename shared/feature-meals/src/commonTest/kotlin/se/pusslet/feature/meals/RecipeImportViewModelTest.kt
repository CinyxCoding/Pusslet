package se.pusslet.feature.meals

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import se.pusslet.core.model.Recipe
import se.pusslet.data.usecase.RecipeImporter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Importera från länk" with a weekly menu and several links, against pages written for the test (see RecipeLinksTest). */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeImportViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val menuTitle = "Veckomeny 44 - 2026"
    private val menu = """<script type="application/ld+json">{"@type":"Article","headline":"$menuTitle"}</script>
        <page-week :week="{&quot;recipes&quot;:[{&quot;slug&quot;:&quot;linssoppa&quot;},{&quot;slug&quot;:&quot;borta&quot;},
        {&quot;slug&quot;:&quot;fiskgratang&quot;}]}"></page-week>"""
    private val requests = mutableListOf<String>()

    private fun recipePage(name: String) = """<script type="application/ld+json">
        {"@type":"Recipe","name":"$name","recipeYield":"2"}</script>"""

    private fun TestScope.server() = HttpClient(
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = UnconfinedTestDispatcher(testScheduler)
                addHandler { request ->
                    requests += request.url.encodedPath
                    val body = when (request.url.encodedPath) {
                        "/veckomeny/2026-44" -> menu
                        "/recept/linssoppa" -> recipePage("Linssoppa")
                        "/recept/fiskgratang" -> recipePage("Fiskgratäng")
                        "/recept/tacos" -> recipePage("Tacos")
                        else -> null
                    }
                    if (body == null) {
                        respond("", HttpStatusCode.NotFound)
                    } else {
                        respond(body, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
                    }
                }
            },
        ),
    )

    private fun RecipeImportState.titles() = batch.orEmpty().flatMap { link -> link.found.map { it.imported.recipe.title } }

    @Test
    fun aWeeklyMenuIsTickedAndSavedInItsCollection() = runTest {
        val world = TestWorld()
        world.recipes.save(Recipe("old", "Linssoppa", sourceUrl = "https://recept.se/recept/linssoppa", favorite = true))
        val vm = RecipeImportViewModel(RecipeImporter(server(), world.ids), world.recipes, world.ids)

        vm.import("https://recept.se/veckomeny/2026-44")
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue(state.batchDone)
        assertEquals(LinkStatus.Menu(menuTitle, 3), state.batch?.first()?.status)
        assertEquals(listOf("Linssoppa", "Fiskgratäng"), state.titles())
        assertEquals("old", state.batch?.get(1)?.found?.single()?.existingId, "saved before, so it is updated")
        assertIs<LinkStatus.NotReachable>(state.batch?.get(2)?.status)
        assertEquals(listOf(menuTitle), state.selectedCollections)
        assertEquals(listOf("/veckomeny/2026-44", "/recept/linssoppa", "/recept/borta", "/recept/fiskgratang"), requests)

        vm.toggle(link = 3, recipe = 0)
        assertEquals(1, vm.state.value.selectedCount, "Fiskgratäng unticked")
        vm.toggle(link = 3, recipe = 0)
        vm.saveAll()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.savedCount)
        val all = world.recipes.all()
        assertEquals(listOf("Fiskgratäng", "Linssoppa"), all.map { it.title }.sorted())
        val soup = all.single { it.title == "Linssoppa" }
        assertTrue(soup.id == "old" && soup.favorite, "the earlier copy, still a favourite")
        val collection = world.recipes.allCollections().single()
        assertEquals(menuTitle, collection.name)
        assertEquals(all.map { it.id }.toSet(), collection.recipeIds.keys)
    }

    @Test
    fun aMenuAmongPastedLinksBringsItsRecipesAlong() = runTest {
        val world = TestWorld()
        val vm = RecipeImportViewModel(RecipeImporter(server(), world.ids), world.recipes, world.ids)

        vm.import("https://recept.se/recept/tacos\nhttps://recept.se/veckomeny/2026-44\nhttps://recept.se/recept/linssoppa")
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(
            listOf("tacos", "2026-44", "borta", "fiskgratang", "linssoppa"),
            state.batch?.map { it.url.substringAfterLast('/') },
            "the menu's recipes right after it; the pasted soup stays where it is",
        )
        assertEquals(5, requests.size, "every page once")
        assertEquals(listOf("Tacos", "Fiskgratäng", "Linssoppa"), state.titles())
        assertNull(state.batch?.first()?.found?.single()?.collection, "Tacos is not on the menu")
        assertEquals(listOf(menuTitle), state.selectedCollections, "the pasted soup joins the menu")

        vm.saveAll()
        advanceUntilIdle()
        assertEquals(3, world.recipes.all().size)
        assertEquals(2, world.recipes.allCollections().single().recipeIds.size)
    }

    @Test
    fun oneRecipeIsSavedAtOnce() = runTest {
        val world = TestWorld()
        val vm = RecipeImportViewModel(RecipeImporter(server(), world.ids), world.recipes, world.ids)
        vm.import("Testa! https://recept.se/recept/tacos")
        advanceUntilIdle()
        val saved = vm.state.value.savedId
        assertEquals("Tacos", saved?.let { world.recipes.get(it) }?.title)
        assertNull(vm.state.value.batch)
        assertTrue(world.recipes.allCollections().isEmpty())
    }
}
