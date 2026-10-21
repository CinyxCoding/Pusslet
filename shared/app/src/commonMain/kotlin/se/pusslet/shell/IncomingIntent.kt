package se.pusslet.shell

import se.pusslet.core.notify.NotificationTarget
import se.pusslet.data.usecase.RecipeImporter
import se.pusslet.shell.navigation.Route

/** Something the platform hands to the app: an invite link, text shared from another app or a tapped notification. */
sealed interface IncomingIntent {
    data class JoinCode(val code: String) : IncomingIntent

    /** Text shared to Pusslet (e.g. a message from school) opens quick add with the text filled in. */
    data class SharedText(val text: String) : IncomingIntent

    /** A tapped notification (ADR-036) opens what it is about. */
    data class Open(val target: NotificationTarget) : IncomingIntent
}

/** Where a tapped notification leads in the full app; Idag is the Today tab. */
fun NotificationTarget.route(): Route = when (this) {
    NotificationTarget.Today -> Route.Today
    is NotificationTarget.Occurrence -> Route.EventDetails(eventId, date)
    is NotificationTarget.Task -> Route.TaskEditor(taskId)
    NotificationTarget.Shopping -> Route.Shopping
}

/** The same in the helper view, which has no to-do editor: a to-do and Idag open the home page (null = stay there). */
fun NotificationTarget.helperRoute(): Route? = when (this) {
    is NotificationTarget.Occurrence -> Route.EventDetails(eventId, date)
    NotificationTarget.Shopping -> Route.Shopping
    NotificationTarget.Today, is NotificationTarget.Task -> null
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
