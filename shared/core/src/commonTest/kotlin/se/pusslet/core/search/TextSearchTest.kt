package se.pusslet.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextSearchTest {
    private fun score(query: String, title: String, vararg other: String) = TextSearch.score(TextSearch.words(query), title, other.toList())

    @Test
    fun wordsAreFoldedAndSplit() {
        assertEquals(listOf("tandlakare", "simon"), TextSearch.words("Tandläkare, Simon!"))
        assertEquals(listOf("kottbullar", "med", "potatismos"), TextSearch.words("Köttbullar med potatismos"))
    }

    @Test
    fun everyWordMustMatchSomewhere() {
        assertTrue(score("tand", "Tandläkare") > 0)
        assertTrue(score("tandläkare simon", "Tandläkare", "Simon") > 0)
        assertEquals(0, score("tandläkare ella", "Tandläkare", "Simon"))
        assertEquals(0, score("", "Tandläkare"))
    }

    @Test
    fun swedishCompoundsAndMissingDotsStillMatch() {
        assertTrue(score("läkare", "Tandläkare") > 0, "inside a compound from three letters")
        assertTrue(score("lakare", "Tandläkare") > 0, "typed without å ä ö")
        assertEquals(0, score("la", "Tandläkare"), "two letters inside a word is too loose")
    }

    @Test
    fun titleHitsRankAboveNotes() {
        val title = score("fotboll", "Fotboll")
        val word = score("fotboll", "Simon fotboll")
        val inside = score("boll", "Fotboll")
        val note = score("fotboll", "Träning", "Ta med fotbollsskor")
        assertTrue(title > word && word > inside && inside > note, "$title > $word > $inside > $note")
    }
}
