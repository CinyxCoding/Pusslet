package se.pusslet.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedRecipeLinkTest {
    @Test
    fun aSharedLinkIsARecipeToImport() {
        val menu = "https://recept.se/veckomeny/lattlagade-veckomenyer/2026-44"
        assertEquals(menu, IncomingIntent.SharedText("Veckans meny! $menu").recipeLink())
        assertEquals(
            "https://recept.se/recept/linssoppa\nhttps://recept.se/recept/fiskgratang",
            IncomingIntent.SharedText("https://recept.se/recept/linssoppa\nhttps://recept.se/recept/fiskgratang\n").recipeLink(),
            "a few links shared together are imported together",
        )
    }

    @Test
    fun aMessageWithALinkGoesToQuickAdd() {
        val letter = "Hej! På fredag har vi utflykt till skogen, ta med matsäck och regnkläder. Läs mer här: https://skolan.se/info"
        assertNull(IncomingIntent.SharedText(letter).recipeLink())
        assertNull(IncomingIntent.SharedText("Bara text").recipeLink())
    }
}
