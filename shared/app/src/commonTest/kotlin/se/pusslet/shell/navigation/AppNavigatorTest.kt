package se.pusslet.shell.navigation

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class AppNavigatorTest {
    private val event = Route.EventDetails("e-1", LocalDate(2026, 10, 21))

    @Test
    fun aNotificationForIdagShowsIdagNotWhatWasLeftOpenThere() {
        val nav = AppNavigator(TopLevelTab.Today, emptyMap())
        nav.navigate(event)
        nav.selectTab(TopLevelTab.Shopping)

        nav.open(Route.Today)
        assertEquals(TopLevelTab.Today, nav.currentTab)
        assertEquals(listOf<Route>(Route.Today), nav.backStack)

        nav.selectTab(TopLevelTab.Shopping)
        nav.navigate(Route.Stores)
        nav.selectTab(TopLevelTab.Today)
        nav.open(Route.Shopping)
        assertEquals(listOf(Route.Today, Route.Shopping), nav.backStack, "another tab's root, from its root")
    }

    @Test
    fun aScreenAlreadyOnTopIsNotStackedAgain() {
        // Android restored the app with the event open and then delivered the tap on its notification.
        val restored = AppNavigator(TopLevelTab.Today, mapOf(TopLevelTab.Today to listOf(Route.Today, event)))
        restored.open(event)
        assertEquals(listOf(Route.Today, event), restored.backStack)
        restored.goBack()
        assertEquals(listOf<Route>(Route.Today), restored.backStack, "one Back to Idag")

        val other = Route.EventDetails("e-2", LocalDate(2026, 10, 21))
        restored.open(event)
        restored.open(other)
        assertEquals(listOf(Route.Today, event, other), restored.backStack, "a different screen opens on top")
    }

    @Test
    fun navigatingToTheCurrentTabRootStillPopsItAndOtherTabsKeepTheirPlace() {
        val nav = AppNavigator(TopLevelTab.Today, emptyMap())
        nav.selectTab(TopLevelTab.Meals)
        nav.navigate(Route.RecipeDetails("r-1"))
        nav.navigate(Route.Today)
        assertEquals(TopLevelTab.Today, nav.currentTab)
        nav.navigate(Route.Meals)
        assertEquals(listOf(Route.Today, Route.Meals, Route.RecipeDetails("r-1")), nav.backStack, "a tap on a tab keeps its place")
    }
}
