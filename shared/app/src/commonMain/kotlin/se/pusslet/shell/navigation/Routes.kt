package se.pusslet.shell.navigation

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** Every destination in the app. Type-safe: arguments are properties, not string templates. */
@Serializable
sealed interface Route {
    @Serializable data object Today : Route

    @Serializable data object Calendar : Route

    @Serializable data object Meals : Route

    @Serializable data object Shopping : Route

    @Serializable data object More : Route

    /** Create (no id) or edit an event. [date] pre-selects the day for new events; [occurrenceDate] edits one occurrence of a series. */
    @Serializable data class EventEditor(
        val eventId: String? = null,
        val date: LocalDate? = null,
        val occurrenceDate: LocalDate? = null,
    ) : Route

    /** One occurrence of an event (family, subscription or device calendar). */
    @Serializable data class EventDetails(val eventId: String, val occurrenceDate: LocalDate) : Route

    @Serializable data object Family : Route

    @Serializable data class ChildEditor(val childId: String? = null) : Route

    @Serializable data class MemberEditor(val memberId: String? = null) : Route

    @Serializable data class Invite(val claimMemberId: String? = null) : Route

    @Serializable data class Join(val code: String? = null) : Route

    @Serializable data class Custody(val scheduleId: String? = null) : Route

    @Serializable data class Handover(val scheduleId: String) : Route

    @Serializable data object Settings : Route

    @Serializable data object AboutData : Route

    @Serializable data object Privacy : Route

    @Serializable data object Account : Route

    @Serializable data object Tasks : Route
}

/** Bottom navigation: Idag · Kalender · Mat · Handla · Mer. */
@Serializable
enum class TopLevelTab(val root: Route) {
    Today(Route.Today),
    Calendar(Route.Calendar),
    Meals(Route.Meals),
    Shopping(Route.Shopping),
    More(Route.More),
}
