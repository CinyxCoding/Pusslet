package se.pusslet.data.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.Role
import se.pusslet.core.recipes.IngredientParser
import se.pusslet.core.units.MeasureUnit
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.FakePreferenceDao
import se.pusslet.data.db.PurchaseLogDao
import se.pusslet.data.db.PurchaseLogEntity
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import se.pusslet.data.sources.ProductInfo
import se.pusslet.data.sources.ProductLookup
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class ShoppingAdderTest {
    private object NoPurchases : PurchaseLogDao {
        override suspend fun insert(entity: PurchaseLogEntity) = Unit

        override suspend fun since(familyId: String, since: Long) = emptyList<PurchaseLogEntity>()

        override suspend fun deleteFamily(familyId: String) = Unit
    }

    private object FixedClock : Clock {
        override fun now() = Instant.parse("2026-10-13T16:00:00Z")
    }

    private class FakeProducts(private val known: Map<String, ProductInfo>) : ProductLookup {
        val asked = mutableListOf<String>()

        override suspend fun product(barcode: String): ProductInfo? {
            asked += barcode
            return known[barcode]
        }
    }

    private val sessions = FixedSessionProvider(Session(familyId = "f", memberId = "m-sara", uid = "u", role = Role.Parent))
    private val docs = DocumentStore(InMemorySyncLocalStore(), sessions, FixedClock)
    private val shopping = ShoppingRepository(docs, NoPurchases, sessions, FixedClock)
    private val products = FakeProducts(
        mapOf(
            "7310865004703" to ProductInfo("7310865004703", "Mellanmjölk 1,5%", "Arla", "1,5 l", listOf("dairies", "milks")),
            // The name tells nothing about the aisle; the category does.
            "7300400118003" to ProductInfo("7300400118003", "Kalaspuffar", null, "500 g", listOf("cereals", "breakfast-cereals")),
            "4000000000000" to ProductInfo("4000000000000", null, "Okänt", null, emptyList()),
        ),
    )
    private val prefs = PreferencesRepository(FakePreferenceDao())
    private val adder = ShoppingAdder(shopping, products, prefs, sessions, SequentialIdGenerator(), FixedClock)

    @Test
    fun typedItemsGetAislesAndMergeWithWhatIsThere() = runTest {
        val first = adder.addText("l", "2 l mjölk, bröd")
        assertEquals(2, first.created.size)
        assertEquals(listOf("Mjölk", "Bröd"), first.names)

        val second = adder.addText("l", "1 l mjölk, gurka")
        assertEquals(1, second.created.size, "only the cucumber is new")
        assertEquals(1, second.previous.size)

        val items = shopping.getItems("l").associateBy { it.name }
        assertEquals(3.0, items.getValue("Mjölk").quantity)
        assertEquals(MeasureUnit.L, items.getValue("Mjölk").unit)
        assertEquals(Aisle.Dairy, items.getValue("Mjölk").aisle)
        assertEquals(Aisle.Bakery, items.getValue("Bröd").aisle)
        assertEquals("m-sara", items.getValue("Gurka").addedBy)
    }

    @Test
    fun undoRemovesNewLinesAndRestoresMergedOnes() = runTest {
        adder.addText("l", "2 l mjölk")
        val outcome = adder.addText("l", "1 l mjölk, ost")
        adder.undo(outcome)
        val items = shopping.getItems("l")
        assertEquals(listOf("Mjölk"), items.map { it.name })
        assertEquals(2.0, items.single().quantity)
    }

    @Test
    fun familyCorrectionsDecideTheAisle() = runTest {
        shopping.rememberAisle("Havremjölk", Aisle.Breakfast)
        adder.addText("l", "havremjölk")
        assertEquals(Aisle.Breakfast, shopping.getItems("l").single().aisle)
    }

    @Test
    fun alreadyOnTheListReportsTheNameButChangesNothing() = runTest {
        adder.addText("l", "bananer")
        val again = adder.addText("l", "Bananer")
        assertTrue(again.isEmpty)
        assertEquals(listOf("Bananer"), again.names)
    }

    @Test
    fun scannedProductIsAddedWithBrandAndRememberedBarcode() = runTest {
        val result = adder.addScanned("l", "7310865004703")
        val item = shopping.getItems("l").single()
        assertEquals("Arla Mellanmjölk 1,5%", item.name)
        assertEquals("7310865004703", item.barcode)
        assertEquals(Aisle.Dairy, item.aisle)
        assertEquals(1, result.added.created.size)
    }

    @Test
    fun categoryDecidesWhenTheNameDoesNot() = runTest {
        adder.addScanned("l", "7300400118003")
        assertEquals(Aisle.Breakfast, shopping.getItems("l").single().aisle)
    }

    @Test
    fun scanningTheSamePackageAgainCountsUp() = runTest {
        adder.addScanned("l", "7310865004703")
        val again = adder.addScanned("l", "7310865004703")
        val item = shopping.getItems("l").single()
        assertEquals(2.0, item.quantity)
        assertEquals(MeasureUnit.St, item.unit)
        assertEquals(1, products.asked.size, "the second scan needs no lookup")
        adder.undo(again.added)
        assertNull(shopping.getItems("l").single().quantity)
    }

    @Test
    fun unknownOrNamelessProductsAddNothing() = runTest {
        assertTrue(adder.addScanned("l", "1234567890123").added.isEmpty)
        assertTrue(adder.addScanned("l", "4000000000000").added.isEmpty)
        assertTrue(shopping.getItems("l").isEmpty())

        adder.addNamed("l", "1234567890123", "Tändstickor")
        val item = shopping.getItems("l").single()
        assertEquals("Tändstickor", item.name)
        assertEquals("1234567890123", item.barcode)
    }

    @Test
    fun activeListIsCreatedOnceWhenTheFamilyHasNone() = runTest {
        val first = adder.activeListId()
        assertEquals(first, adder.activeListId())
        assertEquals("Veckohandling", shopping.lists.first().single().name)
    }

    @Test
    fun recipeGoesOnTheListScaledWithoutStaples() = runTest {
        val recipe = Recipe(
            "r1",
            "Pannkakor",
            servings = 4,
            ingredients = listOf("3 ägg", "6 dl mjölk", "2½ dl vetemjöl", "1 krm salt", "sylt till servering")
                .map { IngredientParser.parse(it) },
        )
        adder.addText("l", "1 l mjölk")
        val outcome = adder.addRecipe(recipe, servings = 8, listId = "l")
        val items = shopping.getItems("l").associateBy { it.name }
        assertEquals(setOf("Mjölk", "Ägg", "Vetemjöl"), items.keys, "salt and the optional jam are skipped")
        assertEquals(6.0, items.getValue("Ägg").quantity)
        assertEquals(2.2, items.getValue("Mjölk").quantity ?: 0.0, 0.001, "1 l + 12 dl, in the line's own unit")
        assertEquals(1, outcome.previous.size)
    }
}
