package se.pusslet.shell

/** Something the platform hands to the app: an invite link or text shared from another app. */
sealed interface IncomingIntent {
    data class JoinCode(val code: String) : IncomingIntent

    /** Text shared to Pusslet (e.g. a message from school) opens quick add with the text filled in. */
    data class SharedText(val text: String) : IncomingIntent

    /** A shared web link (typically a recipe page) opens the recipe import. */
    data class SharedLink(val url: String) : IncomingIntent
}
