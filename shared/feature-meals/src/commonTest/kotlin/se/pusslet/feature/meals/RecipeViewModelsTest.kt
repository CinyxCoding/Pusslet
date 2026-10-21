package se.pusslet.feature.meals

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeTag
import se.pusslet.core.model.Role
import se.pusslet.core.nutrition.Food
import se.pusslet.core.recipes.IngredientParser
import se.pusslet.core.time.isoWeek
import se.pusslet.core.units.MeasureUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeViewModelsTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.eager() = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    private fun recipe(id: String, title: String, vararg lines: String, minutes: Int? = null, tags: Set<RecipeTag> = emptySet()) =
        Recipe(id, title, 4, ingredients = lines.map { IngredientParser.parse(it) }, totalMinutes = minutes, tags = tags)

    @Test
    fun librarySearchesTitlesAndIngredientsAndFiltersTags() = runTest {
        val world = TestWorld()
        world.recipes.save(recipe("r1", "Köttfärssås", "500 g nötfärs", "1 burk krossade tomater", minutes = 30))
        world.recipes.save(recipe("r2", "Linsgryta", "2 dl röda linser", tags = setOf(RecipeTag.Vegetarian)))
        world.recipes.save(recipe("r3", "Lasagne", "500 g nötfärs", minutes = 90).copy(favorite = true))
        val vm = RecipeLibraryViewModel(world.recipes, world.sessions, world.ids)
        eager().launch { vm.state.collect {} }

        assertEquals(listOf("Lasagne", "Köttfärssås", "Linsgryta"), vm.state.value.recipes.map { it.title }, "favourites first")
        vm.setQuery("nötfärs")
        assertEquals(setOf("r1", "r3"), vm.state.value.recipes.map { it.id }.toSet())
        vm.setQuery("")
        vm.toggleTag(RecipeTag.Quick)
        assertEquals(listOf("r1"), vm.state.value.recipes.map { it.id }, "30 minutes counts as quick")
        vm.toggleTag(RecipeTag.Quick)
        vm.toggleTag(RecipeTag.Vegetarian)
        assertEquals(listOf("r2"), vm.state.value.recipes.map { it.id })
    }

    @Test
    fun anEmptyLibraryOffersANewRecipeToParentsOnly() = runTest {
        val world = TestWorld()
        val vm = RecipeLibraryViewModel(world.recipes, world.sessions, world.ids)
        eager().launch { vm.state.collect {} }
        with(vm.state.value) {
            assertEquals(0, total, "\"Inga recept än\" with the share tip")
            assertTrue(canEdit, "\"Nytt recept\" for parents")
        }
        world.recipes.save(recipe("r1", "Pannkakor", "3 ägg"))
        assertEquals(1, vm.state.value.total, "the empty state goes away with the first recipe")

        val helper = TestWorld(role = Role.Caregiver)
        val helperVm = RecipeLibraryViewModel(helper.recipes, helper.sessions, helper.ids)
        eager().launch { helperVm.state.collect {} }
        assertEquals(0, helperVm.state.value.total)
        assertFalse(helperVm.state.value.canEdit, "helpers get the sentence only")
    }

    @Test
    fun pastedTextBecomesANewRecipe() = runTest {
        val world = TestWorld()
        val vm = RecipeEditorViewModel(world.recipes, world.ids)
        vm.load(null, "https://www.example.se/recept/pannkakor")
        assertTrue(vm.state.value.isNew)
        assertEquals("example.se", vm.state.value.form.sourceName)

        vm.paste("Pannkakor\n6 portioner\nIngredienser\n3 ägg\n6 dl mjölk\nGör så här\n1. Vispa.\n2. Stek.")
        val form = vm.state.value.form
        assertEquals("Pannkakor", form.title)
        assertEquals("6", form.servings)
        vm.save()

        val saved = world.recipes.get(assertNotNull(vm.state.value.savedId))
        assertEquals("Pannkakor", saved?.title)
        assertEquals(6, saved?.servings)
        assertEquals(listOf("ägg", "mjölk"), saved?.ingredients?.map { it.name })
        assertEquals(listOf("Vispa.", "Stek."), saved?.steps)
        assertEquals("https://www.example.se/recept/pannkakor", saved?.sourceUrl)
    }

    @Test
    fun editingKeepsConfirmedFoodMatches() = runTest {
        val world = TestWorld()
        val original = recipe("r1", "Köttfärssås", "500 g nötfärs").let { r ->
            r.copy(ingredients = r.ingredients.map { it.copy(foodNumber = 1) })
        }
        world.recipes.save(original)
        val vm = RecipeEditorViewModel(world.recipes, world.ids)
        vm.load("r1")
        assertEquals("500 g nötfärs", vm.state.value.form.ingredients)
        vm.update { it.copy(ingredients = "600 g nötfärs\n1 gul lök") }
        vm.save()
        val saved = assertNotNull(world.recipes.get("r1"))
        assertEquals(600.0, saved.ingredients.first().quantity)
        assertEquals(1, saved.ingredients.first().foodNumber)
        assertEquals(null, saved.ingredients[1].foodNumber)
    }

    private fun TestScope.details(world: TestWorld): RecipeDetailsViewModel {
        val vm = RecipeDetailsViewModel(
            world.recipes,
            world.nutrition,
            world.adder,
            world.meals,
            world.sessions,
            world.clock,
            world.zone,
            world.ids,
        )
        eager().launch { vm.state.collect {} }
        return vm
    }

    @Test
    fun servingsScaleTheIngredients() = runTest {
        val world = TestWorld()
        world.recipes.save(recipe("r1", "Köttfärssås", "500 g nötfärs", "1 gul lök", "2 dl grädde"))
        val vm = details(world)
        vm.load("r1")
        assertEquals(4, vm.state.value.servings)
        vm.setServings(6)
        val scaled = vm.state.value.ingredients.associateBy { it.name }
        assertEquals(750.0, scaled.getValue("nötfärs").quantity)
        assertEquals(1.5, scaled.getValue("gul lök").quantity)
        assertEquals(3.0, scaled.getValue("grädde").quantity)
        assertEquals(MeasureUnit.Dl, scaled.getValue("grädde").unit)
    }

    @Test
    fun nutritionPerPortionFromTheFoodDatabase() = runTest {
        val world = TestWorld()
        world.recipes.save(recipe("r1", "Färs", "500 g nötfärs"))
        val vm = details(world)
        vm.load("r1")
        val ready = assertIs<NutritionState.Ready>(vm.state.value.nutrition)
        assertEquals(250.0, ready.summary.perPortion.energyKcal ?: 0.0, 0.5, "500 g × 200 kcal/100 g over 4 portions")
        assertEquals(1, ready.summary.matchedIngredients)

        world.foods.online = false
        vm.setServings(2)
        assertIs<NutritionState.Unavailable>(vm.state.value.nutrition)
    }

    @Test
    fun nutritionCanBeRetriedOnceOnline() = runTest {
        val world = TestWorld()
        world.foods.online = false
        world.recipes.save(recipe("r1", "Färs", "500 g nötfärs"))
        val vm = details(world)
        vm.load("r1")
        assertIs<NutritionState.Unavailable>(vm.state.value.nutrition)

        vm.retryNutrition()
        assertIs<NutritionState.Unavailable>(vm.state.value.nutrition, "still offline")

        world.foods.online = true
        vm.retryNutrition()
        val ready = assertIs<NutritionState.Ready>(vm.state.value.nutrition)
        assertEquals(250.0, ready.summary.perPortion.energyKcal ?: 0.0, 0.5)
    }

    @Test
    fun confirmingAFoodMatchIsRemembered() = runTest {
        val world = TestWorld()
        world.recipes.save(recipe("r1", "Färs", "500 g nötfärs"))
        val vm = details(world)
        vm.load("r1")
        val ingredient = assertNotNull(vm.state.value.recipe).ingredients.single()
        vm.startMatching(ingredient)
        val candidates = assertNotNull(vm.match.value?.candidates)
        assertEquals(Food(1, "Nötfärs rå"), candidates.first(), "raw before fried")
        vm.confirmMatch(Food(3, "Nötfärs stekt"))
        assertEquals(mapOf("nötfärs" to 3), world.recipes.ingredientMappings.first())
        val ready = assertIs<NutritionState.Ready>(vm.state.value.nutrition)
        assertEquals(312.5, ready.summary.perPortion.energyKcal ?: 0.0, 0.5, "now uses the confirmed food")
    }

    @Test
    fun addToShoppingListAndPlan() = runTest {
        val world = TestWorld()
        world.recipes.save(recipe("r1", "Köttfärssås", "500 g nötfärs", "1 burk krossade tomater", "salt"))
        val vm = details(world)
        val messages = mutableListOf<RecipeMessage>()
        eager().launch { vm.messages.collect { messages += it } }
        vm.load("r1")

        vm.addToShoppingList()
        val added = assertIs<RecipeMessage.AddedToList>(messages.last())
        assertEquals(listOf("Nötfärs", "Krossade tomater"), added.outcome.names)
        val listId = world.shopping.lists.first().single().id
        assertEquals(2, world.shopping.getItems(listId).size)

        val friday = LocalDate(2026, 10, 16)
        vm.setServings(2)
        vm.planOn(friday)
        assertIs<RecipeMessage.Planned>(messages.last())
        val entry = world.meals.getWeek(friday.isoWeek).entry(friday, MealSlot.Dinner)
        assertEquals("r1", entry?.recipeId)
        assertEquals(2, entry?.servings)
    }

    @Test
    fun deletingClosesTheScreen() = runTest {
        val world = TestWorld()
        world.recipes.save(recipe("r1", "Färs", "500 g nötfärs"))
        val vm = details(world)
        vm.load("r1")
        vm.delete()
        assertTrue(vm.deleted.value)
        assertEquals(null, world.recipes.get("r1"))
    }

    @Test
    fun planningFromTheRecipeCanBeUndone() = runTest {
        val world = TestWorld()
        world.recipes.save(recipe("r1", "Köttfärssås", "500 g nötfärs"))
        val friday = LocalDate(2026, 10, 16)
        world.meals.setEntry(MealEntry(friday, title = "Pizza"))
        val vm = details(world)
        val messages = mutableListOf<RecipeMessage>()
        eager().launch { vm.messages.collect { messages += it } }
        vm.load("r1")

        vm.planOn(friday)
        val planned = assertIs<RecipeMessage.Planned>(messages.last())
        assertEquals("r1", world.meals.getWeek(friday.isoWeek).entry(friday)?.recipeId)
        vm.undoPlan(assertNotNull(planned.undo))
        assertEquals("Pizza", world.meals.getWeek(friday.isoWeek).entry(friday)?.title, "Ångra brings back Friday's pizza")
    }

    @Test
    fun theEditorKnowsWhatIsNotSaved() = runTest {
        val world = TestWorld()
        world.recipes.save(recipe("r1", "Köttfärssås", "500 g nötfärs"))
        val vm = RecipeEditorViewModel(world.recipes, world.ids)
        vm.load("r1")
        assertFalse(vm.state.value.hasChanges, "just opened")
        vm.update { it.copy(title = "Köttfärssås med pasta") }
        assertTrue(vm.state.value.hasChanges, "Back asks first")
        vm.update { it.copy(title = "Köttfärssås") }
        assertFalse(vm.state.value.hasChanges, "back to what was saved")

        vm.update { it.copy(steps = "Stek färsen.") }
        vm.save()
        assertFalse(vm.state.value.hasChanges, "saved")

        val new = RecipeEditorViewModel(world.recipes, world.ids)
        new.load(null, "https://www.ica.se/recept/pannkakor")
        assertFalse(new.state.value.hasChanges, "a link alone is not a change")
        new.paste("Pannkakor\n3 ägg")
        assertTrue(new.state.value.hasChanges)
    }

    @Test
    fun theSourceIsShownAsItsHost() {
        assertEquals("ica.se", sourceHost("https://www.ica.se/recept/pannkakor-123/"))
        assertEquals("koket.se", sourceHost("http://koket.se/x?y=1"))
        assertEquals(null, sourceHost("ica.se/recept"))
    }
}
