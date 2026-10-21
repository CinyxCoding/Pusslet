package se.pusslet.feature.shopping

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
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.PantryItem
import se.pusslet.core.model.PantryLocation
import se.pusslet.core.model.Role
import se.pusslet.core.shopping.BestBefore
import se.pusslet.core.shopping.PantryRules
import se.pusslet.core.units.MeasureUnit
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.db.PurchaseLogDao
import se.pusslet.data.db.PurchaseLogEntity
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import se.pusslet.data.sources.ProductInfo
import se.pusslet.data.sources.ProductLookup
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PantryViewModelTest {
    private object NoPurchases : PurchaseLogDao {
        override suspend fun insert(entity: PurchaseLogEntity) = Unit

        override suspend fun since(familyId: String, since: Long) = emptyList<PurchaseLogEntity>()

        override suspend fun deleteFamily(familyId: String) = Unit
    }

    private class Products(private val known: Map<String, ProductInfo>) : ProductLookup {
        val asked = mutableListOf<String>()

        override suspend fun product(barcode: String): ProductInfo? {
            asked += barcode
            return known[barcode]
        }
    }

    /** Wednesday 21 October 2026, 08:00 in Stockholm. */
    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-10-21T06:00:00Z")
    }
    private val zone = TimeZone.of("Europe/Stockholm")
    private val today = LocalDate(2026, 10, 21)
    private val sessions = FixedSessionProvider(Session(familyId = "f", memberId = "m-sara", uid = "u", role = Role.Parent))
    private val docs = DocumentStore(InMemorySyncLocalStore(), sessions, clock)
    private val ids = SequentialIdGenerator()
    private val family = FamilyRepository(docs, sessions, LocalOnlyFamilyRemote, LocalOnlyAuthRepository(), ids, clock)
    private val shopping = ShoppingRepository(docs, NoPurchases, sessions, clock)
    private val products = Products(
        mapOf(
            "7310865004703" to ProductInfo("7310865004703", "Mellanmjölk", "Arla", "1 l", listOf("en:dairies", "en:milks")),
            "7300000000001" to ProductInfo("7300000000001", "Gröna ärtor", "Findus", null, listOf("en:frozen-foods")),
        ),
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(): Pair<PantryViewModel, MutableList<PantryMessage>> {
        val eager = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val vm = PantryViewModel(shopping, family, sessions, ids, products, clock, zone)
        val messages = mutableListOf<PantryMessage>()
        eager.launch { vm.state.collect {} }
        eager.launch { vm.messages.collect { messages += it } }
        return vm to messages
    }

    private fun PantryViewModel.item(name: String) = state.value.items.single { it.name == name }

    @Test
    fun typedItemsAreGroupedByPlaceWithAGuessedPlace() = runTest {
        val (vm, _) = viewModel()
        vm.add("1 kg mjöl, 2 l mjölk, ris")
        val state = vm.state.value
        assertEquals(listOf(PantryLocation.Pantry, PantryLocation.Fridge), state.groups.map { it.location })
        assertEquals(listOf("Mjöl", "Ris"), state.groups.first().items.map { it.name })
        assertEquals(2.0, vm.item("Mjölk").quantity)
        assertTrue(vm.item("Ris").amountUnknown, "no amount: finns hemma")
        assertEquals(today, state.today, "from the injected clock and zone")

        // The same thing again updates it instead of adding a second line.
        vm.add("3 l mjölk")
        assertEquals(3.0, vm.item("Mjölk").quantity)
        assertEquals(3, vm.state.value.items.size)
    }

    @Test
    fun slutKeepsTheItemLastInItsPlaceAndAddingItAgainBringsItBack() = runTest {
        val (vm, _) = viewModel()
        vm.add("ris, bönor, pasta")
        vm.setInStock(vm.item("Bönor"), false)
        assertEquals(listOf("Pasta", "Ris", "Bönor"), vm.state.value.groups.single().items.map { it.name })
        assertFalse(vm.item("Bönor").inStock)
        assertTrue(shopping.pantryAtHome.first().none { it.name == "Bönor" }, "meal suggestions only see what is at home")

        vm.setInStock(vm.item("Bönor"), true)
        assertTrue(vm.item("Bönor").inStock)
        vm.setInStock(vm.item("Bönor"), false)
        vm.add("2 burkar bönor")
        assertTrue(vm.item("Bönor").inStock)
        assertEquals(MeasureUnit.Burk, vm.item("Bönor").unit)
    }

    @Test
    fun placeAmountAndBestBeforeCanBeEditedAndSoonIsAReminder() = runTest {
        val (vm, _) = viewModel()
        vm.add("2 l mjölk")
        val milk = vm.item("Mjölk")
        vm.save(milk.copy(location = PantryLocation.Freezer, quantity = null, unit = null, bestBefore = LocalDate(2026, 10, 24)))
        val edited = vm.item("Mjölk")
        assertEquals(PantryLocation.Freezer, edited.location)
        assertTrue(edited.amountUnknown)
        assertEquals(BestBefore.Soon, PantryRules.bestBefore(edited, assertIs<LocalDate>(vm.state.value.today)))

        vm.save(edited.copy(bestBefore = LocalDate(2026, 10, 25)))
        assertEquals(BestBefore.Later, PantryRules.bestBefore(vm.item("Mjölk"), today))
    }

    @Test
    fun removingOffersUndoThatPutsItBackExactly() = runTest {
        val (vm, messages) = viewModel()
        vm.add("2 l mjölk")
        val milk = vm.item("Mjölk").copy(bestBefore = LocalDate(2026, 10, 30))
        vm.save(milk)
        vm.remove(vm.item("Mjölk"))
        assertTrue(vm.state.value.isEmpty)
        val removed = assertIs<PantryMessage.Removed>(messages.last())
        vm.restore(removed.item)
        assertEquals(milk, vm.item("Mjölk"))
    }

    @Test
    fun scanningLooksTheProductUpLikeTheShoppingList() = runTest {
        val (vm, messages) = viewModel()
        vm.scanned(null)
        assertTrue(messages.isEmpty(), "cancelled")

        vm.scanned("7310865004703")
        assertEquals("Arla Mellanmjölk", assertIs<PantryMessage.Added>(messages.last()).name)
        assertEquals(PantryLocation.Fridge, vm.item("Arla Mellanmjölk").location)
        vm.scanned("7300000000001")
        assertEquals(PantryLocation.Freezer, vm.item("Findus Gröna ärtor").location, "from the Open Food Facts category")

        // Scanning a package that ran out: at home again, without asking Open Food Facts.
        vm.setInStock(vm.item("Arla Mellanmjölk"), false)
        val asked = products.asked.size
        vm.scanned("7310865004703")
        assertTrue(vm.item("Arla Mellanmjölk").inStock)
        assertEquals(asked, products.asked.size)

        vm.scanned("123")
        assertEquals("123", assertIs<PantryMessage.UnknownProduct>(messages.last()).barcode)
        vm.addNamedProduct("123", "Kokosmjölk")
        assertEquals("123", vm.item("Kokosmjölk").barcode)
    }

    @Test
    fun helpersOnlyRead() = runTest {
        sessions.update { it.copy(role = Role.Caregiver) }
        shopping.savePantry(PantryItem("p1", "Ris"))
        val (vm, _) = viewModel()
        assertFalse(vm.state.value.canManage)
        assertEquals(listOf("Ris"), vm.state.value.items.map { it.name })
    }
}
