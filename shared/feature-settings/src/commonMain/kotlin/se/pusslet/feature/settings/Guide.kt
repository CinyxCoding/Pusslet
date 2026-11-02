package se.pusslet.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.Role
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.AppGuide
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.StepBackHandler
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.HelperDimens
import se.pusslet.ui.theme.Spacing

/** Who the guide is written for: parents get the whole app, other adults the helper view they see (ADR-029). */
enum class GuideAudience { Parents, Helpers }

/**
 * One page of the guide through the app (ADR-062): what one part of the app is for. [where] is the way there in the app's
 * own labels ("Mer", "Familjen"), so the guide says the same as the screens it points at.
 */
data class GuidePage(
    val id: String,
    val icon: ImageVector,
    val title: StringResource,
    val body: StringResource,
    val where: List<StringResource> = emptyList(),
)

/** The pages of the guide, in the order the app is met: the day, adding, the tabs, then what lives under Mer. */
object GuidePages {
    fun forAudience(audience: GuideAudience): List<GuidePage> = when (audience) {
        GuideAudience.Parents -> parents
        GuideAudience.Helpers -> helpers
    }

    // The ways there, in the labels of the bottom bar, Mer and Inställningar.
    private val more = Res.string.more_title
    private val settings = listOf(more, Res.string.more_settings)
    private val help = settings + Res.string.help_title

    private val parents = listOf(
        GuidePage("welcome", PussletIcons.Lightbulb, Res.string.guide_welcome_title, Res.string.guide_welcome_body, help),
        GuidePage("today", PussletIcons.Sun, Res.string.guide_today_title, Res.string.guide_today_body, listOf(Res.string.nav_today)),
        GuidePage("add", Icons.Filled.Add, Res.string.guide_add_title, Res.string.guide_add_body),
        GuidePage(
            "calendar",
            PussletIcons.CalendarMonth,
            Res.string.guide_calendar_title,
            Res.string.guide_calendar_body,
            listOf(Res.string.nav_calendar),
        ),
        GuidePage(
            "handoffs",
            PussletIcons.Swap,
            Res.string.guide_handoffs_title,
            Res.string.guide_handoffs_body,
            listOf(Res.string.nav_calendar),
        ),
        GuidePage(
            "meals",
            PussletIcons.Restaurant,
            Res.string.guide_meals_title,
            Res.string.guide_meals_body,
            listOf(Res.string.nav_meals),
        ),
        GuidePage(
            "shopping",
            Icons.Filled.ShoppingCart,
            Res.string.guide_shopping_title,
            Res.string.guide_shopping_body,
            listOf(Res.string.nav_shopping),
        ),
        GuidePage(
            "family",
            PussletIcons.Family,
            Res.string.guide_family_title,
            Res.string.guide_family_body,
            listOf(more, Res.string.more_family),
        ),
        GuidePage(
            "two-homes",
            Icons.Filled.Home,
            Res.string.guide_two_homes_title,
            Res.string.guide_two_homes_body,
            listOf(more, Res.string.more_family),
        ),
        GuidePage(
            "tasks",
            PussletIcons.Task,
            Res.string.guide_tasks_title,
            Res.string.guide_tasks_body,
            listOf(more, Res.string.more_tasks),
        ),
        GuidePage(
            "sick",
            PussletIcons.Beach,
            Res.string.guide_sick_title,
            Res.string.guide_sick_body,
            listOf(more, Res.string.breaks_title),
        ),
        GuidePage(
            "medicine",
            PussletIcons.Medication,
            Res.string.guide_medicine_title,
            Res.string.guide_medicine_body,
            listOf(more, Res.string.medicine_title),
        ),
        GuidePage(
            "notifications",
            Icons.Filled.Notifications,
            Res.string.guide_notifications_title,
            Res.string.guide_notifications_body,
            settings + Res.string.settings_notifications,
        ),
        GuidePage("help", Icons.Filled.Info, Res.string.guide_help_title, Res.string.guide_help_body, help),
    )

    private val helpers = listOf(
        GuidePage(
            "welcome",
            PussletIcons.Lightbulb,
            Res.string.guide_welcome_title,
            Res.string.guide_helper_welcome_body,
            listOf(Res.string.helper_settings),
        ),
        GuidePage("home", PussletIcons.Sun, Res.string.guide_helper_home_title, Res.string.guide_helper_home_body),
        GuidePage("requests", PussletIcons.Swap, Res.string.guide_helper_requests_title, Res.string.guide_helper_requests_body),
        GuidePage(
            "picked-up",
            Icons.Filled.CheckCircle,
            Res.string.guide_helper_picked_up_title,
            Res.string.guide_helper_picked_up_body,
        ),
        GuidePage("week", PussletIcons.CalendarMonth, Res.string.guide_helper_week_title, Res.string.guide_helper_week_body),
        GuidePage(
            "settings",
            Icons.Filled.Settings,
            Res.string.guide_helper_settings_title,
            Res.string.guide_helper_settings_body,
            listOf(Res.string.helper_settings),
        ),
    )
}

