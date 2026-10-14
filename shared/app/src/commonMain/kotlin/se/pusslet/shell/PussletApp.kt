package se.pusslet.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.EventOccurrence
import se.pusslet.data.usecase.QuickAddOutcome
import se.pusslet.feature.calendar.CalendarRoute
import se.pusslet.feature.calendar.details.EventDetailsRoute
import se.pusslet.feature.calendar.editor.EventEditorRoute
import se.pusslet.feature.family.FamilyRoute
import se.pusslet.feature.family.child.ChildEditorRoute
import se.pusslet.feature.family.custody.CustodyEditorRoute
import se.pusslet.feature.family.custody.HandoverRoute
import se.pusslet.feature.family.invite.InviteRoute
import se.pusslet.feature.family.join.JoinRoute
import se.pusslet.feature.family.member.MemberEditorRoute
import se.pusslet.feature.family.onboarding.OnboardingRoute
import se.pusslet.feature.meals.MealsRoute
import se.pusslet.feature.meals.RecipeDetailsRoute
import se.pusslet.feature.meals.RecipeEditorRoute
import se.pusslet.feature.meals.RecipeImportRoute
import se.pusslet.feature.settings.AboutDataRoute
import se.pusslet.feature.settings.AccountRoute
import se.pusslet.feature.settings.MoreDestination
import se.pusslet.feature.settings.MoreScreen
import se.pusslet.feature.settings.PrivacyRoute
import se.pusslet.feature.settings.SettingsRoute
import se.pusslet.feature.shopping.PantryRoute
import se.pusslet.feature.shopping.ShoppingRoute
import se.pusslet.feature.shopping.StoreEditorRoute
import se.pusslet.feature.shopping.StoreModeRoute
import se.pusslet.feature.shopping.StoresRoute
import se.pusslet.feature.tasks.TasksScreen
import se.pusslet.feature.today.TodayRoute
import se.pusslet.shell.navigation.AppNavigator
import se.pusslet.shell.navigation.Route
import se.pusslet.shell.navigation.TopLevelTab
import se.pusslet.shell.navigation.rememberAppNavigator
import se.pusslet.shell.quickadd.QuickAddSheet
import se.pusslet.shell.quickadd.QuickAddViewModel
import se.pusslet.shell.quickadd.ReceiptAction
import se.pusslet.shell.quickadd.ReceiptContext
import se.pusslet.shell.quickadd.receiptMessage
import se.pusslet.ui.components.AddButtonState
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.LocalAddButton
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.action_undo
import se.pusslet.ui.resources.months_short
import se.pusslet.ui.resources.nav_calendar
import se.pusslet.ui.resources.nav_meals
import se.pusslet.ui.resources.nav_more
import se.pusslet.ui.resources.nav_shopping
import se.pusslet.ui.resources.nav_today
import se.pusslet.ui.resources.quickadd_action_show
import se.pusslet.ui.resources.weekdays_short
import se.pusslet.ui.theme.PussletTheme

/**
 * Root composable shared by Android and iOS: theme, onboarding gate, bottom navigation with one back stack per tab,
 * quick add and incoming links/shares.
 */
@Composable
fun PussletApp(
    incoming: IncomingIntent? = null,
    onIncomingHandled: () -> Unit = {},
    shell: ShellViewModel = koinViewModel(),
) {
    val state by shell.state.collectAsStateWithLifecycle()
    PussletTheme(themeMode = state.theme, dynamicColor = state.dynamicColor) {
        Surface(Modifier.fillMaxSize()) {
            when {
                !state.ready -> LoadingBox()
                !state.onboarded -> OnboardingFlow(incoming, onIncomingHandled)
                else -> MainShell(incoming, onIncomingHandled)
            }
        }
    }
}

@Composable
private fun OnboardingFlow(incoming: IncomingIntent?, onIncomingHandled: () -> Unit) {
    var joinCode by rememberSaveable { mutableStateOf<String?>(null) }
    var joining by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(incoming) {
        if (incoming is IncomingIntent.JoinCode) {
            joinCode = incoming.code
            joining = true
            onIncomingHandled()
        }
    }
    if (joining) {
        // Joining finishes by marking onboarding done; the shell then switches to the main UI by itself.
        JoinRoute(initialCode = joinCode, onBack = { joining = false }, onJoined = {})
    } else {
        OnboardingRoute(onJoin = { joining = true }, onFinished = {})
    }
}

