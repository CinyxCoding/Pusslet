package se.pusslet.feature.today.helper

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
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.helper.HelperAnswer
import se.pusslet.core.helper.HelperDay
import se.pusslet.core.helper.HelperMission
import se.pusslet.core.helper.HelperPlan
import se.pusslet.core.helper.HelperRequest
import se.pusslet.core.helper.HelperTakenOver
import se.pusslet.core.helper.MissionState
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.PackingItem
import se.pusslet.core.model.PackingReason
import se.pusslet.core.tasks.TaskOccurrence
import se.pusslet.core.today.Greeting
import se.pusslet.core.today.TodayBlock
import se.pusslet.feature.today.SEPARATOR
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.CheckRow
import se.pusslet.ui.components.FirstTimeHint
import se.pusslet.ui.components.HeroCard
import se.pusslet.ui.components.InfoPill
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.NavRow
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.Section
import se.pusslet.ui.components.TabHeader
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.relativeDay
import se.pusslet.ui.format.weekLong
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.greeting_day
import se.pusslet.ui.resources.greeting_evening
import se.pusslet.ui.resources.greeting_morning
import se.pusslet.ui.resources.greeting_named
import se.pusslet.ui.resources.greeting_night
import se.pusslet.ui.resources.helper_answer_spoken
import se.pusslet.ui.resources.helper_answered_no
import se.pusslet.ui.resources.helper_answered_yes
import se.pusslet.ui.resources.helper_cannot_today
import se.pusslet.ui.resources.helper_cannot_waiting
import se.pusslet.ui.resources.helper_change_answer
import se.pusslet.ui.resources.helper_done_today
import se.pusslet.ui.resources.helper_hint_home
import se.pusslet.ui.resources.helper_more_requests
import se.pusslet.ui.resources.helper_more_requests_one
import se.pusslet.ui.resources.helper_next_both
import se.pusslet.ui.resources.helper_next_dropoff
import se.pusslet.ui.resources.helper_next_pickup
import se.pusslet.ui.resources.helper_no
import se.pusslet.ui.resources.helper_nothing_today
import se.pusslet.ui.resources.helper_offline_pending
import se.pusslet.ui.resources.helper_request_dropoff
import se.pusslet.ui.resources.helper_request_from
import se.pusslet.ui.resources.helper_request_pickup
import se.pusslet.ui.resources.helper_settings
import se.pusslet.ui.resources.helper_shopping
import se.pusslet.ui.resources.helper_soon
import se.pusslet.ui.resources.helper_taken_dropoff
import se.pusslet.ui.resources.helper_taken_pickup
import se.pusslet.ui.resources.helper_week
import se.pusslet.ui.resources.helper_yes_dropoff
import se.pusslet.ui.resources.helper_yes_pickup
import se.pusslet.ui.resources.pickup_done_action
import se.pusslet.ui.resources.sick_card_title
import se.pusslet.ui.resources.today
import se.pusslet.ui.resources.today_block_packing
import se.pusslet.ui.resources.today_block_shopping
import se.pusslet.ui.resources.today_block_tasks
import se.pusslet.ui.resources.today_block_upcoming
import se.pusslet.ui.resources.today_overdue
import se.pusslet.ui.resources.today_packing_reason_event
import se.pusslet.ui.resources.today_packing_reason_handover
import se.pusslet.ui.resources.today_request_someone
import se.pusslet.ui.theme.HelperDimens
import se.pusslet.ui.theme.HelperMissionStyle
import se.pusslet.ui.theme.Spacing

/**
 * The helper's home screen (UX plan Steg 3, ADR-029): one screen that answers "when do I pick up or drop off, and whom?".
 * No +, no bottom bar; the rest is one step away through the rows at the bottom.
 */
