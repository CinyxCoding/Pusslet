package se.pusslet.core.shopping

import se.pusslet.core.model.Aisle
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.units.MeasureUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShoppingTextTest {
    private fun item(
        name: String,
        aisle: Aisle,
        quantity: Double? = null,
        unit: MeasureUnit? = null,
        note: String = "",
        checked: Boolean = false,
    ) = ShoppingItem("id-$name", "l", name, quantity, unit, note = note, aisle = aisle, checked = checked)

    @Test
    fun lineWithAmountAndNote() {
        assertEquals("1 ½ l Mjölk (laktosfri)", ShoppingText.line(item("Mjölk", Aisle.Dairy, 1.5, MeasureUnit.L, "laktosfri")))
        assertEquals("Bröd", ShoppingText.line(item("Bröd", Aisle.Bakery)))
    }

    @Test
    fun sharedTextFollowsStoreOrderAndSkipsTickedItems() {
        val items = listOf(
            item("Mjölk", Aisle.Dairy, 2.0, MeasureUnit.L),
            item("Gurka", Aisle.Produce, 2.0, MeasureUnit.St),
            item("Bananer", Aisle.Produce),
            item("Kaffe", Aisle.Breakfast, checked = true),
        )
        val text = ShoppingText.share("Veckohandling", ShoppingListGenerator.groupForStore(items)) { it.name }
        assertEquals("Veckohandling\n\nProduce\n• Bananer\n• 2 st Gurka\n\nDairy\n• 2 l Mjölk", text)
    }

    @Test
    fun offCategoriesMostSpecificFirst() {
        assertEquals(Aisle.Frozen, ProductCategories.aisle(listOf("en:plant-based-foods", "en:vegetables", "en:frozen-vegetables")))
        assertEquals(Aisle.Dairy, ProductCategories.aisle(listOf("dairies", "milks")))
        assertEquals(Aisle.Breakfast, ProductCategories.aisle(listOf("en:beverages", "en:green-teas")))
        assertEquals(Aisle.Meat, ProductCategories.aisle(listOf("en:meats", "en:beef-steaks")))
        assertNull(ProductCategories.aisle(emptyList()))
    }
}
