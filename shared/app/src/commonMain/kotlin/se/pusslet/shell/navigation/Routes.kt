package se.pusslet.shell.navigation

import kotlinx.serialization.Serializable

/** Every destination in the app. Type-safe: arguments are properties, not string templates. */
@Serializable
sealed interface Route {
    @Serializable data object Today : Route

    @Serializable data object Calendar : Route

    @Serializable data object Meals : Route

    @Serializable data object Shopping : Route

    @Serializable data object More : Route
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