@Composable
fun HelperHomeRoute(
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onOpenWeek: () -> Unit,
    onOpenShopping: () -> Unit,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: HelperViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HelperHomeScreen(
        state = state,
        contentPadding = contentPadding,
        actions = HelperActions(
            onAccept = viewModel::accept,
            onDecline = viewModel::decline,
            onChangeAnswer = viewModel::changeAnswer,
            onCannotMake = viewModel::cannotMake,
            onPickedUp = viewModel::pickedUp,
            onTogglePacking = viewModel::setPacked,
            onToggleTask = viewModel::setTaskDone,
            onOpenOccurrence = onOpenOccurrence,
            onOpenWeek = onOpenWeek,
            onOpenShopping = onOpenShopping,
            onOpenSettings = onOpenSettings,
            onDismissHint = viewModel::dismissHint,
        ),
    )
}

/**
 * What a tap on the helper's home screen can do. The defaults do nothing, for previews and screenshot tests. A null
 * navigation action is not offered at all (the parents' preview stays on its page): the rows are then neither tappable
 * nor shown with a chevron, and the rows at the bottom are left out.
 */
data class HelperActions(
    val onAccept: (HelperRequest) -> Unit = {},
    val onDecline: (HelperRequest) -> Unit = {},
    val onChangeAnswer: (HelperRequest) -> Unit = {},
    /** "Säg till Sara och Johan" in the "Jag kan inte idag" sheet. */
    val onCannotMake: (List<HelperMission>) -> Unit = {},
    val onTogglePacking: (LocalDate, PackingItem, Boolean) -> Unit = { _, _, _ -> },
    val onToggleTask: (TaskOccurrence, Boolean) -> Unit = { _, _ -> },
    val onOpenOccurrence: ((EventOccurrence) -> Unit)? = {},
    val onOpenWeek: (() -> Unit)? = {},
    val onOpenShopping: (() -> Unit)? = {},
    val onOpenSettings: (() -> Unit)? = {},
    /**
     * "Jag förstår" on the first-time hint. Without it the hint never shows, so a preview ("Så ser Bengt appen") neither
     * shows nor dismisses Bengt's hint.
     */
    val onDismissHint: (() -> Unit)? = null,
    /** "Hämtad" on a pick-up (ADR-048); null in the parents' preview, where nothing is saved. */
    val onPickedUp: ((HelperMission) -> Unit)? = null,
)

/** [footer] is a last row below everything, e.g. the preview's "Bengt ser inte 2 händelser den här veckan." */
@Composable
fun HelperHomeScreen(
    state: HelperUiState,
    contentPadding: PaddingValues = PaddingValues(),
    actions: HelperActions = HelperActions(),
    footer: (@Composable () -> Unit)? = null,
) {
    val plan = state.plan
    if (state.loading || plan == null) {
        LoadingBox()
        return
    }
    var allRequests by rememberSaveable { mutableStateOf(false) }
    var cannotMake by rememberSaveable { mutableStateOf(false) }
    val requests = shownRequests(plan.requests, allRequests)
    val hiddenRequests = plan.requests.size - requests.size
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screenHorizontal,
            end = Spacing.screenHorizontal,
            top = Spacing.sm + contentPadding.calculateTopPadding(),
            bottom = Spacing.xxl + contentPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(HelperDimens.gap),
    ) {
        item(key = "header") { Header(state, plan) }
        // "Ella är sjuk idag": her pick-up is already gone, this says why (ADR-047).
        items(plan.sickToday, key = { "sick-${it.id}" }) { child ->
            Banner(stringResource(Res.string.sick_card_title, child.name), kind = BannerKind.Warning)
        }
        // Questions come first and can never be hidden.
        items(requests, key = { "request-${it.request.id}" }) { RequestCard(it, plan.date, state.waitingForNetwork, actions) }
        if (hiddenRequests > 0) {
            item(key = "more-requests") {
                val text = if (hiddenRequests == 1) {
                    stringResource(Res.string.helper_more_requests_one)
                } else {
                    stringResource(Res.string.helper_more_requests, hiddenRequests)
                }
                NavRow(text, onClick = { allRequests = true }, modifier = Modifier.heightIn(min = HelperDimens.row))
            }
        }
        val dismissHint = actions.onDismissHint
        if (state.showHint && dismissHint != null) {
            item(key = "hint") { FirstTimeHint(stringResource(Res.string.helper_hint_home), onDismiss = dismissHint) }
        }
        item(key = "today") { TodayCard(state, plan, actions) }
        if (plan.cannotMakeCandidates.isNotEmpty()) {
            item(key = "cannot-make") {
                TextButton(onClick = { cannotMake = true }, modifier = Modifier.heightIn(min = HelperDimens.button)) {
                    Text(stringResource(Res.string.helper_cannot_today))
                }
            }
        }
        items(state.sections, key = { "section-${it.id}" }) { block ->
            Column(Modifier.padding(top = Spacing.sectionGap - HelperDimens.gap)) {
                when (block) {
                    TodayBlock.Packing -> PackingSection(plan, actions)
                    TodayBlock.Tasks -> TasksSection(plan, actions)
                    TodayBlock.Upcoming -> UpcomingSection(plan.upcoming, actions.onOpenWeek)
                    else -> Unit
                }
            }
        }
        item(key = "rows") { BottomRows(state, actions) }
        if (footer != null) item(key = "footer") { footer() }
    }
    if (cannotMake) {
        CannotMakeSheet(
            missions = plan.cannotMakeCandidates,
            parents = state.parents,
            onSend = { chosen ->
                actions.onCannotMake(chosen)
                cannotMake = false
            },
            onDismiss = { cannotMake = false },
        )
    }
}

