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
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.ListKind
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealKind
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Member
import se.pusslet.core.model.PantryItem
import se.pusslet.core.model.PantryLocation
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.Role
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.model.ShoppingPresence
import se.pusslet.core.model.Store
import se.pusslet.core.recipes.IngredientParser
import se.pusslet.core.shopping.AisleGroup
import se.pusslet.core.time.isoWeek
import se.pusslet.core.units.MeasureUnit
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
import se.pusslet.data.usecase.FirstTimeHints
import se.pusslet.data.usecase.MealShopping
import se.pusslet.data.usecase.ShoppingAdder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
    private val receipts = ShoppingReceipts()
    private val hints = FirstTimeHints(prefs)

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
        val vm = ShoppingViewModel(shopping, adder, mealShopping, meals, family, prefs, sessions, ids, clock, zone, eager, receipts, hints)
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
        assertEquals(today.isoWeek.days.filter { it >= today }.toSet(), preview?.days, "today to Sunday")
        assertEquals(3, preview?.plan?.add?.size)

        vm.generateFromMealPlan()
        assertNull(vm.mealPlanPreview.value)
        assertIs<ShoppingMessage.Generated>(messages.last())
        assertEquals(3, vm.state.value.openCount)
    }

    @Test
    fun storeModeOffersUndoOnlyForTheFirstTickOfAVisit() = runTest {
        seed()
        val (vm, messages) = viewModel()
        vm.add("mjölk, bröd, ost, kaffe")
        vm.startShopping()

        fun open(name: String) = vm.state.value.groups.flatMap { it.items }.first { it.name == name }

        vm.setChecked(open("Mjölk"), true, inStore = true)
        vm.setChecked(open("Bröd"), true, inStore = true)
        assertEquals(1, messages.count { it is ShoppingMessage.InCart }, "only the first tick of the visit offers Ångra")
        assertEquals("Mjölk", messages.filterIsInstance<ShoppingMessage.InCart>().single().item.name)

        // A tap in "I vagnen" puts an item back without any snackbar.
        vm.setChecked(vm.state.value.checked.first { it.name == "Bröd" }, false, inStore = true)
        assertEquals(3, vm.state.value.openCount)

        // The next visit offers it again; the planning list always does.
        vm.startShopping()
        vm.setChecked(open("Ost"), true, inStore = true)
        vm.setChecked(open("Kaffe"), true)
        assertEquals(listOf("Mjölk", "Ost", "Kaffe"), messages.filterIsInstance<ShoppingMessage.InCart>().map { it.item.name })
    }

    @Test
    fun emptyingTheCartHandsTheReceiptToTheList() = runTest {
        seed()
        val (list, _) = viewModel()
        val receiptsSeen = mutableListOf<ShoppingMessage.Cleared>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { list.storeReceipts.collect { receiptsSeen += it } }
        val (store, storeMessages) = viewModel()
        store.add("mjölk, bröd")
        store.startShopping()
        store.state.value.groups.flatMap { it.items }.forEach { store.setChecked(it, true, inStore = true) }
        assertIs<ShoppingMessage.AllDone>(storeMessages.last())

        // "Klar – töm vagnen": the cart is emptied and the list underneath gets "2 varor rensade · Ångra".
        store.finishShopping(clear = true)
        assertTrue(list.state.value.isEmpty)
        assertTrue(shopping.presence.first().isEmpty(), "no longer shopping")
        val cleared = receiptsSeen.single()
        assertEquals(setOf("Mjölk", "Bröd"), cleared.items.map { it.name }.toSet())
        assertTrue(storeMessages.none { it is ShoppingMessage.Cleared }, "store mode has closed; the list shows it")

        list.restoreCleared(cleared.items)
        assertEquals(2, list.state.value.checked.size)
    }

    @Test
    fun keepingTheTickedItemsLeavesThemOnTheList() = runTest {
        seed()
        val (list, _) = viewModel()
        val receiptsSeen = mutableListOf<ShoppingMessage.Cleared>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { list.storeReceipts.collect { receiptsSeen += it } }
        val (store, _) = viewModel()
        store.add("mjölk")
        store.startShopping()
        store.setChecked(store.state.value.groups.single().items.single(), true, inStore = true)

        store.finishShopping(clear = false)
        assertEquals(listOf("Mjölk"), list.state.value.checked.map { it.name }, "\"Behåll avbockade\"")
        assertTrue(receiptsSeen.isEmpty())
        assertTrue(shopping.presence.first().isEmpty())
    }

    @Test
    fun severalShoppersMergeIntoOneRow() = runTest {
        seed()
        family.saveMember(Member("m-bengt", "Bengt", role = Role.Caregiver))
        docs.put(
            Collections.Presence,
            "m-bengt",
            ShoppingPresence("m-bengt", "l-apotek", now - 5.minutes, now + 55.minutes),
            ShoppingPresence.serializer(),
        )
        docs.put(
            Collections.Presence,
            "m-johan",
            ShoppingPresence("m-johan", "l-vecka", now - 10.minutes, now + 50.minutes),
            ShoppingPresence.serializer(),
        )
        val (vm, _) = viewModel()
        val row = assertNotNull(vm.state.value.shoppersRow)
        assertEquals(listOf("Johan", "Bengt"), row.names, "one row, first to start first")
        assertEquals(listOf("Apoteket"), row.elsewhere.map { it.name }, "\"(Apoteket) · Byt\"")

        // "Byt" shows Apoteket; from there Veckohandling is the other list.
        vm.selectList(row.elsewhere.single().id)
        assertEquals(listOf("Veckohandling"), vm.state.value.shoppersRow?.elsewhere?.map { it.name })

        // Nobody shopping: no row at all.
        docs.delete(Collections.Presence, "m-bengt")
        docs.delete(Collections.Presence, "m-johan")
        assertNull(vm.state.value.shoppersRow)
    }

    @Test
    fun mealPlanPreviewGroupsWhatIsRemoved() = runTest {
        seed()
        recipes.save(Recipe("r1", "Tacos", 4, listOf("500 g nötfärs", "1 gurka", "1 påse tacokrydda").map { IngredientParser.parse(it) }))
        val today = now.toLocalDateTime(zone).date
        meals.setEntry(MealEntry(today, recipeId = "r1", title = "Tacos"))
        val (vm, _) = viewModel()
        vm.previewMealPlan(0)
        vm.generateFromMealPlan()
        assertEquals(3, vm.state.value.openCount)

        // Tacos is taken off the plan: what goes is listed under "Tas bort" by name, in name order.
        meals.clearEntry(today, MealSlot.Dinner)
        vm.previewMealPlan(0)
        val preview = assertNotNull(vm.mealPlanPreview.value)
        assertTrue(preview.plan.add.isEmpty())
        assertTrue(preview.plan.remove.isNotEmpty())
        assertEquals(preview.plan.remove.toSet(), preview.removed.map { it.id }.toSet())
        assertEquals(preview.removed.sortedBy { it.name.lowercase() }, preview.removed)
        val generated = vm.state.value.groups.flatMap { it.items }.map { it.name }.toSet()
        assertTrue(preview.removed.all { it.name in generated }, "${preview.removed.map { it.name }} / $generated")
    }

    @Test
    fun recipesMissingThisWeekShowACardUntilShoppedOrDismissed() = runTest {
        seed()
        recipes.save(Recipe("r1", "Tacos", 4, listOf("500 g nötfärs").map { IngredientParser.parse(it) }))
        recipes.save(Recipe("r2", "Lasagne", 4, listOf("1 paket lasagneplattor").map { IngredientParser.parse(it) }))
        val today = now.toLocalDateTime(zone).date
        val yesterday = today.minus(DatePeriod(days = 1))
        val thursday = today.plus(DatePeriod(days = 2))
        meals.setEntry(MealEntry(yesterday, recipeId = "r2", title = "Lasagne"))
        meals.setEntry(MealEntry(today, recipeId = "r1", title = "Tacos"))
        val (vm, _) = viewModel()
        assertEquals(listOf("Tacos"), vm.state.value.weekMissing?.titles, "only from today on")

        vm.dismissWeekMissing()
        assertNull(vm.state.value.weekMissing, "\"Inte nu\"")

        meals.setEntry(MealEntry(thursday, recipeId = "r2", title = "Lasagne"))
        assertEquals(listOf("Lasagne"), vm.state.value.weekMissing?.titles, "a newly planned recipe asks again")

        vm.previewMealPlan(0)
        vm.generateFromMealPlan()
        assertNull(vm.state.value.weekMissing, "on the list now")

        // Bought and cleared: the meals are dealt with and do not come back.
        vm.state.value.groups.flatMap { it.items }.forEach { vm.setChecked(it, true) }
        vm.clearChecked()
        assertTrue(vm.state.value.isEmpty)
        assertNull(vm.state.value.weekMissing)

        // Other lists never ask.
        vm.selectList("l-apotek")
        meals.setEntry(MealEntry(thursday, recipeId = "r1", title = "Tacos"))
        assertNull(vm.state.value.weekMissing)
    }

    @Test
    fun oneSuggestionCanBeWavedAway() = runTest {
        seed()
        listOf(16, 12, 8, 4).forEach { daysAgo ->
            val at = (now - daysAgo.days).toEpochMilliseconds()
            purchases.insert(PurchaseLogEntity(familyId = "f", normalizedName = "mjölk", displayName = "Mjölk", boughtAt = at))
            purchases.insert(PurchaseLogEntity(familyId = "f", normalizedName = "bröd", displayName = "Bröd", boughtAt = at))
        }
        val (vm, _) = viewModel()
        assertEquals(setOf("Mjölk", "Bröd"), vm.state.value.suggestions.map { it.name }.toSet())

        vm.dismissSuggestion(vm.state.value.suggestions.first { it.name == "Mjölk" })
        assertEquals(listOf("Bröd"), vm.state.value.suggestions.map { it.name })
    }

    @Test
    fun theMergeHintShowsInTheDemoFamilyOnlyAndStaysGoneAfterDismiss() = runTest {
        seed()
        val (vm, _) = viewModel()
        vm.add("mjölk")
        assertFalse(vm.state.value.showMergeHint, "a real family never sees the demo hint")

        sessions.update { it.copy(demo = true) }
        assertTrue(vm.state.value.showMergeHint, "\"Tips: mjölk två gånger blir en rad\"")

        vm.dismissMergeHint()
        assertFalse(vm.state.value.showMergeHint)
        // A new view model on the same phone (the next start of the app) does not bring it back.
        val (again, _) = viewModel()
        assertFalse(again.state.value.showMergeHint)
    }

    @Test
    fun theMergeHintNeverShowsOnAnEmptyListOrWhileShopping() = runTest {
        sessions.update { it.copy(demo = true) }
        seed()
        val (vm, _) = viewModel()
        assertTrue(vm.state.value.isEmpty)
        assertFalse(vm.state.value.showMergeHint, "the empty state explains how to type instead")

        vm.add("mjölk")
        assertTrue(vm.state.value.showMergeHint)
        vm.startShopping()
        assertFalse(vm.state.value.showMergeHint, "never in store mode")
        vm.stopShopping()
        assertTrue(vm.state.value.showMergeHint)
    }

    @Test
    fun theEmptyListOffersHandlaTillVeckanOnGroceryListsAndScanningEverywhere() = runTest {
        seed()
        val (vm, messages) = viewModel()
        with(vm.state.value) {
            assertTrue(isEmpty)
            assertTrue(canShopForWeek, "\"Handla till veckan\" on Veckohandling")
        }
        // "Handla till veckan" from the empty state previews the week like everywhere else.
        vm.previewMealPlan()
        val today = clock.now().toLocalDateTime(zone).date
        assertEquals(today, assertNotNull(vm.mealPlanPreview.value).days.min())
        vm.dismissMealPlanPreview()

        vm.selectList("l-apotek")
        with(vm.state.value) {
            assertTrue(isEmpty)
            assertFalse(canShopForWeek, "no meals on Apoteket")
        }
        // "Skanna streckkod": a cancelled scan does nothing, an unknown code asks for a name.
        vm.scanned(null)
        assertTrue(messages.isEmpty())
        vm.scanned("7310865004703")
        assertEquals("7310865004703", assertIs<ShoppingMessage.UnknownProduct>(messages.last()).barcode)
    }

    @Test
    fun chosenDaysPreviewLetsTheFamilyUntickAndNeverDuplicates() = runTest {
        seed()
        recipes.save(Recipe("r1", "Tacos", 4, listOf("500 g nötfärs", "1 gurka", "1 påse tacokrydda").map { IngredientParser.parse(it) }))
        recipes.save(Recipe("r2", "Gryta", 4, listOf("600 g kycklingfilé", "1 gurka", "3 dl ris").map { IngredientParser.parse(it) }))
        val today = now.toLocalDateTime(zone).date
        val nextMonday = today.isoWeek.next().monday
        meals.setEntry(MealEntry(today, recipeId = "r1", title = "Tacos"))
        meals.setEntry(MealEntry(nextMonday, recipeId = "r2", title = "Gryta"))
        meals.setEntry(MealEntry(today.plus(DatePeriod(days = 1)), recipeId = "r2", title = "Hämtmat", kind = MealKind.Takeaway))
        shopping.saveItem(ShoppingItem("manual", "l-vecka", "Kaffe", 1.0, MeasureUnit.Pkt))
        val (vm, messages) = viewModel()

        vm.previewMealPlan()
        var preview = assertNotNull(vm.mealPlanPreview.value)
        assertEquals(14, preview.window.size)
        assertEquals(today, preview.window.first())
        assertEquals(setOf("nötfärs", "gurka", "tacokrydda"), preview.choices.map { it.key }.toSet(), "takeaway adds nothing")

        // Next Monday is in the window: its meal joins, and the cucumbers merge into one line.
        vm.toggleMealPlanDay(nextMonday)
        preview = assertNotNull(vm.mealPlanPreview.value)
        assertTrue(nextMonday in preview.days)
        // Two people at home: half a cucumber per recipe, merged into one whole one from both meals.
        val cucumber = preview.plan.add.single { it.name == "gurka" }
        assertEquals(1.0, cucumber.quantity)
        assertEquals(setOf(today, nextMonday), cucumber.sources.mapNotNull { it.mealDate }.toSet())
        vm.toggleMealPlanDay(today.plus(DatePeriod(days = 20)))
        assertEquals(preview.days, assertNotNull(vm.mealPlanPreview.value).days, "outside the 14 days: ignored")

        // "Finns hemma?" → untick the rice: it stays in the sheet, unticked, and is not written.
        vm.setMealPlanIngredient("ris", included = false)
        preview = assertNotNull(vm.mealPlanPreview.value)
        assertFalse(preview.choices.single { it.key == "ris" }.included)
        assertTrue(preview.plan.add.none { it.name == "ris" })
        assertEquals(setOf("ris"), preview.excluded)
        vm.generateFromMealPlan()
        assertIs<ShoppingMessage.Generated>(messages.last())
        val names = vm.state.value.groups.flatMap { it.items }.map { it.name }
        assertEquals(setOf("Kaffe", "gurka", "kycklingfilé", "nötfärs", "tacokrydda"), names.toSet())
        assertEquals(names.size, names.toSet().size, "one line each")

        // The same days again: nothing to change, and the typed coffee is never touched.
        vm.previewMealPlan()
        vm.toggleMealPlanDay(nextMonday)
        vm.setMealPlanIngredient("ris", included = false)
        preview = assertNotNull(vm.mealPlanPreview.value)
        assertTrue(preview.plan.isEmpty, "${preview.plan}")
        assertTrue(preview.choices.none { it.key == "ris" && it.included })
        vm.dismissMealPlanPreview()
        assertNull(vm.mealPlanPreview.value)
    }

    @Test
    fun thePresetsPickTheRestOfTheWeekOrAllOfNextWeek() = runTest {
        seed()
        val (vm, _) = viewModel()
        val today = now.toLocalDateTime(zone).date
        vm.previewMealPlan(1)
        assertEquals(today.isoWeek.next().days.toSet(), assertNotNull(vm.mealPlanPreview.value).days)
        vm.previewMealPlan(0)
        assertEquals(today.isoWeek.days.filter { it >= today }.toSet(), assertNotNull(vm.mealPlanPreview.value).days)
    }

    @Test
    fun theListFromTheMealPlanAsksAboutWhatIsAtHomeAndNeverChangesThePantry() = runTest {
        seed()
        family.saveSettings { it.copy(usePantry = true) }
        recipes.save(
            Recipe(
                "r1",
                "Tacos",
                4,
                listOf("500 g nötfärs", "2 dl gräddfil", "1 påse tacokrydda").map {
                    IngredientParser.parse(it)
                },
            ),
        )
        val today = now.toLocalDateTime(zone).date
        meals.setEntry(MealEntry(today, recipeId = "r1", title = "Tacos"))
        val pantry = listOf(
            PantryItem("p1", "Tacokrydda"),
            PantryItem("p2", "Gräddfil", 3.0, MeasureUnit.Dl, PantryLocation.Fridge),
            PantryItem("p3", "Nötfärs", 1.0, MeasureUnit.Kg, PantryLocation.Freezer, inStock = false),
        )
        pantry.forEach { shopping.savePantry(it) }
        val (vm, _) = viewModel()

        vm.previewMealPlan()
        val preview = assertNotNull(vm.mealPlanPreview.value)
        assertTrue(preview.choices.single { it.key == "tacokrydda" }.askIfAtHome, "\"Finns hemma?\", ticked until the family says no")
        assertTrue(preview.choices.single { it.key == "tacokrydda" }.included)
        assertFalse(preview.choices.single { it.key == "nötfärs" }.askIfAtHome, "slut is not at home")
        assertEquals(listOf("gräddfil"), preview.coveredAtHome)

        vm.generateFromMealPlan()
        assertEquals(pantry.toSet(), shopping.pantry.first().toSet(), "the pantry is only read")
        vm.state.value.groups.flatMap { it.items }.forEach { vm.setChecked(it, true) }
        vm.clearChecked()
        assertEquals(pantry.toSet(), shopping.pantry.first().toSet(), "not even after shopping")
    }

    @Test
    fun handledMealsArePrunedAfterAWeek() {
        val today = LocalDate(2026, 10, 13)
        val keys = setOf("2026-10-01/r1", "2026-10-06/r2", "2026-10-15/r3", "broken")
        assertEquals(setOf("2026-10-06/r2", "2026-10-15/r3"), pruneHandled(keys, today))
    }

    @Test
    fun aTickedRowLingersInItsAisle() {
        val milk = ShoppingItem("i1", "l", "Mjölk", aisle = Aisle.Dairy)
        val bread = ShoppingItem("i2", "l", "Bröd", aisle = Aisle.Bakery, checked = true)
        val apples = ShoppingItem("i3", "l", "Äpplen", aisle = Aisle.Dairy, checked = true)
        val groups = listOf(AisleGroup(Aisle.Dairy, listOf(milk)))

        val shown = withLingering(groups, listOf(bread, apples, milk.copy(checked = true)), Aisle.defaultOrder)
        assertEquals(listOf(Aisle.Bakery, Aisle.Dairy), shown.map { it.aisle }, "store order")
        assertEquals(listOf("Mjölk", "Äpplen"), shown.last().items.map { it.name }, "name order, as before the tick")
        assertFalse(shown.last().items.first().checked, "an item that is open again shows as open")
        assertEquals(groups, withLingering(groups, emptyList(), Aisle.defaultOrder))
    }
}
