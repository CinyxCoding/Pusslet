package se.pusslet.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SwedishPlacesTest {
    @Test
    fun anEmptySearchFieldSuggestsNothing() {
        assertEquals(emptyList(), SwedishPlaces.suggestions(""))
        assertEquals(emptyList(), SwedishPlaces.suggestions("   "))
    }

    @Test
    fun namesThatStartWithTheQueryComeFirst() {
        assertEquals(listOf("Sala", "Sandviken", "Härnösand", "Uppsala"), SwedishPlaces.suggestions("sa").map { it.name })
        assertEquals(listOf("Lund"), SwedishPlaces.suggestions(" LUND ").map { it.name })
    }

    @Test
    fun suggestionsAreLimited() {
        assertEquals(SwedishPlaces.MAX_SUGGESTIONS, SwedishPlaces.suggestions("a").size)
        assertEquals(3, SwedishPlaces.suggestions("a", limit = 3).size)
    }

    @Test
    fun theFullListStillShowsAllPlacesWithoutAQuery() {
        // Pickers that list without searching (Inställningar › Välj ort) keep the whole list.
        assertEquals(SwedishPlaces.all, SwedishPlaces.search(""))
    }
}
