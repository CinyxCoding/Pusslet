package se.pusslet.data.usecase

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import se.pusslet.core.model.Child
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.Member
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.Role
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.recipes.IngredientParser
import se.pusslet.core.time.isoWeek
import se.pusslet.core.units.MeasureUnit
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.MockHttp
import se.pusslet.data.MockHttp.Companion.ok
import se.pusslet.data.db.PurchaseLogDao
import se.pusslet.data.db.PurchaseLogEntity
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class MealDataTest {
    private object NoPurchases : PurchaseLogDao {
        override suspend fun insert(entity: PurchaseLogEntity) = Unit

        override suspend fun since(familyId: String, since: Long) = emptyList<PurchaseLogEntity>()

        override suspend fun deleteFamily(familyId: String) = Unit
    }

    private object FixedClock : Clock {
        override fun now() = Instant.parse("2026-10-11T18:00:00Z")
    }

    private val monday = LocalDate(2026, 10, 12)
    private val sessions = FixedSessionProvider(Session(familyId = "f", memberId = "m-sara", uid = "u", role = Role.Parent))
    private val docs = DocumentStore(InMemorySyncLocalStore(), sessions, FixedClock)
    private val ids = SequentialIdGenerator()
    private val family = FamilyRepository(docs, sessions, LocalOnlyFamilyRemote, LocalOnlyAuthRepository(), ids, FixedClock)
    private val meals = MealRepository(docs)
    private val recipes = RecipeRepository(docs)
    private val shopping = ShoppingRepository(docs, NoPurchases, sessions, FixedClock)
    private val mealShopping = MealShopping(meals, recipes, shopping, family, sessions, ids, FixedClock)

    private suspend fun seedFamily() {
        family.saveMember(Member("m-sara", "Sara"))
        family.saveMember(Member("m-johan", "Johan"))
        family.saveMember(Member("m-bengt", "Bengt", Role.Caregiver))
        family.saveChild(Child("c-ella", "Ella"))
        family.saveChild(Child("c-simon", "Simon"))
    }

    @Test
    fun weekPlanBecomesShoppingListAndRegeneratesCleanly() = runTest {
        seedFamily()
        assertEquals(4, mealShopping.householdSize(), "morfar does not eat here every day")
        val tacos = listOf("500 g nötfärs", "1 gurka", "2 tomater", "1 påse tacokrydda")
        recipes.save(Recipe("r1", "Tacos", 4, tacos.map { IngredientParser.parse(it) }))
        recipes.save(Recipe("r2", "Tomatsoppa", 2, listOf("2 tomater", "1 gul lök", "salt").map { IngredientParser.parse(it) }))
        meals.setEntry(MealEntry(monday, recipeId = "r1", title = "Tacos"))
        meals.setEntry(MealEntry(monday.plusDays(2), recipeId = "r2", title = "Tomatsoppa"))

        val first = mealShopping.generate("list", monday.isoWeek)
        assertTrue(first.update.isEmpty())
        val items = shopping.getItems("list").associateBy { it.name }
        assertEquals(6.0, items.getValue("tomater").quantity, "2 for tacos + 4 for soup scaled from 2 to 4 portions")
        assertEquals(MeasureUnit.St, items.getValue("tomater").unit)
        assertTrue("salt" !in items)

        // Someone adds milk by hand; soup is swapped for something without tomatoes.
        shopping.saveItem(ShoppingItem("milk", "list", "Mjölk", 1.0, MeasureUnit.L))
        meals.clearEntry(monday.plusDays(2), se.pusslet.core.model.MealSlot.Dinner)
        mealShopping.generate("list", monday.isoWeek)
        val after = shopping.getItems("list").associateBy { it.name }
        assertEquals(2.0, after.getValue("tomater").quantity)
        assertTrue("gul lök" !in after)
        assertEquals(1.0, after.getValue("Mjölk").quantity)

        assertTrue(mealShopping.preview("list", monday.isoWeek).isEmpty, "nothing left to change")
    }

    @Test
    fun importsRecipeFromSharedText() = runTest {
        val page = """<html><script type="application/ld+json">{"@type":"Recipe","name":"Fiskgratäng","recipeYield":"4 portioner",
            "recipeIngredient":["600 g torskfilé","4 dl matlagningsgrädde"],"recipeInstructions":"Sätt ugnen på 200 grader."}</script></html>"""
        val http = MockHttp { request ->
            if ("example.se" in request.url.host) ok(page, "text/html") else respond("", HttpStatusCode.NotFound)
        }
        val importer = RecipeImporter(http.client, SequentialIdGenerator("r"))

        val found = assertIs<RecipeImportResult.Found>(importer.import("Den här blev bra! https://www.example.se/recept/fiskgratang."))
        assertEquals("Fiskgratäng", found.imported.recipe.title)
        assertEquals("r1", found.imported.recipe.id)
        assertEquals("https://www.example.se/recept/fiskgratang", http.requests.single())

        assertIs<RecipeImportResult.InvalidUrl>(importer.import("bara text"))
        assertIs<RecipeImportResult.NotReachable>(importer.import("https://other.se/x"))
    }

    private fun LocalDate.plusDays(n: Int) = LocalDate.fromEpochDays(toEpochDays() + n)
}
