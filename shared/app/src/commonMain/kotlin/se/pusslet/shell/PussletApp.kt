package se.pusslet.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import se.pusslet.feature.calendar.CalendarScreen
import se.pusslet.feature.meals.MealsScreen
import se.pusslet.feature.settings.MoreScreen
import se.pusslet.feature.shopping.ShoppingScreen
import se.pusslet.feature.today.TodayScreen
import se.pusslet.shell.navigation.AppNavigator
import se.pusslet.shell.navigation.Route
import se.pusslet.shell.navigation.TopLevelTab
import se.pusslet.shell.navigation.rememberAppNavigator
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.nav_calendar
import se.pusslet.ui.resources.nav_meals
import se.pusslet.ui.resources.nav_more
import se.pusslet.ui.resources.nav_shopping
import se.pusslet.ui.resources.nav_today
import se.pusslet.ui.resources.quick_add
import se.pusslet.ui.resources.quick_add_description
import se.pusslet.ui.theme.PussletTheme
import se.pusslet.ui.theme.ThemeMode

/** Root composable shared by Android and iOS. */
@Composable
fun PussletApp(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    navigator: AppNavigator = rememberAppNavigator(),
) {
    PussletTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
        Scaffold(
            bottomBar = { PussletBottomBar(navigator) },
            floatingActionButton = { QuickAddButton(onClick = {}) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                NavDisplay(
                    backStack = navigator.backStack,
                    onBack = navigator::goBack,
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    entryProvider = entryProvider {
                        entry<Route.Today> { TodayScreen() }
                        entry<Route.Calendar> { CalendarScreen() }
                        entry<Route.Meals> { MealsScreen() }
                        entry<Route.Shopping> { ShoppingScreen() }
                        entry<Route.More> { MoreScreen() }
                    },
                )
            }
        }
    }
}

@Composable
private fun QuickAddButton(onClick: () -> Unit) {
    val description = stringResource(Res.string.quick_add_description)
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
        text = { Text(stringResource(Res.string.quick_add)) },
        modifier = Modifier.semantics { contentDescription = description },
    )
}

private data class TabSpec(val tab: TopLevelTab, val label: StringResource, val icon: ImageVector)

private val tabs = listOf(
    TabSpec(TopLevelTab.Today, Res.string.nav_today, PussletIcons.Today),
    TabSpec(TopLevelTab.Calendar, Res.string.nav_calendar, PussletIcons.CalendarMonth),
    TabSpec(TopLevelTab.Meals, Res.string.nav_meals, PussletIcons.Restaurant),
    TabSpec(TopLevelTab.Shopping, Res.string.nav_shopping, Icons.Filled.ShoppingCart),
    TabSpec(TopLevelTab.More, Res.string.nav_more, Icons.Filled.Menu),
)

@Composable
private fun PussletBottomBar(navigator: AppNavigator) {
    NavigationBar {
        tabs.forEach { spec ->
            NavigationBarItem(
                selected = navigator.currentTab == spec.tab,
                onClick = { navigator.selectTab(spec.tab) },
                icon = { Icon(spec.icon, contentDescription = null) },
                label = { Text(stringResource(spec.label)) },
            )
        }
    }
}