/** Answered cards stay in place; of the open ones only the first shows until "1 fråga till ›" is tapped. */
private fun shownRequests(all: List<HelperRequest>, showAll: Boolean): List<HelperRequest> {
    if (showAll) return all
    val firstOpen = all.firstOrNull { it.isOpen }
    return all.filter { !it.isOpen || it === firstOpen }
}

/** "Torsdag 8 oktober" and "Vecka 41 · God morgon, Bengt": the helper view keeps the greeting, and the week is always there. */
@Composable
private fun Header(state: HelperUiState, plan: HelperPlan) {
    val greeting = stringResource(
        when (plan.greeting) {
            Greeting.Morning -> Res.string.greeting_morning
            Greeting.Day -> Res.string.greeting_day
            Greeting.Evening -> Res.string.greeting_evening
            Greeting.Night -> Res.string.greeting_night
        },
    )
    val subtitle = buildList {
        add(weekLong(plan.week.week))
        plan.holidays.forEach { add(stringResource(Labels.holiday(it.id))) }
        add(state.me?.let { stringResource(Res.string.greeting_named, greeting, it.name) } ?: greeting)
    }.joinToString(SEPARATOR)
    TabHeader(title = formatDateLong(plan.date).replaceFirstChar { it.uppercase() }, subtitle = subtitle)
}

/**
 * "Sara undrar": outlined, the question as a sentence and Ja/Nej stacked at full width. After an answer the card stays
 * with what was said and "Ändra svar" (while a parent's phone has not written it in).
 */
@Composable
private fun RequestCard(r: HelperRequest, today: LocalDate, waitingForNetwork: Boolean, actions: HelperActions) {
    val colors = MaterialTheme.colorScheme
    val from = r.from?.name ?: stringResource(Res.string.today_request_someone)
    val children = names(r.children)
    val pickUp = r.kind == HandoffKind.PickUp
    val time = whenText(r.date, r.time, today)
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = colors.surface)) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(HelperDimens.gap)) {
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(
                    stringResource(Res.string.helper_request_from, from),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(if (pickUp) Res.string.helper_request_pickup else Res.string.helper_request_dropoff, children, time),
                    style = HelperMissionStyle,
                )
                r.place?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant) }
            }
            when (r.answer) {
                null -> {
                    val yes = stringResource(if (pickUp) Res.string.helper_yes_pickup else Res.string.helper_yes_dropoff, children)
                    val spokenYes = stringResource(Res.string.helper_answer_spoken, yes, time)
                    Button(
                        onClick = { actions.onAccept(r) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = HelperDimens.button).semantics { contentDescription = spokenYes },
                    ) { Text(yes) }
                    OutlinedButton(
                        onClick = { actions.onDecline(r) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = HelperDimens.button),
                    ) { Text(stringResource(Res.string.helper_no)) }
                }
                else -> {
                    val said = if (r.answer == HelperAnswer.Yes) Res.string.helper_answered_yes else Res.string.helper_answered_no
                    Text(stringResource(said, from), style = MaterialTheme.typography.bodyLarge)
                    if (waitingForNetwork) {
                        Text(stringResource(Res.string.helper_offline_pending), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (r.canChangeAnswer) {
                        TextButton(onClick = { actions.onChangeAnswer(r) }, modifier = Modifier.heightIn(min = HelperDimens.button)) {
                            Text(stringResource(Res.string.helper_change_answer))
                        }
                    }
                }
            }
        }
    }
}

