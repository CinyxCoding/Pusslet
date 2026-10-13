package se.pusslet.shell

import se.pusslet.data.usecase.RecipeImporter

/** Something the platform hands to the app: an invite link or text shared from another app. */
sealed interface IncomingIntent {
    data class JoinCode(val code: String) : IncomingIntent

    /** Text shared to Pusslet (e.g. a message from school) opens quick add with the text filled in. */
    data class SharedText(val text: String) : IncomingIntent
}

/**
 * A shared link with little else around it ("Testa den här! https://…") is a recipe to import;
 * longer texts with a link (a message from school) go to quick add.
 */
fun IncomingIntent.SharedText.recipeLink(): String? {
    val url = RecipeImporter.findUrl(text) ?: return null
    return url.takeIf { text.length - url.length <= LINK_ONLY_SLACK }
}

private const val LINK_ONLY_SLACK = 60
