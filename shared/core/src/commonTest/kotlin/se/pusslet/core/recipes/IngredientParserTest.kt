package se.pusslet.core.recipes

import se.pusslet.core.shopping.ShoppingNames
import se.pusslet.core.units.MeasureUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IngredientParserTest {
    private fun p(s: String) = IngredientParser.parse(s)

    @Test
    fun amountUnitAndName() {
        val i = p("2 dl vispgrädde")
        assertEquals(2.0, i.quantity)
        assertEquals(MeasureUnit.Dl, i.unit)
        assertEquals("vispgrädde", i.name)
    }

    @Test
    fun commaStartsANoteAndSizeWordsMoveToTheNote() {
        val i = p("1 stor gul lök, finhackad")
        assertEquals("gul lök", i.name)
        assertEquals(MeasureUnit.St, i.unit)
        assertEquals("stor, finhackad", i.note)
    }

    @Test
    fun parenthesisIsANote() {
        val i = p("400 g kycklingfilé (ca 3 st)")
        assertEquals(400.0, i.quantity)
        assertEquals(MeasureUnit.G, i.unit)
        assertEquals("kycklingfilé", i.name)
        assertEquals("ca 3 st", i.note)
    }

    @Test
    fun packageUnitsAndPackSize() {
        val i = p("1 burk krossade tomater à 400 g")
        assertEquals(MeasureUnit.Burk, i.unit)
        assertEquals("krossade tomater", i.name)
        assertEquals("à 400 g", i.note)
    }

    @Test
    fun fractionsAndRanges() {
        val half = p("½ tsk chiliflakes")
        assertEquals(0.5, half.quantity)
        assertEquals(MeasureUnit.Tsk, half.unit)
        val range = p("2-3 vitlöksklyftor")
        assertEquals(2.0, range.quantity)
        assertEquals(3.0, range.quantityMax)
    }

    @Test
    fun alternativesBecomeNotes() {
        val i = p("2 dl matlagningsgrädde eller havregrädde")
        assertEquals("matlagningsgrädde", i.name)
        assertEquals("eller havregrädde", i.note)
    }

    @Test
    fun staplesAreOptional() {
        assertTrue(p("salt och peppar").optional)
        assertTrue(p("1 krm salt").optional)
        assertTrue(p("3 dl vatten").optional)
        assertFalse(p("3 dl mjölk").optional)
    }

    @Test
    fun noAmount() {
        val i = p("färsk koriander till servering")
        assertNull(i.quantity)
        assertTrue(i.optional)
        assertEquals("koriander", i.name)
    }

    @Test
    fun bulletsAndNonBreakingSpaces() {
        val i = p("• 500 g nötfärs")
        assertEquals(500.0, i.quantity)
        assertEquals("nötfärs", i.name)
    }

    @Test
    fun canonicalNamesMergePluralsAndSizes() {
        assertEquals("gul lök", ShoppingNames.canonical("gula lökar"))
        assertEquals("gul lök", ShoppingNames.canonical("Stor gul lök"))
        assertEquals("morot", ShoppingNames.canonical("morötter"))
        assertEquals("krossade tomat", ShoppingNames.canonical("krossade tomater"))
        assertEquals(ShoppingNames.canonical("krossade tomater"), ShoppingNames.canonical("Krossade tomater"))
    }
}