@Composable
private fun MainShell(incoming: IncomingIntent?, onIncomingHandled: () -> Unit) {
    val navigator = rememberAppNavigator()
    val quickAdd: QuickAddViewModel = koinViewModel()
    var showQuickAdd by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Each tab root says what the round + does there (ADR-023); screens without a registration have no +.
    val addButton = remember { AddButtonState() }
    val weekdaysShort = stringArrayResource(Res.array.weekdays_short)
    val monthsShort = stringArrayResource(Res.array.months_short)

    LaunchedEffect(incoming) {
        when (incoming) {
            is IncomingIntent.JoinCode -> navigator.navigate(Route.Join(incoming.code))
            is IncomingIntent.SharedText -> {
                val recipe = incoming.recipeLink()
                if (recipe != null) {
                    navigator.navigate(Route.RecipeImport(recipe))
                } else {
                    quickAdd.start(incoming.text)
                    showQuickAdd = true
                }
            }
            null -> return@LaunchedEffect
        }
        onIncomingHandled()
    }

    val openQuickAdd: (LocalDate?) -> Unit = { date ->
        quickAdd.start(date = date)
        showQuickAdd = true
    }

    fun onSaved(outcome: QuickAddOutcome) {
        showQuickAdd = false
        val context = ReceiptContext(quickAdd.today(), weekdaysShort, monthsShort, quickAdd.state.value.people)
        scope.launch {
            val message = receiptMessage(outcome.receipt, context)
            val actionLabel = when (message.action) {
                ReceiptAction.Undo -> getString(Res.string.action_undo)
                ReceiptAction.Show -> getString(Res.string.quickadd_action_show)
                null -> null
            }
            val result = snackbar.showSnackbar(
                message.text,
                actionLabel,
                duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                when (message.action) {
                    ReceiptAction.Undo -> quickAdd.undo(outcome)
                    ReceiptAction.Show -> navigator.selectTab(TopLevelTab.Shopping)
                    null -> Unit
                }
            }
        }
    }

    CompositionLocalProvider(LocalAddButton provides addButton) {
        Scaffold(
            bottomBar = { if (navigator.atTabRoot) PussletBottomBar(navigator) },
            floatingActionButton = {
                val label = addButton.label
                if (navigator.atTabRoot && label != null) {
                    // Round and without text; the label is the TalkBack name ("Lägg till vara").
                    FloatingActionButton(onClick = { addButton.click() }) {
                        Icon(Icons.Filled.Add, contentDescription = label)
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                AppNavDisplay(navigator, padding, openQuickAdd)
            }
        }
    }
    if (showQuickAdd) {
        QuickAddSheet(viewModel = quickAdd, onDismiss = { showQuickAdd = false }, onSaved = ::onSaved)
    }
}

@Composable
private fun AppNavDisplay(navigator: AppNavigator, padding: PaddingValues, onQuickAdd: (LocalDate?) -> Unit) {
    val openOccurrence: (EventOccurrence) -> Unit = { navigator.navigate(Route.EventDetails(it.event.id, it.occurrenceDate)) }
    NavDisplay(
        backStack = navigator.backStack,
        onBack = navigator::goBack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<Route.Today> {
                TodayRoute(
                    onOpenOccurrence = openOccurrence,
                    onPlanDinner = { navigator.navigate(Route.Meals) },
                    onOpenSettings = { navigator.navigate(Route.Settings) },
                    onOpenHandover = { navigator.navigate(Route.Handover(it)) },
                    onQuickAdd = onQuickAdd,
                    contentPadding = padding,
                )
            }
            entry<Route.Calendar> {
                CalendarRoute(
                    onOpenOccurrence = openOccurrence,
                    onNewEvent = { navigator.navigate(Route.EventEditor(date = it)) },
                    onQuickAdd = onQuickAdd,
                    contentPadding = padding,
                )
            }
            entry<Route.Meals> {
                MealsRoute(
                    onOpenRecipe = { navigator.navigate(Route.RecipeDetails(it)) },
                    onNewRecipe = { navigator.navigate(Route.RecipeEditor()) },
                    onPasteRecipe = { navigator.navigate(Route.RecipeEditor(paste = true)) },
                    onImportRecipe = { navigator.navigate(Route.RecipeImport()) },
                    onShopForWeek = { navigator.navigate(Route.Shopping) },
                    contentPadding = padding,
                )
            }
            entry<Route.RecipeDetails> { r ->
                RecipeDetailsRoute(r.recipeId, onBack = navigator::goBack, onEdit = { navigator.navigate(Route.RecipeEditor(it)) })
            }
            entry<Route.RecipeEditor> { r ->
                RecipeEditorRoute(r.recipeId, r.sourceUrl, startPasting = r.paste, onDone = { saved ->
                    navigator.goBack()
                    // A new recipe opens right away; an edited one is already open underneath.
                    if (saved != null && r.recipeId == null) navigator.navigate(Route.RecipeDetails(saved))
                })
            }
            entry<Route.RecipeImport> { r ->
                RecipeImportRoute(
                    initialUrl = r.url,
                    onBack = navigator::goBack,
                    onImported = { id ->
                        navigator.goBack()
                        navigator.navigate(Route.RecipeDetails(id))
                    },
                    onPasteInstead = { url ->
                        navigator.goBack()
                        navigator.navigate(Route.RecipeEditor(sourceUrl = url))
                    },
                )
            }
            entry<Route.Shopping> {
                ShoppingRoute(
                    onOpenStoreMode = { navigator.navigate(Route.StoreMode) },
                    onOpenStores = { navigator.navigate(Route.Stores) },
                    onOpenPantry = { navigator.navigate(Route.Pantry) },
                    contentPadding = padding,
                )
            }
            entry<Route.StoreMode> { StoreModeRoute(onBack = navigator::goBack) }
            entry<Route.Stores> { StoresRoute(onBack = navigator::goBack, onEdit = { navigator.navigate(Route.StoreEditor(it)) }) }
            entry<Route.StoreEditor> { r -> StoreEditorRoute(r.storeId, onDone = navigator::goBack) }
            entry<Route.Pantry> { PantryRoute(onBack = navigator::goBack) }
            entry<Route.More> { MoreScreen(onOpen = { navigator.navigate(it.route()) }, contentPadding = padding) }
            entry<Route.Tasks> { TasksScreen() }
            entry<Route.EventEditor> { r -> EventEditorRoute(r.eventId, r.date, r.occurrenceDate, onDone = navigator::goBack) }
            entry<Route.EventDetails> { r ->
                EventDetailsRoute(
                    eventId = r.eventId,
                    occurrenceDate = r.occurrenceDate,
                    onBack = navigator::goBack,
                    onEdit = { id, occurrence -> navigator.navigate(Route.EventEditor(id, occurrenceDate = occurrence)) },
                )
            }
            entry<Route.Family> {
                FamilyRoute(
                    onBack = navigator::goBack,
                    onEditChild = { navigator.navigate(Route.ChildEditor(it)) },
                    onEditMember = { navigator.navigate(Route.MemberEditor(it)) },
                    onInvite = { navigator.navigate(Route.Invite(it)) },
                    onCustody = { navigator.navigate(Route.Custody(it)) },
                    onHandover = { navigator.navigate(Route.Handover(it)) },
                )
            }
            entry<Route.ChildEditor> { r -> ChildEditorRoute(r.childId, onDone = navigator::goBack) }
            entry<Route.MemberEditor> { r ->
                MemberEditorRoute(r.memberId, onDone = navigator::goBack, onInvite = { navigator.navigate(Route.Invite(it)) })
            }
            entry<Route.Invite> { r ->
                InviteRoute(r.claimMemberId, onBack = navigator::goBack, onSignIn = { navigator.navigate(Route.Account) })
            }
            entry<Route.Join> { r -> JoinRoute(initialCode = r.code, onBack = navigator::goBack, onJoined = navigator::reset) }
            entry<Route.Custody> { r -> CustodyEditorRoute(r.scheduleId, onDone = navigator::goBack) }
            entry<Route.Handover> { r -> HandoverRoute(r.scheduleId, onBack = navigator::goBack) }
            entry<Route.Settings> { SettingsRoute(onBack = navigator::goBack) }
            entry<Route.Account> { AccountRoute(onBack = navigator::goBack) }
            entry<Route.AboutData> { AboutDataRoute(onBack = navigator::goBack) }
            entry<Route.Privacy> { PrivacyRoute(onBack = navigator::goBack, onForgotten = navigator::reset) }
        },
    )
}

private fun MoreDestination.route(): Route = when (this) {
    MoreDestination.Family -> Route.Family
    MoreDestination.Tasks -> Route.Tasks
    MoreDestination.Settings -> Route.Settings
    MoreDestination.Account -> Route.Account
    MoreDestination.AboutData -> Route.AboutData
    MoreDestination.Privacy -> Route.Privacy
}

private data class TabSpec(val tab: TopLevelTab, val label: StringResource, val icon: ImageVector)

private val tabs = listOf(
    TabSpec(TopLevelTab.Today, Res.string.nav_today, PussletIcons.Sun),
    TabSpec(TopLevelTab.Calendar, Res.string.nav_calendar, PussletIcons.CalendarMonth),
    TabSpec(TopLevelTab.Meals, Res.string.nav_meals, PussletIcons.Restaurant),
    TabSpec(TopLevelTab.Shopping, Res.string.nav_shopping, Icons.Filled.ShoppingCart),
    TabSpec(TopLevelTab.More, Res.string.nav_more, PussletIcons.MoreHoriz),
)

@Composable
private fun PussletBottomBar(navigator: AppNavigator) {
    NavigationBar {
        tabs.forEach { spec ->
            NavigationBarItem(
                selected = navigator.currentTab == spec.tab,
                onClick = { navigator.selectTab(spec.tab) },
                // The label names the tab for TalkBack too.
                icon = { Icon(spec.icon, contentDescription = null) },
                label = { NavLabel(stringResource(spec.label)) },
                colors = SelectionColors.navigationBarItem(),
            )
        }
    }
}

/** Labels always show (UX plan 1.3). Only their font scale is capped, so five labels fit even with very large text. */
@Composable
private fun NavLabel(text: String) {
    val density = LocalDensity.current
    val capped = Density(density.density, fontScale = minOf(density.fontScale, MAX_LABEL_FONT_SCALE))
    CompositionLocalProvider(LocalDensity provides capped) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val MAX_LABEL_FONT_SCALE = 1.3f
