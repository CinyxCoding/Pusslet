package se.pusslet.core.shopping

import se.pusslet.core.model.Aisle
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.quickadd.ParsedItem
import se.pusslet.core.units.MeasureUnit
import se.pusslet.core.units.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ShoppingInputTest {
    @Test
    fun commaSeparatedListWithAmountsFirst() {
        val items = ShoppingInput.parse("2 l mjölk, bröd och 3 gurkor")
        assertEquals(listOf("Mjölk", "Bröd", "Gurkor"), items.map { it.name })
        assertEquals(Quantity(2.0, MeasureUnit.L), items[0].quantity)
        assertNull(items[1].quantity)
        assertEquals(Quantity(3.0), items[2].quantity)
    }

    @Test
    fun amountsAfterTheName() {
        assertEquals(ParsedItem("Mjölk", Quantity(1.5, MeasureUnit.L)), ShoppingInput.parse("mjölk 1,5 l").single())
        assertEquals(ParsedItem("Gurka", Quantity(2.0, MeasureUnit.St)), ShoppingInput.parse("gurka x2").single())
        assertEquals(ParsedItem("Pasta", Quantity(500.0, MeasureUnit.G)), ShoppingInput.parse("Pasta 500g").single())
    }

    @Test
    fun bareTrailingNumberStaysInTheName() {
        assertEquals(ParsedItem("Blöjor storlek 5"), ShoppingInput.parse("blöjor storlek 5").single())
    }

    @Test
    fun decimalCommaIsNotASeparator() {
        val items = ShoppingInput.parse("1,5 dl grädde, ägg")
        assertEquals(listOf("Grädde", "Ägg"), items.map { it.name })
        assertEquals(Quantity(1.5, MeasureUnit.Dl), items[0].quantity)
    }

    @Test
    fun oneItemPerLineAndBlankLinesIgnored() {
        val items = ShoppingInput.parse("Kaffe\n\n  havregryn \nbananer.")
        assertEquals(listOf("Kaffe", "Havregryn", "Bananer"), items.map { it.name })
    }

    @Test
    fun emptyInputGivesNothing() {
        assertEquals(emptyList(), ShoppingInput.parse(" ,  ;\n"))
    }

    private fun item(name: String, quantity: Double? = null, unit: MeasureUnit? = null, checked: Boolean = false) =
        ShoppingItem("id-$name", "list", name, quantity, unit, aisle = Aisle.Dairy, checked = checked)

    @Test
    fun sameItemTwiceStaysOneLine() {
        val existing = listOf(item("Mjölk"))
        assertEquals(existing.first(), ShoppingInput.mergeInto(existing, ParsedItem("mjölk")))
    }

    @Test
    fun amountsAddUpWithConversion() {
        val existing = listOf(item("Grädde", 2.0, MeasureUnit.Dl))
        val merged = ShoppingInput.mergeInto(existing, ParsedItem("grädde", Quantity(3.0, MeasureUnit.Msk)))
        assertEquals(MeasureUnit.Dl, merged?.unit)
        assertEquals(2.45, merged?.quantity ?: 0.0, 0.001)
    }

    @Test
    fun unitlessCountsAddToPieces() {
        val existing = listOf(item("Gurka", 1.0, MeasureUnit.St))
        val merged = ShoppingInput.mergeInto(existing, ParsedItem("gurkor", Quantity(2.0)))
        assertEquals(3.0, merged?.quantity)
        assertEquals(MeasureUnit.St, merged?.unit)
    }

    @Test
    fun incompatibleAmountsGetANewLine() {
        val existing = listOf(item("Potatis", 2.0, MeasureUnit.Kg))
        assertNull(ShoppingInput.mergeInto(existing, ParsedItem("potatis", Quantity(4.0))))
    }

    @Test
    fun boughtItemComesBackWithTheNewAmount() {
        val existing = listOf(item("Mjölk", 2.0, MeasureUnit.L, checked = true))
        val merged = assertNotNull(ShoppingInput.mergeInto(existing, ParsedItem("Mjölk", Quantity(1.0, MeasureUnit.L))))
        assertFalse(merged.checked)
        assertEquals(1.0, merged.quantity)
    }

    @Test
    fun nothingToMergeWith() {
        assertNull(ShoppingInput.mergeInto(listOf(item("Mjölk")), ParsedItem("Smör")))
    }
}
