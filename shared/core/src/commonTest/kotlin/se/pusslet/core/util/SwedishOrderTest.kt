package se.pusslet.core.util

import se.pusslet.core.model.SwedishPlaces
import kotlin.test.Test
import kotlin.test.assertEquals

class SwedishOrderTest {
    @Test
    fun alphabetEndsWithÅÄÖAndCaseDoesNotMatter() {
        val sorted = listOf("ört", "Ägg", "ål", "Zucchini", "banan", "Apelsin", "Öl", "Äpple", "Ålandsost")
            .sortedWith(SwedishOrder.comparator)
        assertEquals(listOf("Apelsin", "banan", "Zucchini", "ål", "Ålandsost", "Ägg", "Äpple", "Öl", "ört"), sorted)
    }

    @Test
    fun placesListÅreBeforeÄlmhultBeforeÖrebro() {
        val names = SwedishPlaces.all.map { it.name }
        val tail = names.filter { it.first() in "ÅÄÖ" }
        assertEquals(tail.sortedWith(SwedishOrder.comparator), tail)
        assertEquals(names.size, names.indexOfFirst { it.first() in "ÅÄÖ" } + tail.size)
    }
}
