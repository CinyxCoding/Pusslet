package se.pusslet.feature.meals

import kotlinx.coroutines.flow.first
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeTag
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.nutrition.Food
import se.pusslet.core.nutrition.NutrientValues
import se.pusslet.core.recipes.IngredientParser
import se.pusslet.core.time.isoWeek
import se.pusslet.core.units.MeasureUnit
import se.pusslet.feature.meals.recipes.EditorStage
import se.pusslet.feature.meals.recipes.ImportProblem
import se.pusslet.feature.meals.recipes.NutritionState
import se.pusslet.feature.meals.recipes.RecipeDraft
import se.pusslet.feature.meals.recipes.RecipeEditorViewModel
import se.pusslet.feature.meals.recipes.RecipeError
import se.pusslet.feature.meals.recipes.RecipeLibraryViewModel
import se.pusslet.feature.meals.recipes.RecipeMessage
import se.pusslet.feature.meals.recipes.RecipeViewModel
import se.pusslet.testing.TestGraph
import se.pusslet.testing.collectInBackground
import se.pusslet.testing.recordMessages
import se.pusslet.testing.viewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecipeViewModelsTest {
    private val url = "https://recept.example.se/kramig-kycklinggryta"
    private val page = """
        <html><head><script type="application/ld+json">
        {"@context":"https://schema.org","@type":"Recipe","name":"Krämig kycklinggryta","recipeYield":"4 portioner",
         "totalTime":"PT35M","recipeIngredient":["600 g kycklingfilé","2 dl matlagningsgrädde","1 gul lök"],
         "recipeInstructions":[{"@type":"HowToStep","text":"Stek kycklingen."},{"@type":"HowToStep","text":"Häll i grädden."}]}
        </script></head><body></body></html>
    """.trimIndent()

    /** The page is fetched on Ktor's own dispatcher, outside the test scheduler. */
    private suspend fun RecipeEditorViewModel.awaitFetched() = state.first { !it.fetching }

    private fun editor(g: TestGraph) = RecipeEditorViewModel(g.recipes, g.importer, g.ids)

    private fun recipeVm(g: TestGraph) =
        RecipeViewModel(g.recipes, g.meals, g.shopping, g.family, g.mealShopping, g.nutrition, g.sessions, g.clock, g.zone)

    @Test
    fun sharedLinkIsImportedAndSavedWithItsSource() = viewModelTest {
        val g = TestGraph()
        g.pages[url] = page
        val vm = editor(g)
        vm.start(recipeId = null, link = true, url = "Den här måste vi testa! $url", paste = false)
        val s = vm.awaitFetched()
        assertEquals(EditorStage.Form, s.stage)
        assertEquals("Krämig kycklinggryta", s.draft.title)
        assertEquals(35, s.draft.minutes.toInt())
        assertEquals(listOf("kycklingfilé", "matlagningsgrädde", "gul lök"), s.draft.ingredients.map { it.name })
        vm.save()
        val id = assertNotNull(vm.state.value.savedId)
        val saved = assertNotNull(g.recipes.get(id))
        assertEquals(url, saved.sourceUrl)
        assertEquals(2, saved.steps.size)
        assertEquals(4, saved.servings)
    }

    @Test
    fun pageWithoutRecipeDataFallsBackToPastedText() = viewModelTest {
        val g = TestGraph()
        g.pages[url] = "<html><body><h1>Gryta</h1></body></html>"
        val vm = editor(g)
        vm.start(null, link = true, url = url, paste = false)
        assertEquals(ImportProblem.NoRecipeData, vm.awaitFetched().problem)
        vm.showPaste()
        assertEquals(EditorStage.Paste, vm.state.value.stage)
        vm.setPasteText("Gryta\n4 portioner\nIngredienser\n500 g högrev\n2 morötter\nGör så här\nBryn köttet.\nKoka i två timmar.")
        vm.split()
        val s = vm.state.value
        assertEquals(EditorStage.Form, s.stage)
        assertEquals("Gryta", s.draft.title)
        assertEquals(2, s.draft.ingredients.size)
        assertEquals(2, s.draft.steps.size)
        assertEquals(url, s.draft.sourceUrl, "the link is kept as the source")
        assertEquals(2 to 2, s.pasteResult)
    }

    @Test
    fun unreachableAndInvalidLinksAreExplained() = viewModelTest {
        val g = TestGraph()
        val vm = editor(g)
        vm.start(null, link = true, url = null, paste = false)
        vm.setUrl("inte en länk")
        vm.fetch()
        assertEquals(ImportProblem.InvalidUrl, vm.awaitFetched().problem)
        vm.setUrl("https://finns.inte.example/recept")
        vm.fetch()
        assertEquals(ImportProblem.NotReachable, vm.awaitFetched().problem)
    }

    @Test
    fun editingKeepsIngredientsGroupsAndConfirmedMatches() = viewModelTest {
        val g = TestGraph()
        val original = Recipe(
            "r1",
            "Köttbullar",
            4,
            listOf(
                IngredientParser.parse("500 g blandfärs", "Bullar").copy(foodNumber = 42),
                IngredientParser.parse("1 ägg", "Bullar"),
                IngredientParser.parse("2 dl grädde", "Sås"),
                IngredientParser.parse("1 ½ msk soja", "Sås"),
            ),
            steps = listOf("Blanda.", "Stek."),
        )
        g.recipes.save(original)
        val vm = editor(g)
        vm.start("r1", link = false, url = null, paste = false)
        assertEquals("Bullar:\n500 g blandfärs\n1 ägg\nSås:\n2 dl grädde\n1 ½ msk soja", vm.state.value.draft.ingredientsText)
        vm.edit { it.copy(title = "Mormors köttbullar") }
        vm.save()
        val saved = assertNotNull(g.recipes.get("r1"))
        assertEquals("Mormors köttbullar", saved.title)
        assertEquals(original.ingredients, saved.ingredients)
        assertEquals(false, vm.state.value.isNew)
    }

    @Test
    fun aRecipeNeedsAName() = viewModelTest {
        val g = TestGraph()
        val vm = editor(g)
        vm.start(null, false, null, false)
        vm.save()
        assertEquals(setOf(RecipeError.NameRequired), vm.state.value.errors)
        assertEquals(null, vm.state.value.savedId)
    }

    @Test
    fun ingredientLinesRoundTrip() {
        val draft = RecipeDraft(ingredientsText = "2 st ägg\n½ tsk salt\n1,5 dl mjölk, ljummen")
        val text = RecipeDraft.ingredientLines(draft.ingredients)
        assertEquals("2 ägg\n½ tsk salt\n1 ½ dl mjölk, ljummen", text)
    }

    @Test
    fun recipeScalesPlansAndGoesOnTheList() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.shopping.saveList(ShoppingList("l1", "Veckohandling"))
        g.recipes.save(Recipe("r1", "Pannkakor", 4, listOf("3 dl vetemjöl", "6 dl mjölk", "3 ägg").map { IngredientParser.parse(it) }))
        val vm = recipeVm(g)
        collectInBackground(vm.state)
        val messages = recordMessages(vm.messages)
        vm.load("r1")
        assertEquals(4, vm.state.value.servings)
        vm.setServings(8)
        val milk = vm.state.value.ingredients.first { it.name == "mjölk" }
        assertEquals(12.0, milk.quantity, "1,2 l is not a nice number, 12 dl is")
        assertEquals(MeasureUnit.Dl, milk.unit)

        vm.planOn(g.today)
        val entry = g.meals.getWeek(g.today.isoWeek).entry(g.today, MealSlot.Dinner)
        assertEquals("r1", entry?.recipeId)
        assertEquals(8, entry?.servings)

        vm.addToList("l1")
        val items = g.shopping.getItems("l1").associateBy { it.name }
        assertEquals(6.0, items.getValue("ägg").quantity)
        assertEquals(listOf(RecipeMessage.Planned(g.today), RecipeMessage.AddedToList(3, "Veckohandling")), messages)
    }

    @Test
    fun nutritionMatchesCanBeCorrected() = viewModelTest {
        val g = TestGraph()
        g.foods.foods += listOf(Food(1, "Grädde 40% fett"), Food(2, "Matlagningsgrädde 15% fett"))
        g.foods.values[1] = NutrientValues(energyKcal = 370.0, fat = 40.0)
        g.foods.values[2] = NutrientValues(energyKcal = 160.0, fat = 15.0)
        g.recipes.save(Recipe("r1", "Sås", 2, listOf(IngredientParser.parse("2 dl grädde"))))
        val vm = recipeVm(g)
        collectInBackground(vm.state)
        vm.load("r1")
        vm.loadNutrition()
        val ready = assertIs<NutritionState.Ready>(vm.state.value.nutrition)
        assertTrue(ready.summary.complete)
        assertEquals(1, ready.matches.single().food?.number, "best guess: plain cream")
        // 200 ml * 1.0 g/ml = 200 g at 370 kcal/100 g, two portions.
        assertEquals(370.0, ready.summary.perPortion.energyKcal)

        vm.openMatch("grädde")
        val candidates = assertNotNull(vm.state.value.match?.candidates)
        assertTrue(candidates.any { it.number == 2 })
        vm.confirmMatch("grädde", Food(2, "Matlagningsgrädde 15% fett"))
        assertEquals(2, g.recipes.get("r1")?.ingredients?.single()?.foodNumber)
        assertEquals(2, g.recipes.ingredientMappings.first()["grädde"])
        val after = assertIs<NutritionState.Ready>(vm.state.value.nutrition)
        assertEquals(160.0, after.summary.perPortion.energyKcal)
    }

    @Test
    fun offlineNutritionSaysSo() = viewModelTest {
        val g = TestGraph()
        g.foods.online = false
        g.recipes.save(Recipe("r1", "Sås", 2, listOf(IngredientParser.parse("2 dl grädde"))))
        val vm = recipeVm(g)
        collectInBackground(vm.state)
        vm.load("r1")
        vm.loadNutrition()
        assertEquals(NutritionState.Offline, vm.state.value.nutrition)
    }

    @Test
    fun libraryFiltersOnTextTagsAndFavourites() = viewModelTest {
        val g = TestGraph()
        g.recipes.save(Recipe("a", "Tacos", tags = setOf(RecipeTag.Quick, RecipeTag.KidFriendly)))
        g.recipes.save(
            Recipe("b", "Linssoppa", ingredients = listOf(IngredientParser.parse("2 dl röda linser")), tags = setOf(RecipeTag.Vegetarian)),
        )
        g.recipes.save(Recipe("c", "Lasagne", favorite = true))
        val vm = RecipeLibraryViewModel(g.recipes, g.sessions)
        collectInBackground(vm.state)
        assertEquals(listOf("c", "b", "a"), vm.state.value.recipes.map { it.id }, "favourites first, then by name")
        vm.setQuery("linser")
        assertEquals(listOf("b"), vm.state.value.recipes.map { it.id }, "ingredients are searched too")
        vm.setQuery("")
        vm.toggleTag(RecipeTag.Quick)
        assertEquals(listOf("a"), vm.state.value.recipes.map { it.id })
        vm.toggleTag(RecipeTag.Quick)
        vm.toggleFavorites()
        assertEquals(listOf("c"), vm.state.value.recipes.map { it.id })
    }
}
