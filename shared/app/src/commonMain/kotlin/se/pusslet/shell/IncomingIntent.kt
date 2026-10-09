package se.pusslet.shell

/** Something the platform hands to the app: an invite link or text shared from another app. */
sealed interface IncomingIntent {
    data class JoinCode(val code: String) : IncomingIntent

    /** Text shared to Pusslet (e.g. a message from school) opens quick add with the text filled in. */
    data class SharedText(val text: String) : IncomingIntent

    /** A home-screen widget opening its part of the app. */
    data class OpenTab(val tab: se.pusslet.shell.navigation.TopLevelTab) : IncomingIntent

    /** A tapped notification about an event occurrence. */
    data class OpenEvent(val eventId: String, val occurrenceDate: kotlinx.datetime.LocalDate) : IncomingIntent

    /** A shared web link: calendar feeds (webcal://, .ics) open the subscriptions, other pages the recipe import. */
    data class SharedLink(val url: String) : IncomingIntent {
        val isCalendarFeed: Boolean
            get() = url.startsWith("webcal", ignoreCase = true) || url.substringBefore('?').endsWith(".ics", ignoreCase = true)
    }
}