/** Which guide this phone gets, and "Klar" or "Visa inte igen" stored so it never opens by itself again (ADR-062). */
class GuideViewModel(private val prefs: PreferencesRepository, sessions: SessionProvider) : ViewModel() {
    val audience: StateFlow<GuideAudience> = sessions.session.map { audienceFor(it.role) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, audienceFor(sessions.session.value.role))

    fun finish() {
        viewModelScope.launch { AppGuide(prefs).done() }
    }

    private fun audienceFor(role: Role) = if (role == Role.Parent) GuideAudience.Parents else GuideAudience.Helpers
}

/**
 * The guide through the app. [onClose] is "Stäng" (and Back on the first page). [offerDontShowAgain] is for the guide that
 * opened by itself after the first start: it adds "Visa inte igen". "Klar" on the last page and "Visa inte igen" both
 * store that the guide is done before closing.
 */
@Composable
fun GuideRoute(onClose: () -> Unit, offerDontShowAgain: Boolean = false, viewModel: GuideViewModel = koinViewModel()) {
    val audience by viewModel.audience.collectAsStateWithLifecycle()
    val finish = {
        viewModel.finish()
        onClose()
    }
    GuideScreen(
        pages = GuidePages.forAudience(audience),
        onClose = onClose,
        onFinish = finish,
        onDontShowAgain = if (offerDontShowAgain) finish else null,
        // The helper view's buttons are 64 dp (ADR-029).
        buttonHeight = if (audience == GuideAudience.Helpers) HelperDimens.button else Spacing.primaryTouchTarget,
    )
}

/**
 * One page at a time, with "Tillbaka" and "Nästa" (or a swipe) between them and "Klar" on the last. The system Back goes
 * to the page before, and closes the guide on the first page.
 */
@Composable
fun GuideScreen(
    pages: List<GuidePage>,
    onClose: () -> Unit,
    onFinish: () -> Unit,
    onDontShowAgain: (() -> Unit)? = null,
    buttonHeight: Dp = Spacing.primaryTouchTarget,
    initialPage: Int = 0,
) {
    val pager = rememberPagerState(initialPage = initialPage) { pages.size }
    val scope = rememberCoroutineScope()
    val page = pager.currentPage
    val goTo: (Int) -> Unit = { target -> scope.launch { pager.animateScrollToPage(target) } }
    StepBackHandler { if (page > 0) goTo(page - 1) else onClose() }
    Scaffold(
        topBar = { GuideTopBar(step = page + 1, total = pages.size, onClose = onClose) },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { goTo(page - 1) }, enabled = page > 0, modifier = Modifier.heightIn(min = buttonHeight)) {
                        Text(stringResource(Res.string.navigate_back))
                    }
                    Spacer(Modifier.weight(1f))
                    val last = page == pages.lastIndex
                    Button(
                        onClick = { if (last) onFinish() else goTo(page + 1) },
                        modifier = Modifier.heightIn(min = buttonHeight),
                    ) { Text(stringResource(if (last) Res.string.action_done else Res.string.action_next)) }
                }
                if (onDontShowAgain != null) {
                    TextButton(onClick = onDontShowAgain, modifier = Modifier.heightIn(min = buttonHeight)) {
                        Text(stringResource(Res.string.guide_dont_show_again))
                    }
                }
            }
        },
    ) { padding ->
        HorizontalPager(pager, Modifier.fillMaxSize().padding(padding), key = { pages[it].id }) { index ->
            GuidePageContent(pages[index])
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuideTopBar(step: Int, total: Int, onClose: () -> Unit) {
    Column {
        TopAppBar(
            title = {
                Text(
                    stringResource(Res.string.onboarding_step, step, total),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Said again by TalkBack when "Nästa" or "Tillbaka" turns the page.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            },
            actions = {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_close)) }
            },
        )
        LinearProgressIndicator(progress = { step.toFloat() / total }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun GuidePageContent(page: GuidePage) {
    val where = page.where.map { stringResource(it) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl, vertical = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    page.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Text(stringResource(page.title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(stringResource(page.body), style = MaterialTheme.typography.bodyLarge)
        if (where.isNotEmpty()) {
            val spoken = stringResource(Res.string.guide_where, where.joinToString(", "))
            Text(
                stringResource(Res.string.guide_where, where.joinToString(" › ")),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                // TalkBack reads "Mer, Familjen" instead of the arrows.
                modifier = Modifier.semantics { contentDescription = spoken },
            )
        }
    }
}
