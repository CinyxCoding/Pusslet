package se.pusslet.feature.meals

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeCollection
import se.pusslet.core.model.RecipeTag
import se.pusslet.core.model.Role
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeCollectionsViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.eager() = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    private suspend fun TestWorld.seed() {
        recipes.save(Recipe("r-tacos", "Tacos", totalMinutes = 30, tags = setOf(RecipeTag.KidFriendly)))
        recipes.save(Recipe("r-pesto", "Pasta med pesto", totalMinutes = 15, favorite = true))
        recipes.save(Recipe("r-soup", "Linssoppa", totalMinutes = 40, tags = setOf(RecipeTag.Vegetarian)))
    }

    private fun TestScope.library(world: TestWorld): RecipeLibraryViewModel {
        val vm = RecipeLibraryViewModel(world.recipes, world.sessions, world.ids)
        eager().launch { vm.state.collect {} }
        return vm
    }

    private fun TestScope.details(world: TestWorld, id: String): RecipeDetailsViewModel {
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
        vm.load(id)
        return vm
    }

    private fun RecipeLibraryViewModel.shown() = state.value.recipes.map { it.id }

    @Test
    fun createAddRemoveAndFilter() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = library(world)

        assertTrue(vm.createCollection(" Kalas "))
        val party = assertNotNull(vm.state.value.collection, "a new collection is shown at once")
        assertEquals("Kalas", party.name)
        assertTrue(vm.state.value.collectionIsEmpty, "\"Inga recept i Kalas än\"")
        assertTrue(vm.shown().isEmpty())

        val tacos = details(world, "r-tacos")
        tacos.setInCollection(party.id, inside = true)
        val pesto = details(world, "r-pesto")
        pesto.setInCollection(party.id, inside = true)
        assertTrue(pesto.state.value.inCollection(pesto.state.value.collections.single()))
        assertFalse(vm.state.value.collectionIsEmpty)
        assertEquals(listOf("r-pesto", "r-tacos"), vm.shown(), "favourites first, inside the collection only")
        assertEquals(2, vm.state.value.collections.single().count)

        tacos.setInCollection(party.id, inside = false)
        assertEquals(listOf("r-pesto"), vm.shown())
        assertEquals(mapOf("r-pesto" to true), world.recipes.allCollections().single().recipeIds)

        vm.toggleCollection(party.id)
        assertNull(vm.state.value.collection, "a second tap shows every recipe")
        assertEquals(3, vm.shown().size)
    }

    @Test
    fun searchAndTagsStillWorkInsideACollection() = runTest {
        val world = TestWorld().apply { seed() }
        world.recipes.saveCollection(RecipeCollection("c1", "Vardagsfavoriter", mapOf("r-tacos" to true, "r-soup" to true)))
        val vm = library(world)
        vm.toggleCollection("c1")
        assertEquals(listOf("r-soup", "r-tacos"), vm.shown())
        vm.setQuery("lins")
        assertEquals(listOf("r-soup"), vm.shown())
        vm.setQuery("")
        vm.toggleTag(RecipeTag.KidFriendly)
        assertEquals(listOf("r-tacos"), vm.shown())
        vm.toggleTag(RecipeTag.Quick)
        assertEquals(listOf("r-tacos"), vm.shown(), "30 minutes counts as quick")
        assertFalse(vm.state.value.collectionIsEmpty, "a filter that matches nothing is not the collection's empty state")
        vm.setQuery("pesto")
        assertTrue(vm.shown().isEmpty(), "pesto is not in the collection")
    }

    @Test
    fun renameKeepsTheRecipesAndRefusesTakenNames() = runTest {
        val world = TestWorld().apply { seed() }
        world.recipes.saveCollection(RecipeCollection("c1", "Kalas", mapOf("r-tacos" to true)))
        world.recipes.saveCollection(RecipeCollection("c2", "Matlådor"))
        val vm = library(world)

        assertFalse(vm.renameCollection("c1", "matlådor"), "another collection has the name")
        assertFalse(vm.renameCollection("c1", "  "))
        assertFalse(vm.createCollection("KALAS"))
        assertTrue(vm.renameCollection("c1", "Barnkalas"))
        assertEquals(listOf("Barnkalas", "Matlådor"), vm.state.value.collections.map { it.collection.name })
        assertEquals(mapOf("r-tacos" to true), world.recipes.allCollections().first { it.id == "c1" }.recipeIds)
    }

    @Test
    fun deletingACollectionKeepsItsRecipes() = runTest {
        val world = TestWorld().apply { seed() }
        world.recipes.saveCollection(RecipeCollection("c1", "Kalas", mapOf("r-tacos" to true, "r-pesto" to true)))
        val vm = library(world)
        vm.toggleCollection("c1")
        vm.deleteCollection("c1")
        assertTrue(vm.state.value.collections.isEmpty())
        assertNull(vm.state.value.collection)
        assertEquals(3, vm.state.value.total)
        assertNotNull(world.recipes.get("r-tacos"))
        assertNotNull(world.recipes.get("r-pesto"))
    }

    @Test
    fun aDeletedRecipeLeavesItsCollections() = runTest {
        val world = TestWorld().apply { seed() }
        world.recipes.saveCollection(RecipeCollection("c1", "Kalas", mapOf("r-tacos" to true, "r-pesto" to true)))
        val vm = library(world)
        world.recipes.delete("r-tacos")
        assertEquals(1, vm.state.value.collections.single().count)
        vm.toggleCollection("c1")
        assertEquals(listOf("r-pesto"), vm.shown())
    }

    @Test
    fun aNewCollectionFromARecipeHoldsIt() = runTest {
        val world = TestWorld().apply { seed() }
        val vm = details(world, "r-soup")
        assertTrue(vm.createCollection("Matlådor"))
        assertFalse(vm.createCollection("matlådor"), "the name is taken")
        val made = vm.state.value.collections.single()
        assertEquals("Matlådor", made.name)
        assertTrue(vm.state.value.inCollection(made))
    }

    @Test
    fun helpersSeeCollectionsButCannotChangeThem() = runTest {
        val world = TestWorld(role = Role.Caregiver).apply { seed() }
        world.recipes.saveCollection(RecipeCollection("c1", "Kalas", mapOf("r-tacos" to true)))
        val vm = library(world)
        assertFalse(vm.state.value.canEdit, "no \"Ny samling\", no ⋮")
        vm.toggleCollection("c1")
        assertEquals(listOf("r-tacos"), vm.shown())
    }
}
