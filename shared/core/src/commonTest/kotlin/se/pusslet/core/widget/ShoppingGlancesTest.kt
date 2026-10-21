package se.pusslet.core.widget

import se.pusslet.core.model.Aisle
import se.pusslet.core.model.ListKind
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.model.Store
import se.pusslet.core.units.MeasureUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShoppingGlancesTest {
    private val week = ShoppingList("l-vecka", "Veckohandling")
    private val party = ShoppingList("l-kalas", "Kalas", kind = ListKind.Other)

    @Test
    fun theListHandlaShowsIsTheOne() {
        assertEquals(party, ShoppingGlances.activeList(listOf(party, week), "l-kalas"), "chosen on this phone")
        assertEquals(week, ShoppingGlances.activeList(listOf(party, week), "l-borta"), "else the first grocery list")
        assertEquals(party, ShoppingGlances.activeList(listOf(party), null))
        assertNull(ShoppingGlances.activeList(emptyList(), null), "no list is made for a widget")
    }

    @Test
    fun whatIsLeftInTheStoresOrder() {
        val items = listOf(
            ShoppingItem("i-1", "l-vecka", "mjölk", quantity = 2.0, unit = MeasureUnit.L, aisle = Aisle.Dairy),
            ShoppingItem("i-2", "l-vecka", "bananer", aisle = Aisle.Produce),
            ShoppingItem("i-3", "l-vecka", "bröd", aisle = Aisle.Bakery, checked = true),
            ShoppingItem("i-4", "l-kalas", "ballonger"),
        )
        assertEquals(listOf("i-2", "i-1"), ShoppingGlances.from(week, items).items.map { it.id }, "fruit first by default")
        val dairyFirst = Store("s-1", "Hörnbutiken", aisleOrder = listOf(Aisle.Dairy, Aisle.Produce))
        val glance = ShoppingGlances.from(week, items, listOf(dairyFirst), defaultStoreId = "s-1")
        assertEquals(listOf("2 l mjölk", "bananer"), glance.items.map { it.text })
        assertEquals("Veckohandling", glance.listName)
    }
}
