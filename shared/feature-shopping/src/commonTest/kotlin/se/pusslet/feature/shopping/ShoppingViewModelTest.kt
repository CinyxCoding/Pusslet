package se.pusslet.feature.shopping

import kotlinx.coroutines.launch
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.ListKind
import se.pusslet.core.model.Role
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.model.ShoppingPresence
import se.pusslet.core.model.Store
import se.pusslet.core.units.MeasureUnit
import se.pusslet.data.db.PurchaseLogEntity
import se.pusslet.data.sources.ProductInfo
import se.pusslet.data.sync.Collections
import se.pusslet.testing.TestGraph
import se.pusslet.testing.TestGraph.Companion.JOHAN
import se.pusslet.testing.collectInBackground
import se.pusslet.testing.recordMessages
import se.pusslet.testing.viewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class ShoppingViewModelTest {
    private fun vm(g: TestGraph) = ShoppingViewModel(g.shopping, g.family, g.prefs, g.products, g.sessions, g.ids, g.clock, tickMillis = 0)

    private suspend fun TestGraph.withList(storeId: String? = null) {
        seedFamily()
        shopping.saveList(ShoppingList("l1", "Veckohandling", storeId = storeId))
        shopping.saveList(ShoppingList("l2", "Apoteket", ListKind.Other, sortIndex = 1))
    }

    @Test
    fun quickAddSplitsClassifiesAndSortsByAisle() = viewModelTest {
        val g = TestGraph()
        g.withList()
        val vm = vm(g)
        collectInBackground(vm.state)
        vm.addText("mjölk, 2 gurka och pasta")
        val s = vm.state.value
        assertEquals(listOf(Aisle.Produce, Aisle.Dairy, Aisle.Pantry), s.groups.map { it.aisle }, "walking order of a Swedish store")
        val cucumber = s.groups.first().items.single()
        assertEquals("Gurka", cucumber.name)
        assertEquals(2.0, cucumber.quantity)
        assertEquals(3, s.remaining)
        // Typing milk again does not add a second line.
        vm.addText("Mjölk")
        assertEquals(3, vm.state.value.remaining)
    }

    @Test
    fun aStoreHasItsOwnOrderAndCorrectionsAreLearned() = viewModelTest {
        val g = TestGraph()
        g.shopping.saveStore(
            Store(
                "s1",
                "Willys",
                listOf(Aisle.Dairy, Aisle.Produce) + Aisle.defaultOrder.filter { it != Aisle.Dairy && it != Aisle.Produce },
            ),
        )
        g.withList(storeId = "s1")
        val vm = vm(g)
        collectInBackground(vm.state)
        vm.addText("mjölk, gurka, tandkräm")
        assertEquals("Willys", vm.state.value.store?.name)
        assertEquals(listOf(Aisle.Dairy, Aisle.Produce), vm.state.value.groups.take(2).map { it.aisle })

        val toothpaste = vm.state.value.groups.flatMap { it.items }.first { it.name == "Tandkräm" }
        vm.saveItem(toothpaste.copy(aisle = Aisle.Pharmacy, price = 24.9))
        assertEquals(Aisle.Pharmacy, vm.state.value.groups.flatMap { it.items }.first { it.name == "Tandkräm" }.aisle)
        assertEquals(24.9, vm.state.value.total)
        vm.selectList("l2")
        vm.addText("tandkräm")
        assertEquals(Aisle.Pharmacy, vm.state.value.groups.single().aisle, "the family's correction is remembered")
    }

    @Test
    fun checkingDropsIntoTheTrolleyAndCanBeUndone() = viewModelTest {
        val g = TestGraph()
        g.withList()
        val vm = vm(g)
        collectInBackground(vm.state)
        val messages = recordMessages(vm.messages)
        vm.addText("bröd, smör")
        val bread = vm.state.value.groups.flatMap { it.items }.first { it.name == "Bröd" }
        vm.setChecked(bread, true)
        assertEquals(1, vm.state.value.remaining)
        assertEquals("Bröd", vm.state.value.checked.single().name)
        assertEquals(TestGraph.SARA, vm.state.value.checked.single().checkedBy)
        val checked = messages.single() as ShoppingMessage.Checked
        vm.restore(checked.item)
        assertEquals(2, vm.state.value.remaining)

        vm.setChecked(bread, true)
        vm.clearChecked()
        assertTrue(vm.state.value.checked.isEmpty())
        assertEquals(1, vm.state.value.remaining)
    }

    @Test
    fun deleteCanBeUndone() = viewModelTest {
        val g = TestGraph()
        g.withList()
        val vm = vm(g)
        collectInBackground(vm.state)
        val messages = recordMessages(vm.messages)
        vm.addText("ägg")
        vm.delete(vm.state.value.groups.single().items.single())
        assertEquals(0, vm.state.value.remaining)
        vm.restore((messages.single() as ShoppingMessage.Deleted).item)
        assertEquals("Ägg", vm.state.value.groups.single().items.single().name)
    }

    @Test
    fun staplesAreSuggestedDismissedAndAdded() = viewModelTest {
        val g = TestGraph()
        g.withList()
        val now = g.clock.now()
        listOf(16, 12, 8, 4).forEach { daysAgo ->
            g.purchases.insert(
                PurchaseLogEntity(
                    familyId = "f-test",
                    normalizedName = "mjölk",
                    displayName = "Mjölk",
                    boughtAt = (
                        now -
                            daysAgo.days
                    ).toEpochMilliseconds(),
                ),
            )
        }
        val vm = vm(g)
        collectInBackground(vm.state)
        val staple = vm.state.value.staples.single()
        assertEquals("Mjölk", staple.name)
        assertEquals(4.days, staple.typicalInterval)

        vm.dismissStaple(staple)
        assertTrue(vm.state.value.staples.isEmpty())
        g.prefs.set(se.pusslet.data.repository.Prefs.DismissedStaples, emptyMap())
        assertEquals(1, vm.state.value.staples.size)
        vm.addStaple(staple)
        assertTrue(vm.state.value.staples.isEmpty(), "on the list now")
        assertEquals("Mjölk", vm.state.value.groups.single().items.single().name)

        vm.selectList("l2")
        assertTrue(vm.state.value.staples.isEmpty(), "no groceries suggested on the pharmacy list")
    }

    @Test
    fun presenceShowsWhoIsShoppingAndExpires() = viewModelTest {
        val g = TestGraph()
        g.withList()
        val vm = vm(g)
        collectInBackground(vm.state)
        vm.startShopping()
        assertTrue(vm.state.value.iAmShopping)
        val now = g.clock.now()
        g.docs.put(Collections.Presence, JOHAN, ShoppingPresence(JOHAN, "l1", now, now + 30.minutes), ShoppingPresence.serializer())
        assertEquals(listOf("Sara", "Johan").sorted(), vm.state.value.shoppers.map { it.person.name }.sorted())
        vm.stopShopping()
        assertFalse(vm.state.value.iAmShopping)

        g.clock.advance(31.minutes)
        vm.addText("kaffe") // any change re-evaluates
        assertTrue(vm.state.value.shoppers.isEmpty(), "Johan's sign has expired")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun othersAdditionsAreAnnouncedInStoreMode() = viewModelTest {
        val g = TestGraph()
        g.withList()
        val vm = vm(g)
        collectInBackground(vm.state)
        val messages = recordMessages(vm.messages)
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { vm.watchForAdditions() }
        vm.addText("bananer") // mine: not announced
        g.shopping.saveItem(ShoppingItem("x", "l1", "Kaffe", aisle = Aisle.Breakfast, addedBy = JOHAN))
        val m = messages.filterIsInstance<ShoppingMessage.AddedByOther>().single()
        assertEquals("Johan", m.person.name)
        assertEquals("Kaffe", m.name)
    }

    @Test
    fun scannedBarcodesBecomeItemsOrAskForAName() = viewModelTest {
        val g = TestGraph()
        g.withList()
        g.products.products["7310865004703"] = ProductInfo("7310865004703", "Mellanmjölk", "Arla", "1,5 l", listOf("Dairies", "Milks"))
        val vm = vm(g)
        collectInBackground(vm.state)
        vm.onBarcode("7310865004703")
        val milk = vm.state.value.groups.single().items.single()
        assertEquals("Mellanmjölk", milk.name)
        assertEquals(Aisle.Dairy, milk.aisle)
        assertEquals("Arla · 1,5 l", milk.note)
        assertEquals("7310865004703", milk.barcode)

        vm.onBarcode("0000000000000")
        assertEquals("0000000000000", vm.state.value.unknownBarcode)
        vm.nameUnknownBarcode("diskmedel")
        assertNull(vm.state.value.unknownBarcode)
        assertEquals(Aisle.Household, vm.state.value.groups.flatMap { it.items }.first { it.name == "Diskmedel" }.aisle)
    }

    @Test
    fun listsCanBeCreatedChangedAndDeleted() = viewModelTest {
        val g = TestGraph()
        g.withList()
        val vm = vm(g)
        collectInBackground(vm.state)
        vm.createList("Biltema", ListKind.Other)
        assertEquals("Biltema", vm.state.value.list?.name)
        vm.updateList(vm.state.value.list!!.copy(name = "Bauhaus")) // test code: the list was just created
        assertEquals("Bauhaus", vm.state.value.list?.name)
        vm.deleteList(vm.state.value.list!!.id) // test code
        assertEquals("Veckohandling", vm.state.value.list?.name)
        assertEquals(2, vm.state.value.lists.size)
    }

    @Test
    fun helpersCanShopButNotManageLists() = viewModelTest {
        val g = TestGraph(role = Role.Caregiver, memberId = TestGraph.BENGT)
        g.withList()
        val vm = vm(g)
        collectInBackground(vm.state)
        assertFalse(vm.state.value.canManage)
        vm.addText("1 kg potatis")
        assertEquals(MeasureUnit.Kg, vm.state.value.groups.single().items.single().unit)
    }
}
