package se.pusslet.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.data.session.Session
import se.pusslet.data.session.SessionProvider
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionHeader
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import se.pusslet.core.model.Role as MemberRole

enum class FaqGroup(val title: StringResource) {
    Family(Res.string.faq_group_family),
    Calendar(Res.string.faq_group_calendar),
    Notifications(Res.string.faq_group_notifications),
    Food(Res.string.faq_group_food),
    Other(Res.string.faq_group_other),
}

/** Who a question is shown to: what only parents can do is not offered to other adults, like in Mer. */
enum class FaqFor { Everyone, Parents, ExampleFamily }

/** One common problem and what to do about it (ADR-062). [id] is stable: it keeps the question open across rotation. */
data class FaqEntry(
    val id: String,
    val group: FaqGroup,
    val question: StringResource,
    val answer: StringResource,
    val shownTo: FaqFor = FaqFor.Everyone,
)

/**
 * "Vanliga frågor": typical problems in the app, each answered in the app's own words for buttons and screens. The answers
 * describe what the app does today; a change in behaviour changes the answer too.
 */
object Faq {
    val entries: List<FaqEntry> = listOf(
        FaqEntry("sync", FaqGroup.Family, Res.string.faq_sync_q, Res.string.faq_sync_a),
        FaqEntry("invite", FaqGroup.Family, Res.string.faq_invite_q, Res.string.faq_invite_a, FaqFor.Parents),
        FaqEntry("new-phone", FaqGroup.Family, Res.string.faq_new_phone_q, Res.string.faq_new_phone_a),
        FaqEntry("leave", FaqGroup.Family, Res.string.faq_leave_q, Res.string.faq_leave_a),
        FaqEntry("roles", FaqGroup.Family, Res.string.faq_roles_q, Res.string.faq_roles_a),
        FaqEntry("shared-event", FaqGroup.Calendar, Res.string.faq_shared_event_q, Res.string.faq_shared_event_a, FaqFor.Parents),
        FaqEntry("subscription", FaqGroup.Calendar, Res.string.faq_subscription_q, Res.string.faq_subscription_a, FaqFor.Parents),
        FaqEntry("device-calendar", FaqGroup.Calendar, Res.string.faq_device_calendar_q, Res.string.faq_device_calendar_a, FaqFor.Parents),
        FaqEntry("notifications", FaqGroup.Notifications, Res.string.faq_notifications_q, Res.string.faq_notifications_a),
        FaqEntry("quiet-hours", FaqGroup.Notifications, Res.string.faq_quiet_hours_q, Res.string.faq_quiet_hours_a, FaqFor.Parents),
        FaqEntry("picked-up", FaqGroup.Notifications, Res.string.faq_picked_up_q, Res.string.faq_picked_up_a),
        FaqEntry("medicine", FaqGroup.Notifications, Res.string.faq_medicine_q, Res.string.faq_medicine_a, FaqFor.Parents),
        FaqEntry("lunch", FaqGroup.Food, Res.string.faq_lunch_q, Res.string.faq_lunch_a, FaqFor.Parents),
        FaqEntry("recipe", FaqGroup.Food, Res.string.faq_recipe_q, Res.string.faq_recipe_a, FaqFor.Parents),
        FaqEntry("barcode", FaqGroup.Food, Res.string.faq_barcode_q, Res.string.faq_barcode_a),
        FaqEntry("undo", FaqGroup.Other, Res.string.faq_undo_q, Res.string.faq_undo_a),
        FaqEntry("offline", FaqGroup.Other, Res.string.faq_offline_q, Res.string.faq_offline_a),
        FaqEntry("weather", FaqGroup.Other, Res.string.faq_weather_q, Res.string.faq_weather_a, FaqFor.Parents),
        FaqEntry("widget", FaqGroup.Other, Res.string.faq_widget_q, Res.string.faq_widget_a),
        FaqEntry("demo", FaqGroup.Other, Res.string.faq_demo_q, Res.string.faq_demo_a, FaqFor.ExampleFamily),
    )

    /** The questions for whoever uses this phone, by group in the order of [FaqGroup]; empty groups are left out. */
    fun groupsFor(session: Session): List<Pair<FaqGroup, List<FaqEntry>>> {
        val shown = entries.filter { entry ->
            when (entry.shownTo) {
                FaqFor.Everyone -> true
                FaqFor.Parents -> session.role == MemberRole.Parent
                FaqFor.ExampleFamily -> session.demo
            }
        }
        return FaqGroup.entries.mapNotNull { group -> shown.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { group to it } }
    }
}

class FaqViewModel(sessions: SessionProvider) : ViewModel() {
    val groups: StateFlow<List<Pair<FaqGroup, List<FaqEntry>>>> = sessions.session.map(Faq::groupsFor)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Faq.groupsFor(sessions.session.value))
}

/**
 * [onOpenGuide] opens the guide through the app from the end of the list. [rowHeight] is `HelperDimens.row` in the helper
 * view (ADR-029).
 */
@Composable
fun FaqRoute(onBack: () -> Unit, onOpenGuide: () -> Unit, rowHeight: Dp = Spacing.touchTarget, viewModel: FaqViewModel = koinViewModel()) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    FaqScreen(groups, onBack, onOpenGuide, rowHeight)
}

@Composable
fun FaqScreen(
    groups: List<Pair<FaqGroup, List<FaqEntry>>>,
    onBack: () -> Unit,
    onOpenGuide: () -> Unit,
    rowHeight: Dp = Spacing.touchTarget,
    initiallyOpen: Set<String> = emptySet(),
) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen.toList()) }
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.help_faq), onBack = onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = Spacing.xxl + padding.calculateBottomPadding(),
            ),
        ) {
            groups.forEach { (group, entries) ->
                item(key = "group-${group.name}") {
                    SectionHeader(
                        stringResource(group.title),
                        Modifier.padding(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, top = Spacing.lg),
                    )
                }
                items(entries, key = { it.id }) { entry ->
                    FaqRow(entry, expanded = entry.id in open, rowHeight = rowHeight) {
                        open = if (entry.id in open) open - entry.id else open + entry.id
                    }
                }
            }
            item(key = "guide") {
                OutlinedButton(
                    onClick = onOpenGuide,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, top = Spacing.xl)
                        .heightIn(min = maxOf(rowHeight, Spacing.primaryTouchTarget)),
                ) { Text(stringResource(Res.string.help_guide)) }
            }
        }
    }
}

/** A question that opens its answer underneath; TalkBack says whether it is open. */
@Composable
private fun FaqRow(entry: FaqEntry, expanded: Boolean, rowHeight: Dp, onToggle: () -> Unit) {
    val state = stringResource(if (expanded) Res.string.state_expanded else Res.string.state_collapsed)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = rowHeight)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = state }
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(entry.question), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Text(
                stringResource(entry.answer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, bottom = Spacing.md),
            )
        }
    }
}
