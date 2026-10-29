package se.pusslet.shell.navigation

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import se.pusslet.core.quickadd.EventPrefill

/** Every destination in the app. Type-safe: arguments are properties, not string templates. */
@Serializable
sealed interface Route {
    @Serializable data object Today : Route

    @Serializable data object Calendar : Route

    @Serializable data object Meals : Route

    @Serializable data object Shopping : Route

    @Serializable data object More : Route

    /**
     * Create (no id) or edit an event. [date] pre-selects the day for new events; [occurrenceDate] edits one occurrence of a
     * series; [prefill] is what quick add understood when "Fyll i själv" opened the editor.
     */
    @Serializable data class EventEditor(
        val eventId: String? = null,
        val date: LocalDate? = null,
        val occurrenceDate: LocalDate? = null,
        val prefill: EventPrefill? = null,
    ) : Route

    /** One occurrence of an event (family, subscription or device calendar). */
    @Serializable data class EventDetails(val eventId: String, val occurrenceDate: LocalDate) : Route

    /** "Medicin och feber" (ADR-050), on [childId] when it was opened for one child. */
    @Serializable data class Medicine(val childId: String? = null) : Route

    /** "Delade kostnader" with the other home (ADR-057). */
    @Serializable data object SharedCosts : Route

    /** A child's wishlist (ADR-054). */
    @Serializable data class Wishlist(val childId: String) : Route

    /** "Sök" (ADR-052). */
    @Serializable data object Search : Route

    /** "Lov och vistelsetider" (ADR-051); [stays] opens on the hours. */
    @Serializable data class Breaks(val stays: Boolean = false) : Route

    /** A picture shared to the app, already copied to the private attachment folder (ADR-049). */
    @Serializable data class AttachImage(val fileName: String) : Route

    /** One day of the calendar as its own screen, opened from a day header (ADR-033). */
    @Serializable data class CalendarDay(val date: LocalDate) : Route

    @Serializable data object Family : Route

    @Serializable data class ChildEditor(val childId: String? = null) : Route

    @Serializable data class MemberEditor(val memberId: String? = null) : Route

    @Serializable data class Invite(val claimMemberId: String? = null) : Route

    @Serializable data object OtherHome : Route

    @Serializable data class Join(val code: String? = null) : Route

    @Serializable data class Custody(val scheduleId: String? = null) : Route

    @Serializable data class Handover(val scheduleId: String) : Route

    @Serializable data object Settings : Route

    /** "Prenumerationer": the family's ICS subscriptions (parents only), with the add/edit screen on top. */
    @Serializable data object Subscriptions : Route

    /** "Anpassa Idag" (ADR-027); in the helper view "Visa på startsidan". */
    @Serializable data object TodayLayout : Route

    /** The helper view's only root (UX plan Steg 3, ADR-029): `HelperShell` has no tabs. */
    @Serializable data object HelperHome : Route

    /** "Hela veckan": 14 days of what is shared with the caregiver. */
    @Serializable data object HelperWeek : Route

    /** "Inställningar och hjälp" in the helper view. */
    @Serializable data object HelperSettings : Route

    /** "Så ser Bengt appen": a parent's read-only preview of [memberId]'s helper view (UX plan Steg 3). */
    @Serializable data class HelperPreview(val memberId: String) : Route

    @Serializable data object AboutData : Route

    @Serializable data object Privacy : Route

    @Serializable data object Account : Route

    @Serializable data object Tasks : Route

    /** New (no id) or edited task or chore. */
    @Serializable data class TaskEditor(val taskId: String? = null) : Route

    /** "Så har vi delat": how tasks, chores and hand-offs were shared over the last four weeks (parents). */
    @Serializable data object TaskDistribution : Route

    /** Butiksläge for the active shopping list. */
    @Serializable data object StoreMode : Route

    @Serializable data object Stores : Route

    @Serializable data class StoreEditor(val storeId: String? = null) : Route

    @Serializable data object Pantry : Route

    @Serializable data class RecipeDetails(val recipeId: String) : Route

    /** New (no id) or edited recipe; [sourceUrl] starts a new one from a link without recipe data. */
    @Serializable data class RecipeEditor(val recipeId: String? = null, val sourceUrl: String? = null, val paste: Boolean = false) : Route

    @Serializable data class RecipeImport(val url: String? = null) : Route
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
