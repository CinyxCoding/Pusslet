package se.pusslet.core.medicine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthLinksTest {
    @Test
    fun theLeafletSearchCarriesTheMedicineAsWrittenEncoded() {
        assertEquals("https://fass.se/search?query=K%C3%A5vepenin", HealthLinks.fass("Kåvepenin"))
        assertEquals("https://fass.se/search?query=Alvedon%20500%20mg", HealthLinks.fass("  Alvedon 500 mg "))
        assertEquals("https://fass.se/search?query=%C3%96rtte%20%26%20honung%3D%23%2F%3F", HealthLinks.fass("Örtte & honung=#/?"))
    }

    @Test
    fun noMedicineOpensFassItself() {
        assertEquals("https://fass.se", HealthLinks.fass(""))
        assertEquals("https://fass.se", HealthLinks.fass("   "))
    }

    @Test
    fun theAdviceIs1177s() {
        assertTrue(HealthLinks.FEVER_IN_CHILDREN.startsWith("https://www.1177.se/"))
        assertEquals("1177", HealthLinks.PHONE_1177)
    }
}
