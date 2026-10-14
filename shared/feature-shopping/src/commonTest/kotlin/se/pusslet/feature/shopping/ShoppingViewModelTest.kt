package se.pusslet.feature.shopping

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.ListKind
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.Member
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.Role
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.model.ShoppingPresence
import se.pusslet.core.model.Store
import se.pusslet.core.recipes.IngredientParser
import se.pusslet.core.time.isoWeek
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.db.PreferenceDao
import se.pusslet.data.db.PreferenceEntity
import se.pusslet.data.db.PurchaseLogDao
import se.pusslet.data.db.PurchaseLogEntity
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import se.pusslet.data.sources.ProductInfo
import se.pusslet.data.sources.ProductLookup
import se.pusslet.data.sync.Collections
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import se.pusslet.data.usecase.MealShopping
import se.pusslet.data.usecase.ShoppingAdder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingViewModelTest {
    private class FakePreferenceDao : PreferenceDao {
        private val values = MutableStateFlow<Map<String, String>>(emptyMap())

        override fun observe(key: String): Flow<String?> = values.map { it[key] }

        override suspend fun get(key: String): String? = values.value[key]

        override suspend fun put(entity: PreferenceEntity) {
            values.value = values.value + (entity.key to entity.value)
        }

        override suspend fun remove(key: String) {
            values.value = values.value - key
        }

        override suspend fun clear() {
            values.value = emptyMap()
        }
    }

    private class FakePurchases : PurchaseLogDao {
        val log = mutableListOf<PurchaseLogEntity>()

        override suspend fun insert(entity: PurchaseLogEntity) {
            log += entity
        }

        override suspend fun since(familyId: String, since: Long) = log.filter { it.familyId == familyId && it.boughtAt >= since }

        override suspend fun deleteFamily(familyId: String) = log.clear()
    }

    private object NoProducts : ProductLookup {
        override suspend fun product(barcode: String): ProductInfo? = null
    }

    /** Tuesday 13 October 2026, 18:00 in Stockholm. */
    private val now = Instant.parse("2026-10-13T16:00:00Z")
    private val clock = object : Clock {
        override fun now() = now
    }
    private val zone = TimeZone.of("Europe/Stockholm")
    private val sessions = FixedSessionProvider(Session(familyId = "f", memberId = "m-sara", uid = "u", role = Role.Parent))
    private val docs = DocumentStore(InMemorySyncLocalStore(), sessions, clock)
    private val ids = SequentialIdGenerator()
    private val purchases = FakePurchases()
    private val prefs = PreferencesRepository(FakePreferenceDao())
    private val family = FamilyRepository(docs, sessions, LocalOnlyFamilyRemote, LocalOnlyAuthRepository(), ids, clock)
    private val shopping = ShoppingRepository(docs, purchases, sessions, clock)
    private val meals = MealRepository(docs)
    private val recipes = RecipeRepository(docs)
    private val adder = ShoppingAdder(shopping, NoProducts, prefs, sessions, ids, clock)
    private val mealShopping = MealShopping(meals, recipes, shopping, family, sessions, ids, clock)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun seed() {
        family.saveMember(Member("m-sara", "Sara"))
        family.saveMember(Member("m-johan", "Johan"))
        shopping.saveList(ShoppingList("l-vecka", "Veckohandling", sortIndex = 0))
        shopping.saveList(ShoppingList("l-apotek", "Apoteket", ListKind.Other, sortIndex = 1))
    }

    /** The view model plus every message it sends. Collectors run eagerly so `state.value` is always current. */
    private fun TestScope.viewModel(): Pair<ShoppingViewModel, MutableList<ShoppingMessage>> {
        val eager = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val vm = ShoppingViewModel(shopping, adder, mealShopping, family, prefs, sessions, ids, clock, zone, eager)
        val messages = mutableListOf<ShoppingMessage>()
        eager.launch { vm.state.collect {} }
        eager.launch { vm.messages.collect { messages += it } }
        return vm to messages
    }

    @Test
    fun firstGroceryListIsActiveAndItemsFollowTheStoreOrder() = runTest {
        seed()
        // This store has dairy before fruit.
        shopping.saveStore(Store("s1", "ICA", listOf(Aisle.Dairy, Aisle.Produce)))
        shopping.saveList(ShoppingList("l-vecka", "Veckohandling", storeId = "s1"))
        val (vm, _) = viewModel()
        vm.add("bananer, mjölk")

        val state = vm.state.value
        assertEquals("l-vecka", state.list?.id)
        assertEquals("ICA", state.store?.name)
        assertEquals(listOf(Aisle.Dairy, Aisle.Produce), state.groups.map { it.aisle })
        assertEquals(2, state.openCount)
        assertTrue(state.canManage)
    }

    @Test
    fun addingReportsAndCanBeUndone() = runTest {
        seed()
        val (vm, messages) = viewModel()
        vm.add("2 l mjölk")
        vm.add("1 l mjölk, bröd")
        val added = assertIs<ShoppingMessage.Added>(messages.last())
        assertEquals(listOf("Mjölk", "Bröd"), added.outcome.names)
        assertEquals(3.0, vm.state.value.groups.flatMap { it.items }.first { it.name == "Mjölk" }.quantity)

        vm.undoAdd(added.outcome)
        val items = vm.state.value.groups.flatMap { it.items }
        assertEquals(listOf("Mjölk"), items.map { it.name })
        assertEquals(2.0, items.single().quantity)

        vm.add("mjölk")
        assertIs<ShoppingMessage.AlreadyOnList>(messages.last())
    }

    @Test
    fun addingWithoutAnyListCreatesOne() = runTest {
        val (vm, _) = viewModel()
        vm.add("kaffe")
        val state = vm.state.value
        assertEquals(1, state.lists.size)
        assertEquals("Kaffe", state.groups.single().items.single().name)
        assertEquals(state.list?.id, prefs.get(Prefs.ActiveShoppingList))
    }

    @Test
    fun selectingAnotherListShowsItsItems() = runTest {
        seed()
        val (vm, _) = viewModel()
        vm.selectList("l-apotek")
        vm.add("alvedon")
        assertEquals("Apoteket", vm.state.value.list?.name)
        assertEquals(Aisle.Pharmacy, vm.state.value.groups.single().aisle)
        assertTrue(vm.state.value.suggestions.isEmpty(), "no staple suggestions on non-grocery lists")
    }

    @Test
    fun tickingOffInStoreModeOffersUndoAndTheLastOneCelebrates() = runTest {
        seed()
        val (vm, messages) = viewModel()
        vm.add("mjölk, bröd")
        val (milk, bread) = vm.state.value.groups.flatMap { it.items }.sortedBy { it.name }.let { it[1] to it[0] }

        vm.setChecked(milk, true, inStore = true)
        val inCart = assertIs<ShoppingMessage.InCart>(messages.last())
        assertTrue(inCart.inStore)
        assertEquals(listOf("Mjölk"), vm.state.value.checked.map { it.name })
        assertEquals(1, purchases.log.size, "ticking off feeds the purchase history")

        vm.restore(inCart.item)
        assertEquals(2, vm.state.value.openCount)

        vm.setChecked(bread, true, inStore = true)
        vm.setChecked(vm.state.value.groups.single().items.single(), true, inStore = true)
        assertIs<ShoppingMessage.AllDone>(messages.last())
        assertEquals(0, vm.state.value.openCount)

        vm.clearChecked()
        assertEquals(2, assertIs<ShoppingMessage.Cleared>(messages.last()).items.size)
        assertTrue(vm.state.value.isEmpty)
    }

    @Test
    fun tickingOffOnThePlanningListAlsoOffersUndo() = runTest {
        seed()
        val (vm, messages) = viewModel()
        vm.add("mjölk, bröd")
        val milk = vm.state.value.groups.flatMap { it.items }.first { it.name == "Mjölk" }

        vm.setChecked(milk, true)
        val ticked = assertIs<ShoppingMessage.InCart>(messages.last())
        assertFalse(ticked.inStore, "\"Mjölk bockad\", not \"i vagnen\"")
        assertEquals(listOf("Mjölk"), vm.state.value.checked.map { it.name })

        vm.restore(ticked.item)
        assertTrue(vm.state.value.checked.isEmpty())
        assertEquals(Aisle.Dairy, vm.state.value.groups.first { g -> g.items.any { it.name == "Mjölk" } }.aisle, "same aisle again")

        // The last item on the planning list still offers "Ångra" instead of celebrating.
        vm.state.value.groups.flatMap { it.items }.forEach { vm.setChecked(it, true) }
        assertIs<ShoppingMessage.InCart>(messages.last())
        assertTrue(messages.none { it is ShoppingMessage.AllDone })
    }

    @Test
    fun clearingTickedItemsCanBeUndone() = runTest {
        seed()
        val (vm, messages) = viewModel()
        vm.add("mjölk, bröd, ost")
        vm.state.value.groups.flatMap { it.items }.filter { it.name != "Ost" }.forEach { vm.setChecked(it, true) }
        assertEquals(2, vm.state.value.checked.size)

        vm.clearChecked()
        val cleared = assertIs<ShoppingMessage.Cleared>(messages.last())
        assertEquals(setOf("Mjölk", "Bröd"), cleared.items.map { it.name }.toSet())
        assertTrue(vm.state.value.checked.isEmpty())
        assertEquals(1, vm.state.value.openCount)

        vm.restoreCleared(cleared.items)
        assertEquals(setOf("Mjölk", "Bröd"), vm.state.value.checked.map { it.name }.toSet(), "back as ticked off")
        assertEquals(1, vm.state.value.openCount)

        // Nothing ticked off: nothing to clear and no snackbar.
        vm.state.value.checked.forEach { vm.remove(it) }
        val before = messages.size
        vm.clearChecked()
        assertEquals(before, messages.size)
    }

    @Test
    fun aNewListIsEmptyRatherThanAllBought() = runTest {
        seed()
        val (vm, _) = viewModel()
        with(vm.state.value) {
            assertTrue(isEmpty, "\"Listan är tom\"")
            assertFalse(allBought, "not \"Allt är handlat\"")
            assertFalse(canStartShopping, "no \"Jag handlar nu\" without anything to buy")
        }

        vm.add("mjölk")
        assertTrue(vm.state.value.canStartShopping)

        vm.setChecked(vm.state.value.groups.single().items.single(), true)
        with(vm.state.value) {
            assertFalse(isEmpty)
            assertTrue(allBought)
            assertFalse(canStartShopping)
        }
    }

    @Test
    fun removeAndRestore() = runTest {
        seed()
        val (vm, messages) = viewModel()
        vm.add("ost")
        val item = vm.state.value.groups.single().items.single()
        vm.remove(item)
        assertTrue(vm.state.value.isEmpty)
        vm.restore(assertIs<ShoppingMessage.Removed>(messages.last()).item)
        assertEquals("Ost", vm.state.value.groups.single().items.single().name)
    }

    @Test
    fun correctedAisleIsRememberedForNextTime() = runTest {
        seed()
        val (vm, _) = viewModel()
        vm.add("havremjölk")
        val item = vm.state.value.groups.single().items.single()
        vm.save(item.copy(aisle = Aisle.Breakfast))
        assertEquals(Aisle.Breakfast, vm.state.value.groups.single().aisle)

        vm.remove(vm.state.value.groups.single().items.single())
        vm.add("Havremjölk")
        assertEquals(Aisle.Breakfast, vm.state.value.groups.single().aisle)
    }

    @Test
    fun staplesAreSuggestedFromHistoryAndCanBeDismissed() = runTest {
        seed()
        // Milk bought every 4 days, last time 4 days ago.
        listOf(16, 12, 8, 4).forEach { daysAgo ->
            val at = (now - daysAgo.days).toEpochMilliseconds()
            purchases.insert(PurchaseLogEntity(familyId = "f", normalizedName = "mjölk", displayName = "Mjölk", boughtAt = at))
        }
        val (vm, _) = viewModel()
        assertEquals(listOf("Mjölk"), vm.state.value.suggestions.map { it.name })

        vm.addSuggestion(vm.state.value.suggestions.single())
        assertTrue(vm.state.value.suggestions.isEmpty(), "on the list now")

        vm.remove(vm.state.value.groups.single().items.single())
        assertEquals(1, vm.state.value.suggestions.size)
        vm.dismissSuggestions()
        assertTrue(vm.state.value.suggestions.isEmpty())
    }

    @Test
    fun othersShoppingAreShownUntilTheirPresenceExpires() = runTest {
        seed()
        docs.put(
            Collections.Presence,
            "m-johan",
            ShoppingPresence("m-johan", "l-vecka", now - 10.minutes, now + 50.minutes),
            ShoppingPresence.serializer(),
        )
        docs.put(
            Collections.Presence,
            "m-old",
            ShoppingPresence("m-old", "l-vecka", now - 2.days, now - 1.days),
            ShoppingPresence.serializer(),
        )
        val (vm, _) = viewModel()
        val shopper = vm.state.value.shoppers.single()
        assertEquals("Johan", shopper.person?.name)
        assertEquals("Veckohandling", shopper.listName)
        assertFalse(vm.state.value.iAmShopping)
    }

    @Test
    fun storeModePresenceStartsAndEndsWhenTheScreenGoesAway() = runTest {
        seed()
        val (vm, _) = viewModel()
        vm.startShopping()
        assertTrue(vm.state.value.iAmShopping)
        vm.stopShopping()
        assertTrue(shopping.presence.first().isEmpty())
    }

    @Test
    fun helpersCannotManageLists() = runTest {
        sessions.update { it.copy(role = Role.Caregiver) }
        seed()
        val (vm, _) = viewModel()
        assertFalse(vm.state.value.canManage)
    }

    @Test
    fun listManagement() = runTest {
        seed()
        val (vm, _) = viewModel()
        vm.createList("Biltema", ListKind.Other)
        assertEquals("Biltema", vm.state.value.list?.name)
        assertEquals(2, vm.state.value.list?.sortIndex)

        vm.renameList("Biltema Kungens kurva")
        assertEquals("Biltema Kungens kurva", vm.state.value.list?.name)

        shopping.saveStore(Store("s1", "Willys"))
        vm.setStore("s1")
        assertEquals("Willys", vm.state.value.store?.name)

        vm.deleteList()
        assertEquals("l-vecka", vm.state.value.list?.id)
        assertEquals(2, vm.state.value.lists.size)
    }

    @Test
    fun mealPlanPreviewThenGenerate() = runTest {
        seed()
        recipes.save(
            Recipe("r1", "Tacos", 4, listOf("500 g nötfärs", "1 gurka", "1 påse tacokrydda").map { IngredientParser.parse(it) }),
        )
        val today = now.toLocalDateTime(zone).date
        meals.setEntry(MealEntry(today, recipeId = "r1", title = "Tacos"))
        val (vm, messages) = viewModel()

        vm.previewMealPlan(0)
        val preview = vm.mealPlanPreview.value
        assertEquals(today.isoWeek, preview?.week)
        assertEquals(3, preview?.plan?.add?.size)

        vm.generateFromMealPlan()
        assertNull(vm.mealPlanPreview.value)
        assertIs<ShoppingMessage.Generated>(messages.last())
        assertEquals(3, vm.state.value.openCount)
    }
}
