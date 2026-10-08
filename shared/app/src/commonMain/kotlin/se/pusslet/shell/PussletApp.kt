package se.pusslet.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
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
import se.pusslet.feature.meals.recipes.RecipeEditorRoute
import se.pusslet.feature.meals.recipes.RecipeLibraryRoute
import se.pusslet.feature.meals.recipes.RecipeRoute
import se.pusslet.feature.settings.AboutDataRoute
import se.pusslet.feature.settings.AccountRoute
import se.pusslet.feature.settings.MoreDestination
import se.pusslet.feature.settings.MoreScreen
import se.pusslet.feature.settings.PrivacyRoute
import se.pusslet.feature.settings.SettingsRoute
import se.pusslet.feature.shopping.PantryRoute
import se.pusslet.feature.shopping.ShoppingRoute
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
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.action_undo
import se.pusslet.ui.resources.nav_calendar
import se.pusslet.ui.resources.nav_meals
import se.pusslet.ui.resources.nav_more
import se.pusslet.ui.resources.nav_shopping
import se.pusslet.ui.resources.nav_today
import se.pusslet.ui.resources.quick_add
import se.pusslet.ui.resources.quick_add_description
import se.pusslet.ui.resources.quickadd_saved
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

    LaunchedEffect(incoming) {
        when (incoming) {
            is IncomingIntent.JoinCode -> navigator.navigate(Route.Join(incoming.code))
            is IncomingIntent.SharedText -> {
                quickAdd.start(incoming.text)
                showQuickAdd = true
            }
            is IncomingIntent.SharedLink -> navigator.navigate(Route.RecipeEditor(link = true, url = incoming.url))
            null -> return@LaunchedEffect
        }
        onIncomingHandled()
    }

    fun onSaved(outcome: QuickAddOutcome) {
        showQuickAdd = false
        scope.launch {
            val result = snackbar.showSnackbar(
                getString(Res.string.quickadd_saved),
                getString(Res.string.action_undo),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) quickAdd.undo(outcome)
        }
    }

    Scaffold(
        bottomBar = { if (navigator.atTabRoot) PussletBottomBar(navigator) },
        floatingActionButton = {
            if (navigator.atTabRoot && navigator.currentTab != TopLevelTab.More) {
                QuickAddButton(onClick = {
                    quickAdd.start()
                    showQuickAdd = true
                })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            AppNavDisplay(navigator, padding)
        }
    }
    if (showQuickAdd) {
        QuickAddSheet(viewModel = quickAdd, onDismiss = { showQuickAdd = false }, onSaved = ::onSaved)
    }
}

@Composable
private fun AppNavDisplay(navigator: AppNavigator, padding: PaddingValues) {
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
                    onPlanDinner = {
                        navigator.mealsFocus = it
                        navigator.navigate(Route.Meals)
                    },
                    onOpenSettings = { navigator.navigate(Route.Settings) },
                    onOpenHandover = { navigator.navigate(Route.Handover(it)) },
                    contentPadding = padding,
                )
            }
            entry<Route.Calendar> {
                CalendarRoute(onOpenOccurrence = openOccurrence, onNewEvent = {
                    navigator.navigate(Route.EventEditor(date = it))
                }, contentPadding = padding)
            }
            entry<Route.Meals> {
                MealsRoute(
                    onOpenRecipe = { navigator.navigate(Route.Recipe(it)) },
                    onOpenLibrary = { navigator.navigate(Route.Recipes) },
                    focusDate = navigator.mealsFocus,
                    onFocusHandled = { navigator.mealsFocus = null },
                    contentPadding = padding,
                )
            }
            entry<Route.Recipes> {
                RecipeLibraryRoute(
                    onBack = navigator::goBack,
                    onOpen = { navigator.navigate(Route.Recipe(it)) },
                    onNew = { navigator.navigate(Route.RecipeEditor()) },
                    onImport = { navigator.navigate(Route.RecipeEditor(link = true)) },
                    onPaste = { navigator.navigate(Route.RecipeEditor(paste = true)) },
                )
            }
            entry<Route.Recipe> { r ->
                RecipeRoute(r.recipeId, onBack = navigator::goBack, onEdit = { navigator.navigate(Route.RecipeEditor(recipeId = it)) })
            }
            entry<Route.RecipeEditor> { r ->
                RecipeEditorRoute(
                    recipeId = r.recipeId,
                    link = r.link,
                    url = r.url,
                    paste = r.paste,
                    onBack = navigator::goBack,
                    onSaved = { id, isNew -> if (isNew) navigator.replace(Route.Recipe(id)) else navigator.goBack() },
                )
            }
            entry<Route.Shopping> {
                ShoppingRoute(
                    onStoreMode = { navigator.navigate(Route.StoreMode(it)) },
                    onPantry = { navigator.navigate(Route.Pantry) },
                    onStores = { navigator.navigate(Route.Stores) },
                    contentPadding = padding,
                )
            }
            entry<Route.StoreMode> { r -> StoreModeRoute(r.listId, onDone = navigator::goBack) }
            entry<Route.Pantry> { PantryRoute(onBack = navigator::goBack) }
            entry<Route.Stores> { StoresRoute(onBack = navigator::goBack) }
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
    // With very large text only the selected tab keeps its label, so labels never wrap or get cut.
    val compact = LocalDensity.current.fontScale > LARGE_FONT_SCALE
    NavigationBar {
        tabs.forEach { spec ->
            val label = stringResource(spec.label)
            NavigationBarItem(
                selected = navigator.currentTab == spec.tab,
                onClick = { navigator.selectTab(spec.tab) },
                // Hidden labels still need a name for TalkBack.
                icon = { Icon(spec.icon, contentDescription = if (compact) label else null) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                alwaysShowLabel = !compact,
            )
        }
    }
}

private const val LARGE_FONT_SCALE = 1.3f