/** "Idag": the one focus surface. His missions, or "Inget för dig idag." and "Nästa gång: …". */
@Composable
private fun TodayCard(state: HelperUiState, plan: HelperPlan, actions: HelperActions) {
    val packingShown = TodayBlock.Packing in state.sections
    HeroCard {
        Text(
            stringResource(Res.string.today),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() }.padding(top = Spacing.xs),
        )
        val active = plan.activeMissions
        val open = actions.onOpenOccurrence
        active.forEachIndexed { index, m ->
            MissionRow(
                m = m,
                // "Ska med" has the check boxes for a drop-off; the line would say the same thing twice.
                showBring = m.kind == HandoffKind.PickUp || !packingShown,
                weather = if (index == 0 && state.showWeather) plan.weather?.let { weatherText(it) } else null,
                onOpen = open?.let { { it(m.occurrence) } },
            )
            val pickedUp = actions.onPickedUp
            if (m.confirmable && pickedUp != null) {
                Button(onClick = { pickedUp(m) }, modifier = Modifier.fillMaxWidth().heightIn(min = HelperDimens.button)) {
                    Text(stringResource(Res.string.pickup_done_action))
                }
            }
        }
        plan.takenOver.forEach { TakenOverLine(it) }
        when {
            plan.allDoneToday -> CardLine(stringResource(Res.string.helper_done_today))
            active.isEmpty() -> CardLine(stringResource(Res.string.helper_nothing_today))
        }
        plan.next?.let { NextLine(it) }
    }
}

/**
 * "Du hämtar Simon 15:30" with "Snart" from an hour before, the place and at most a few short lines. One TalkBack node;
 * without [onOpen] (the parents' preview) it is not tappable and has no chevron.
 */
@Composable
private fun MissionRow(m: HelperMission, showBring: Boolean, weather: String?, onOpen: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = HelperDimens.row)
            .then(openable(onOpen))
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(missionText(m), style = HelperMissionStyle)
            listOfNotNull(m.title, m.place).joinToString(SEPARATOR).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            }
            if (showBring) bringText(m)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            m.after.forEach { Text(afterText(it), style = MaterialTheme.typography.bodyLarge) }
            weather?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (m.waitingForTakeover) Text(stringResource(Res.string.helper_cannot_waiting), style = MaterialTheme.typography.bodyLarge)
        }
        if (m.state == MissionState.Soon) InfoPill(stringResource(Res.string.helper_soon), selected = true)
        if (onOpen != null) Icon(PussletIcons.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
    }
}

/** Tappable with [onClick]; otherwise one TalkBack node that does nothing, so nothing looks tappable that is not. */
private fun openable(onClick: (() -> Unit)?): Modifier =
    if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier.semantics(mergeDescendants = true) {}

/** "Johan hämtar Simon i stället". */
@Composable
private fun TakenOverLine(t: HelperTakenOver) {
    val by = t.by?.name ?: stringResource(Res.string.today_request_someone)
    val resource = if (t.kind == HandoffKind.PickUp) Res.string.helper_taken_pickup else Res.string.helper_taken_dropoff
    CardLine(stringResource(resource, by, names(t.children)))
}

