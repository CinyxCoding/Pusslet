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

    @Serializable data object Search : Route

    /** A picture shared to the app, already copied to the private attachment folder. */
    @Serializable data class AttachImage(val fileName: String) : Route

    @Serializable data object Family : Route

    @Serializable data class ChildEditor(val childId: String? = null) : Route

    @Serializable data class Wishlist(val childId: String) : Route

    @Serializable data class MemberEditor(val memberId: String? = null) : Route

    @Serializable data class Invite(val claimMemberId: String? = null) : Route

    @Serializable data object OtherHome : Route

    @Serializable data class Join(val code: String? = null) : Route

    @Serializable data class Custody(val scheduleId: String? = null) : Route

    @Serializable data class Handover(val scheduleId: String) : Route

    /** ICS subscriptions; [url] pre-fills the add dialog (a shared webcal:// link). */
    @Serializable data class Subscriptions(val url: String? = null) : Route

    @Serializable data object Settings : Route

    @Serializable data object AboutData : Route

    @Serializable data object Privacy : Route

    @Serializable data object Account : Route

    @Serializable data object Tasks : Route

    /** Edit a task, or create one ([chore] starts a new household chore). */
    @Serializable data class TaskEditor(val taskId: String? = null, val chore: Boolean = false) : Route

    @Serializable data object Workload : Route

    @Serializable data object KidPicker : Route

    /** Barnläge for one child (no bottom bar; leaving takes a long press). */
    @Serializable data class KidMode(val childId: String) : Route

    @Serializable data object Allowance : Route

    @Serializable data class StoreMode(val listId: String) : Route

    @Serializable data object Pantry : Route

    @Serializable data object Stores : Route

    @Serializable data object Recipes : Route

    @Serializable data class Recipe(val recipeId: String) : Route

    /** New ([recipeId] null), edit, import from a link ([link], optionally pre-filled [url]) or paste text ([paste]). */
    @Serializable data class RecipeEditor(
        val recipeId: String? = null,
        val link: Boolean = false,
        val url: String? = null,
        val paste: Boolean = false,
    ) : Route
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
