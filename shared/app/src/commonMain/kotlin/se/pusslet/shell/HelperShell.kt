package se.pusslet.shell

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import co.touchlab.kermit.Logger
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.EventOccurrence
import se.pusslet.feature.calendar.details.EventDetailsRoute
import se.pusslet.feature.family.join.JoinRoute
import se.pusslet.feature.settings.AboutDataRoute
import se.pusslet.feature.settings.AccountRoute
import se.pusslet.feature.settings.PrivacyRoute
import se.pusslet.feature.shopping.ShoppingRoute
import se.pusslet.feature.shopping.StoreModeRoute
import se.pusslet.feature.today.helper.HelperHomeRoute
import se.pusslet.feature.today.helper.HelperHomeSectionsRoute
import se.pusslet.feature.today.helper.HelperSettingsDestination
import se.pusslet.feature.today.helper.HelperSettingsScreen
import se.pusslet.feature.today.helper.HelperWeekRoute
import se.pusslet.shell.navigation.HelperNavigator
import se.pusslet.shell.navigation.Route
import se.pusslet.shell.navigation.rememberHelperNavigator
import se.pusslet.ui.components.AddButtonState
import se.pusslet.ui.components.LocalAddButton
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.today_block_shopping
import se.pusslet.ui.theme.HelperDimens
import se.pusslet.ui.theme.HelperTheme
import se.pusslet.ui.theme.Spacing

private val log = Logger.withTag("HelperShell")

/**
 * The simple helper view for caregivers (UX plan Steg 3, ADR-029): one root, `Route.HelperHome`, without a bottom bar, a
 * round + or quick add; sub-pages have a top bar with Back and are at most two levels deep. Everything is drawn with
 * [HelperTheme] (nothing below 18 sp, 64 dp buttons). [onShowFullApp] is "Visa hela appen".
 */
@Composable
internal fun HelperShell(incoming: IncomingIntent?, onIncomingHandled: () -> Unit, onShowFullApp: () -> Unit) {
    val navigator = rememberHelperNavigator()
    // Only the shopping list registers an add action here; it gets a labelled button instead of the round +.
    val addButton = remember { AddButtonState() }
    LaunchedEffect(incoming) {
        when (incoming) {
            is IncomingIntent.JoinCode -> navigator.navigate(Route.Join(incoming.code))
            // Quick add can create events, which a caregiver may not; the shared text is not kept.
            is IncomingIntent.SharedText -> log.i { "Shared text is not used in the helper view" }
            is IncomingIntent.Open -> {
                navigator.reset()
                incoming.target.helperRoute()?.let(navigator::navigate)
            }
            null -> return@LaunchedEffect
        }
        onIncomingHandled()
    }
    HelperTheme {
        CompositionLocalProvider(LocalAddButton provides addButton) {
            HelperNavDisplay(navigator, addButton, onShowFullApp)
        }
    }
}

@Composable
private fun HelperNavDisplay(navigator: HelperNavigator, addButton: AddButtonState, onShowFullApp: () -> Unit) {
    val openOccurrence: (EventOccurrence) -> Unit = { navigator.navigate(Route.EventDetails(it.event.id, it.occurrenceDate)) }
    NavDisplay(
        backStack = navigator.backStack,
        onBack = navigator::goBack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<Route.HelperHome> {
                Scaffold { padding ->
                    HelperHomeRoute(
                        onOpenOccurrence = openOccurrence,
                        onOpenWeek = { navigator.navigate(Route.HelperWeek) },
                        onOpenShopping = { navigator.navigate(Route.Shopping) },
                        onOpenSettings = { navigator.navigate(Route.HelperSettings) },
                        contentPadding = padding,
                    )
                }
            }
            entry<Route.HelperWeek> { HelperWeekRoute(onBack = navigator::goBack, onOpenOccurrence = openOccurrence) }
            entry<Route.EventDetails> { r ->
                EventDetailsRoute(
                    eventId = r.eventId,
                    occurrenceDate = r.occurrenceDate,
                    onBack = navigator::goBack,
                    // "Ändra" needs a parent (EventDetailsState.editable), so a caregiver never sees it.
                    onEdit = { _, _ -> },
                )
            }
            entry<Route.Shopping> { HelperShopping(navigator, addButton) }
            entry<Route.StoreMode> { StoreModeRoute(onBack = navigator::goBack) }
            entry<Route.HelperSettings> {
                HelperSettingsScreen(
                    onBack = navigator::goBack,
                    onOpen = { navigator.navigate(it.route()) },
                    onShowFullApp = onShowFullApp,
                )
            }
            entry<Route.TodayLayout> { HelperHomeSectionsRoute(onBack = navigator::goBack) }
            entry<Route.Account> { AccountRoute(onBack = navigator::goBack) }
            entry<Route.AboutData> { AboutDataRoute(onBack = navigator::goBack) }
            entry<Route.Privacy> { PrivacyRoute(onBack = navigator::goBack, onForgotten = navigator::reset) }
            entry<Route.Join> { r -> JoinRoute(initialCode = r.code, onBack = navigator::goBack, onJoined = navigator::reset) }
        },
    )
}

/**
 * The shopping list as a sub-page (only when "Inköpslistan" is switched on). List management, stores and the pantry are
 * parents only (`canManage`), so they never show; adding an item uses a full-width button that says what it does.
 */
@Composable
private fun HelperShopping(navigator: HelperNavigator, addButton: AddButtonState) {
    Scaffold(
        topBar = { PussletTopBar(stringResource(Res.string.today_block_shopping), onBack = navigator::goBack) },
        bottomBar = {
            val label = addButton.label
            if (label != null) {
                Button(
                    onClick = addButton::click,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm)
                        .heightIn(min = HelperDimens.button),
                ) { Text(label) }
            }
        },
    ) { padding ->
        ShoppingRoute(
            onOpenStoreMode = { navigator.navigate(Route.StoreMode) },
            // "Butiker och det vi har hemma" is only offered to parents (canManage).
            onOpenStores = {},
            contentPadding = padding,
        )
    }
}

private fun HelperSettingsDestination.route(): Route = when (this) {
    HelperSettingsDestination.HomeSections -> Route.TodayLayout
    HelperSettingsDestination.Account -> Route.Account
    HelperSettingsDestination.Privacy -> Route.Privacy
    HelperSettingsDestination.AboutData -> Route.AboutData
}