/** "Nästa gång: torsdag 15 oktober, du hämtar Simon och Ella." */
@Composable
private fun NextLine(day: HelperDay) {
    val date = formatDateLong(day.date)
    val dropOffs = day.missions.filter { it.kind == HandoffKind.DropOff }.flatMap { it.children }.distinct()
    val pickUps = day.missions.filter { it.kind == HandoffKind.PickUp }.flatMap { it.children }.distinct()
    val text = when {
        dropOffs.isEmpty() -> stringResource(Res.string.helper_next_pickup, date, names(pickUps))
        pickUps.isEmpty() -> stringResource(Res.string.helper_next_dropoff, date, names(dropOffs))
        else -> stringResource(Res.string.helper_next_both, date, names(dropOffs), names(pickUps))
    }
    CardLine(text)
}

@Composable
private fun CardLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm))
}

/** "Ska med": check boxes for the children he drops off (and what moves between homes). */
@Composable
private fun PackingSection(plan: HelperPlan, actions: HelperActions) {
    Section(stringResource(Res.string.today_block_packing)) {
        val several = plan.packing.size > 1
        plan.packing.forEach { child ->
            if (several) Text(child.child.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.xs))
            child.items.forEach { item ->
                val reason = when (val r = item.reason) {
                    is PackingReason.ForEvent -> stringResource(Res.string.today_packing_reason_event, r.eventTitle)
                    PackingReason.Handover -> stringResource(Res.string.today_packing_reason_handover)
                    is PackingReason.Rule -> null
                }
                CheckRow(
                    item.text,
                    item.checked,
                    onCheckedChange = { actions.onTogglePacking(plan.date, item, it) },
                    modifier = Modifier.heightIn(min = HelperDimens.row),
                    supporting = reason,
                )
            }
        }
    }
}

/** "Att göra": shared tasks that are his, or nobody's on a day he has a mission. At most three. */
@Composable
private fun TasksSection(plan: HelperPlan, actions: HelperActions) {
    Section(stringResource(Res.string.today_block_tasks)) {
        plan.tasks.forEach { t ->
            val supporting = t.date?.let { d ->
                when {
                    d < plan.date -> stringResource(Res.string.today_overdue)
                    d == plan.date -> null
                    else -> relativeDay(d, plan.date)
                }
            }
            CheckRow(
                t.task.title,
                t.done,
                onCheckedChange = { actions.onToggleTask(t, it) },
                modifier = Modifier.heightIn(min = HelperDimens.row),
                supporting = supporting,
            )
        }
    }
}

/** "Kommande": his missions the next days, one row per day; a tap opens "Hela veckan" (when [onOpenWeek] is given). */
@Composable
internal fun UpcomingSection(days: List<HelperDay>, onOpenWeek: (() -> Unit)?) {
    Section(stringResource(Res.string.today_block_upcoming)) {
        days.forEach { day ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = HelperDimens.row)
                    .then(openable(onOpenWeek))
                    .padding(vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(dayTitle(day.date, day.week), style = MaterialTheme.typography.titleSmall)
                    Text(dayText(day.missions), style = MaterialTheme.typography.bodyLarge)
                }
                if (onOpenWeek != null) {
                    Icon(PussletIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * "Hela veckan ›", "Inköpslistan · 5 kvar ›" (only when switched on) and "Inställningar och hjälp ›". A row whose action is
 * not offered is left out.
 */
@Composable
private fun BottomRows(state: HelperUiState, actions: HelperActions) {
    val row = Modifier.heightIn(min = HelperDimens.row)
    val openWeek = actions.onOpenWeek
    val openShopping = actions.onOpenShopping
    val openSettings = actions.onOpenSettings
    Column(Modifier.padding(top = Spacing.lg)) {
        if (openWeek != null) NavRow(stringResource(Res.string.helper_week), openWeek, modifier = row)
        val shopping = state.shopping
        if (shopping != null && openShopping != null) {
            val text = if (shopping.remaining > 0) {
                stringResource(Res.string.helper_shopping, shopping.remaining)
            } else {
                stringResource(Res.string.today_block_shopping)
            }
            NavRow(text, openShopping, modifier = row)
        }
        if (openSettings != null) NavRow(stringResource(Res.string.helper_settings), openSettings, modifier = row)
    }
}
