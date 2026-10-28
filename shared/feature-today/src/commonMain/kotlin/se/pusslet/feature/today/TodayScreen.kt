package se.pusslet.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.conflicts.Conflict
import se.pusslet.core.medicine.MedicineRules
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffReceipt
import se.pusslet.core.model.PackingItem
import se.pusslet.core.tasks.TaskOccurrence
import se.pusslet.core.today.CaregiverCannot
import se.pusslet.core.today.ContextCard
import se.pusslet.core.today.CustodyNote
import se.pusslet.core.today.HandoffStop
import se.pusslet.core.today.PickedUp
import se.pusslet.core.today.Pickup
import se.pusslet.core.today.TodayBlock
import se.pusslet.core.today.TodayPlan
import se.pusslet.core.today.UnconfirmedPickup
import se.pusslet.feature.today.medicine.MedicineTodayCard
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.FirstTimeHint
import se.pusslet.ui.components.InfoPill
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.NavRow
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.QuietLine
import se.pusslet.ui.components.RegisterAddButton
import se.pusslet.ui.components.SmallAction
import se.pusslet.ui.components.TabHeader
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.format.relativeDay
import se.pusslet.ui.format.weekLong
import se.pusslet.ui.format.weekdayName
import se.pusslet.ui.format.weekdayShort
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.action_add
import se.pusslet.ui.resources.action_cancel
import se.pusslet.ui.resources.action_undo
import se.pusslet.ui.resources.card_action_open
import se.pusslet.ui.resources.card_action_take
import se.pusslet.ui.resources.card_busy_dropoff
import se.pusslet.ui.resources.card_busy_pickup
import se.pusslet.ui.resources.card_double_booked
import se.pusslet.ui.resources.card_helper_cannot_dropoff
import se.pusslet.ui.resources.card_helper_cannot_pickup
import se.pusslet.ui.resources.card_nobody_dropoff
import se.pusslet.ui.resources.card_nobody_pickup
import se.pusslet.ui.resources.card_unassigned_dropoff
import se.pusslet.ui.resources.card_unassigned_pickup
import se.pusslet.ui.resources.error_generic
import se.pusslet.ui.resources.pickup_done_action
import se.pusslet.ui.resources.pickup_done_line
import se.pusslet.ui.resources.pickup_unconfirmed
import se.pusslet.ui.resources.pickup_unconfirmed_nobody
import se.pusslet.ui.resources.search_title
import se.pusslet.ui.resources.sick_report_action
import se.pusslet.ui.resources.today_custody
import se.pusslet.ui.resources.today_custody_handover
import se.pusslet.ui.resources.today_customize
import se.pusslet.ui.resources.today_demo_family
import se.pusslet.ui.resources.today_demo_leave
import se.pusslet.ui.resources.today_demo_leave_body
import se.pusslet.ui.resources.today_demo_leave_confirm
import se.pusslet.ui.resources.today_demo_leave_title
import se.pusslet.ui.resources.today_hint_add
import se.pusslet.ui.resources.today_hint_add_generic
import se.pusslet.ui.resources.today_more_problems
import se.pusslet.ui.resources.today_quiet_end
import se.pusslet.ui.resources.today_request_answer
import se.pusslet.ui.resources.today_request_dropoff
import se.pusslet.ui.resources.today_request_pickup
import se.pusslet.ui.resources.today_request_someone
import se.pusslet.ui.resources.today_tomorrow_title
import se.pusslet.ui.theme.Spacing

/**
 * Idag (UX plan, Idag): the answer first. The header and "Att lösa" are fixed; Morgonkollen and the sections follow the
 * viewer's layout, and "Anpassa Idag" is always the last row ([onCustomize]).
 */
@Composable
fun TodayRoute(
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onPlanDinner: (LocalDate) -> Unit,
    onOpenSettings: () -> Unit,
    onQuickAdd: (LocalDate?) -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenShopping: () -> Unit = {},
    onOpenTasks: (() -> Unit)? = null,
    onCustomize: () -> Unit = {},
    /** "Lägg till Ellas skola" on "Kom igång": the child's editor. */
    onOpenChild: (childId: String) -> Unit = {},
    /** "Bjud in Johan" on "Kom igång": an invite that claims that member. */
    onInvite: (memberId: String) -> Unit = {},
    /** "Medicin och feber" (ADR-050), for a child. */
    onOpenMedicine: (childId: String) -> Unit = {},
    /** "Lov och vistelsetider" (ADR-051). */
    onOpenBreaks: () -> Unit = {},
    /** "Sök" (ADR-052), from the header. */
    onSearch: () -> Unit = {},
    onOpenKids: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: TodayViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val platform = LocalPlatformActions.current
    // The round + opens quick add, which starts on today. Caregivers cannot save anything there, so they get no + (UX plan 1.13).
    RegisterAddButton(label = stringResource(Res.string.action_add), enabled = state.canEdit) { onQuickAdd(null) }
    TodayScreen(
        state = state,
        contentPadding = contentPadding,
        actions = TodayActions(
            onTogglePacking = viewModel::setPacked,
            onToggleTask = viewModel::setTaskDone,
            onTakeHandoff = viewModel::takeHandoff,
            onAddGiftTask = viewModel::addGiftTask,
            onOpenOccurrence = onOpenOccurrence,
            onPlanDinner = onPlanDinner,
            onOpenSettings = onOpenSettings,
            onOpenCalendar = onOpenCalendar,
            onOpenShopping = onOpenShopping,
            onOpenTasks = onOpenTasks,
            onCustomize = onCustomize,
            onQuickAdd = { onQuickAdd(null) },
            onOpenChild = onOpenChild,
            onInvite = onInvite,
            onHideSetup = viewModel::hideSetup,
            onDismissHint = viewModel::dismissAddHint,
            onLeaveDemo = viewModel::leaveDemo,
            sick = SickActions(
                onReport = viewModel::reportSick,
                onUndo = viewModel::undoSick,
                onHomeWith = viewModel::setHomeWith,
                onVab = viewModel::setVab,
                onVabPart = viewModel::setVabPart,
                onAbsenceReported = viewModel::setAbsenceReported,
                onWorkTold = viewModel::setWorkTold,
                onVabReportedDay = viewModel::setVabReported,
                onStillSick = viewModel::stillSick,
                onWellAgain = viewModel::wellAgain,
                onVabReported = viewModel::vabReported,
                onOpenForsakringskassan = { platform.openUrl(FORSAKRINGSKASSAN_URL) },
                onOpenMedicine = onOpenMedicine,
            ),
            onGiveDose = viewModel::giveDose,
            onPlanBreaks = onOpenBreaks,
            onSearch = onSearch,
            onOpenKids = onOpenKids,
            onPickedUp = viewModel::pickedUp,
            onConfirmPickup = viewModel::confirmPickup,
            onUndoPickup = viewModel::undoPickup,
        ),
    )
}

/** What a tap on Idag can do. The defaults do nothing, for previews and screenshot tests of the screen alone. */
data class TodayActions(
    val onTogglePacking: (LocalDate, PackingItem, Boolean) -> Unit = { _, _, _ -> },
    val onToggleTask: (TaskOccurrence, Boolean) -> Unit = { _, _ -> },
    val onTakeHandoff: (EventOccurrence, HandoffKind) -> Unit = { _, _ -> },
    val onAddGiftTask: (EventOccurrence, String) -> Unit = { _, _ -> },
    val onOpenOccurrence: (EventOccurrence) -> Unit = {},
    val onPlanDinner: (LocalDate) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    /** "Fler att lösa" and "Visa hela dagen". */
    val onOpenCalendar: () -> Unit = {},
    /** The "Inköpslistan" row. */
    val onOpenShopping: () -> Unit = {},
    /** "Visa alla (n)" under "Att göra"; null while there is no task screen to open (the list is only capped). */
    val onOpenTasks: (() -> Unit)? = null,
    /** "Anpassa Idag". */
    val onCustomize: () -> Unit = {},
    /** Quick add, the same as the round + ("Lägg till en aktivitet" on "Kom igång"). */
    val onQuickAdd: () -> Unit = {},
    /** "Lägg till Ellas skola": the child's editor. */
    val onOpenChild: (String) -> Unit = {},
    /** "Bjud in Johan": an invite for that member. */
    val onInvite: (String) -> Unit = {},
    /** "Dölj" on "Kom igång". */
    val onHideSetup: () -> Unit = {},
    /**
     * "Jag förstår" on the hint about +. Without it the hint never shows, so previews and screenshots of the screen alone
     * neither show nor dismiss it.
     */
    val onDismissHint: (() -> Unit)? = null,
    /** "Börja" in the "Börja på riktigt?" dialog of the example family. */
    val onLeaveDemo: () -> Unit = {},
    /** "Sjuk idag?" and the sick-day cards (ADR-047). */
    val sick: SickActions = SickActions(),
    /** "Hämtad" on my own pick-up line (ADR-048). */
    val onPickedUp: (List<HandoffStop>) -> Unit = {},
    /** "Hämtad" on somebody else's pick-up that nobody ticked off. */
    val onConfirmPickup: (Pickup) -> Unit = {},
    /** "Ångra" on my own "Du hämtade Ella 15:52". */
    val onUndoPickup: (HandoffReceipt) -> Unit = {},
    /** "Given" on a planned dose in "Medicin idag" (ADR-050). */
    val onGiveDose: (MedicineRules.Slot) -> Unit = {},
    /** "Planera" on a coming school break (ADR-051). */
    val onPlanBreaks: () -> Unit = {},
    val onSearch: () -> Unit = {},
    /** "Barnen": ticks to approve and veckopeng (ADR-055). */
    val onOpenKids: () -> Unit = {},
)

@Composable
fun TodayScreen(state: TodayUiState, contentPadding: PaddingValues = PaddingValues(), actions: TodayActions = TodayActions()) {
    val plan = state.plan
    if (state.loading || plan == null) {
        LoadingBox()
        return
    }
    val problems = plan.problems
    var reportingSick by rememberSaveable { mutableStateOf(false) }
    val canReportSick = state.canEdit && state.sick.children.isNotEmpty()
    if (reportingSick) {
        SickReportSheet(
            children = state.sick.children,
            today = plan.date,
            onReport = { ids, date ->
                reportingSick = false
                actions.sick.onReport(ids, date)
            },
            onDismiss = { reportingSick = false },
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screenHorizontal,
            end = Spacing.screenHorizontal,
            top = Spacing.sm + contentPadding.calculateTopPadding(),
            bottom = Spacing.listBottomInset + contentPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "header") {
            Header(plan, onReportSick = if (canReportSick) ({ reportingSick = true }) else null, onSearch = actions.onSearch)
        }
        if (state.demo) item(key = "demo") { DemoRow(state.leavingDemo, state.leaveDemoFailed, actions.onLeaveDemo) }
        // "Att lösa" can never be hidden: problems today and tomorrow, then requests to me.
        items(problems.take(TodayModel.MAX_PROBLEMS), key = { problemKey(it) }) { ProblemBanner(it, state, actions) }
        if (problems.size > TodayModel.MAX_PROBLEMS) {
            item(key = "more-problems") {
                NavRow(stringResource(Res.string.today_more_problems, problems.size - TodayModel.MAX_PROBLEMS), actions.onOpenCalendar)
            }
        }
        items(plan.cannotMake, key = { "cannot-${it.request.id}" }) { CannotMakeBanner(it, state, actions) }
        items(state.requests, key = { "request-${it.request.id}" }) { RequestBanner(it, plan.date, actions.onOpenOccurrence) }
        // Sick days (ADR-047): today's and tomorrow's, the morning question and VAB left to report.
        items(state.sick.cards, key = { "sick-${it.day.id}" }) { SickCardView(it, state.sick, actions.sick) }
        items(state.sick.stillSick, key = { "still-sick-${it.day.id}" }) { StillSickView(it, actions.sick) }
        items(state.sick.vabToReport, key = { "vab-${it.report.childId}-${it.report.first}" }) { VabReportView(it, actions.sick) }
        items(state.breaks, key = { "break-${it.planId}" }) { BreakSoonCard(it, plan.date, actions.onPlanBreaks) }
        // Children's "Klar!" waiting for a parent (ADR-055).
        if (state.canEdit) {
            items(plan.cards.filterIsInstance<ContextCard.ChoresToApprove>(), key = { "approve" }) { card ->
                ChoresToApproveCard(card, actions.onOpenKids)
            }
        }
        // "Dags att OSA" (ADR-054): an invitation to answer within three days.
        items(plan.cards.filterIsInstance<ContextCard.PartyRsvp>(), key = { "rsvp-${it.occurrence.key}" }) { card ->
            RsvpCard(card, plan.date) { actions.onOpenOccurrence(card.occurrence) }
        }
        if (state.medicine.isNotEmpty()) {
            item(key = "medicine") {
                MedicineTodayCard(state.medicine, actions.onGiveDose, onOpen = {
                    actions.sick.onOpenMedicine(state.medicine.first().child.id)
                })
            }
        }
        // "Hämtad ✓" (ADR-048): somebody else's pick-up nobody ticked off, then today's done ones.
        items(state.unconfirmedPickups, key = { "unconfirmed-${it.pickup.key}" }) { UnconfirmedPickupBanner(it, actions) }
        items(state.pickedUp, key = { "picked-${it.receipt.id}" }) { PickedUpLine(it, actions) }
        // "Kom igång" is what a new family has to sort out; it is the focus surface while it shows (UX plan 4.4).
        val setup = state.setup
        if (setup != null) {
            item(key = "setup") {
                SetupChecklistCard(setup, onOpen = { actions.openSetup(it, plan.date) }, onHide = actions.onHideSetup)
            }
        }
        // In the evening Kvällskollen is the focus surface; what is left of today (tonight's dinner) is drawn flat above it.
        if (state.hero.isNotEmpty()) {
            item(key = "hero") { MorningCheck(state, plan, actions, flat = setup != null || state.tomorrow.isNotEmpty()) }
        }
        val tomorrow = plan.tomorrow
        if (state.tomorrow.isNotEmpty() && tomorrow != null) {
            item(key = "tomorrow") {
                MorningCheck(
                    state,
                    plan,
                    actions,
                    flat = setup != null,
                    rows = state.tomorrow,
                    date = tomorrow.date,
                    title = stringResource(Res.string.today_tomorrow_title, weekdayName(tomorrow.date.dayOfWeek)),
                )
            }
        }
        val dismissHint = actions.onDismissHint
        if (state.showAddHint && dismissHint != null) item(key = "hint") { AddHint(state, dismissHint) }
        items(state.sections, key = { "section-${it.id}" }) { block ->
            // Each section is its own card, so Idag händer, Att göra and Kommande read as separate parts (DESIGN.md).
            when (block) {
                TodayBlock.Timeline -> TimelineSection(state, plan, actions)
                TodayBlock.Tasks -> TasksSection(plan, actions)
                TodayBlock.Upcoming -> UpcomingSection(state, plan, actions)
                TodayBlock.Shopping -> ShoppingSection(plan, actions)
                // Morgonkollen rows are never sections.
                TodayBlock.Handoffs, TodayBlock.Packing, TodayBlock.Dinner, TodayBlock.Weather -> Unit
            }
        }
        if (state.quiet) item(key = "quiet") { QuietLine(stringResource(Res.string.today_quiet_end)) }
        item(key = "customize") {
            NavRow(
                stringResource(Res.string.today_customize),
                actions.onCustomize,
                // No extra air right after the quiet line, which has its own.
                modifier = Modifier.padding(top = if (state.quiet) 0.dp else Spacing.lg),
            )
        }
    }
}

/** "Torsdag 8 oktober" and "Vecka 41 · Hos Ahmed". No greeting (ADR-026); the week is always visible. */
@Composable
private fun Header(plan: TodayPlan, onReportSick: (() -> Unit)? = null, onSearch: () -> Unit = {}) {
    val subtitle = buildList {
        add(weekLong(plan.week.week))
        plan.holidays.forEach { add(stringResource(Labels.holiday(it.id))) }
        plan.custodyNotes.forEach { note ->
            add(
                when (note) {
                    is CustodyNote.With -> stringResource(Res.string.today_custody, note.household.name)
                    is CustodyNote.Changeover ->
                        stringResource(Res.string.today_custody_handover, formatTime(note.handover.time), note.handover.to.name)
                },
            )
        }
    }.joinToString(SEPARATOR)
    TabHeader(title = formatDateLong(plan.date).replaceFirstChar { it.uppercase() }, subtitle = subtitle) {
        if (onReportSick != null) TextButton(onClick = onReportSick) { Text(stringResource(Res.string.sick_report_action)) }
        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = stringResource(Res.string.search_title)) }
    }
}

/**
 * "Exempelfamilj · Börja på riktigt" (UX plan 4.8), only for the example family. The action is confirmed in a dialog,
 * because the example data cannot come back; a failure is a quiet line under the row and the button stays.
 */
@Composable
private fun DemoRow(leaving: Boolean, failed: Boolean, onLeave: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            InfoPill(stringResource(Res.string.today_demo_family))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirm = true }, enabled = !leaving) { Text(stringResource(Res.string.today_demo_leave)) }
        }
        if (failed) {
            Text(
                stringResource(Res.string.error_generic),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(Res.string.today_demo_leave_title)) },
            text = { Text(stringResource(Res.string.today_demo_leave_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onLeave()
                }) { Text(stringResource(Res.string.today_demo_leave_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

/** The one first-time hint of Idag (UX plan 4.7), with the family's own child in the example when there is one. */
@Composable
private fun AddHint(state: TodayUiState, onDismiss: () -> Unit) {
    val child = state.people.values.firstOrNull { it.isChild }?.name
    val text = if (child != null) {
        stringResource(Res.string.today_hint_add, child)
    } else {
        stringResource(Res.string.today_hint_add_generic)
    }
    FirstTimeHint(text, onDismiss = onDismiss)
}

/** Unique per problem: one event can lack both its drop-off and its pick-up, and a child can be double booked twice. */
private fun problemKey(c: Conflict): String {
    val detail = when (c) {
        is Conflict.Unassigned -> c.kind.name
        is Conflict.NobodyAvailable -> c.kind.name
        is Conflict.AssigneeBusy -> c.kind.name
        is Conflict.DoubleBooked -> "${c.childId}-${c.other.key}"
    }
    return "problem-${c.date}-${c.occurrence.key}-${c::class.simpleName}-$detail"
}

/** A real problem: filled, a whole sentence and always an action. Tomorrow's start with "Imorgon:". */
@Composable
private fun ProblemBanner(c: Conflict, state: TodayUiState, actions: TodayActions) {
    val plan = state.plan ?: return
    val people = state.people

    fun names(ids: List<String>) = ids.mapNotNull { people[it]?.name }
    val text = when (c) {
        is Conflict.NobodyAvailable -> stringResource(
            if (c.kind == HandoffKind.PickUp) Res.string.card_nobody_pickup else Res.string.card_nobody_dropoff,
            joinNames(names(c.busyAdultIds)),
            formatTime(c.time),
            joinNames(names(c.childIds)),
        )
        is Conflict.AssigneeBusy -> stringResource(
            if (c.kind == HandoffKind.PickUp) Res.string.card_busy_pickup else Res.string.card_busy_dropoff,
            people[c.personId]?.name.orEmpty(),
            joinNames(names(c.childIds)),
            formatTime(c.time),
            c.busyWith.event.title,
        )
        is Conflict.Unassigned -> stringResource(
            if (c.kind == HandoffKind.PickUp) Res.string.card_unassigned_pickup else Res.string.card_unassigned_dropoff,
            joinNames(names(c.childIds)),
            formatTime(c.time),
        )
        is Conflict.DoubleBooked -> stringResource(
            Res.string.card_double_booked,
            people[c.childId]?.name.orEmpty(),
            c.occurrence.event.title,
            c.other.event.title,
        )
    }
    val prefix = if (c.date == plan.date) "" else relativeDay(c.date, plan.date) + ": "
    val kind = when (c) {
        is Conflict.NobodyAvailable -> c.kind
        is Conflict.AssigneeBusy -> c.kind
        is Conflict.Unassigned -> c.kind
        is Conflict.DoubleBooked -> null
    }
    // "Jag tar det" only where the write reaches the family: never for a caregiver (UX plan 1.13).
    val canTake = state.canEdit && kind != null && !(c is Conflict.AssigneeBusy && c.personId == state.me?.id)
    Banner(prefix + text, kind = BannerKind.Problem, actions = {
        if (canTake && kind != null) {
            SmallAction(stringResource(Res.string.card_action_take)) { actions.onTakeHandoff(c.occurrence, kind) }
        }
        SmallAction(stringResource(Res.string.card_action_open)) { actions.onOpenOccurrence(c.occurrence) }
    })
}

/**
 * "Bengt kan inte hämta Simon 15:30 · Jag tar det": a caregiver's "Jag kan inte" (UX plan Steg 3). A real problem, like the
 * conflicts; "Jag tar det" answers it, so it goes away and he sees who took over.
 */
@Composable
private fun CannotMakeBanner(c: CaregiverCannot, state: TodayUiState, actions: TodayActions) {
    val plan = state.plan ?: return
    val from = c.from?.name ?: stringResource(Res.string.today_request_someone)
    val children = joinNames(c.childIds.mapNotNull { state.people[it]?.name })
    val resource = if (c.kind == HandoffKind.PickUp) Res.string.card_helper_cannot_pickup else Res.string.card_helper_cannot_dropoff
    val prefix = if (c.date == plan.date) "" else relativeDay(c.date, plan.date) + ": "
    Banner(prefix + stringResource(resource, from, children, formatTime(c.time)), kind = BannerKind.Problem, actions = {
        // "Jag tar det" only where the write reaches the family: never for a caregiver (UX plan 1.13).
        if (state.canEdit) SmallAction(stringResource(Res.string.card_action_take)) { actions.onTakeHandoff(c.occurrence, c.kind) }
        SmallAction(stringResource(Res.string.card_action_open)) { actions.onOpenOccurrence(c.occurrence) }
    })
}

/** "Ella hämtad? Bengt skulle hämta 15:45 · Hämtad" (ADR-048). */
@Composable
private fun UnconfirmedPickupBanner(u: UnconfirmedPickup, actions: TodayActions) {
    val children = joinNames(u.pickup.children.map { it.name })
    val time = formatTime(u.pickup.time)
    val text = u.person?.let { stringResource(Res.string.pickup_unconfirmed, children, it.name, time) }
        ?: stringResource(Res.string.pickup_unconfirmed_nobody, children, time)
    Banner(text, kind = BannerKind.Problem, actions = {
        TextButton(onClick = { actions.onConfirmPickup(u.pickup) }) { Text(stringResource(Res.string.pickup_done_action)) }
    })
}

/** "Ella hämtad 15:52 · Bengt": who ticked it off (Sara may do it for Johan); "Ångra" on my own. */
@Composable
private fun PickedUpLine(p: PickedUp, actions: TodayActions) {
    val children = joinNames(p.children.map { it.name })
    val at = p.receipt.doneAt?.let { formatTime(it.toLocalDateTime(TimeZone.currentSystemDefault()).time) }.orEmpty()
    val text = stringResource(Res.string.pickup_done_line, children, at, p.by?.name ?: stringResource(Res.string.today_request_someone))
    Banner(
        text,
        kind = BannerKind.Info,
        icon = Icons.Filled.Check,
        actions = if (p.byMe) {
            { TextButton(onClick = { actions.onUndoPickup(p.receipt) }) { Text(stringResource(Res.string.action_undo)) } }
        } else {
            null
        },
    )
}

/** "Sara frågar om du kan hämta Ella fre 15:45 · Svara". A reminder to act on, so unfilled with a terracotta icon. */
@Composable
private fun RequestBanner(r: TodayRequest, today: LocalDate, onOpenOccurrence: (EventOccurrence) -> Unit) {
    val from = r.from?.name ?: stringResource(Res.string.today_request_someone)
    val time = r.time?.let(::formatTime)
    // Today it is just the time; another day gets the short weekday ("fre 15:45").
    val day = if (r.date == today) null else weekdayShort(r.date.dayOfWeek)
    val whenText = listOfNotNull(day, time).joinToString(" ")
    val text = stringResource(
        if (r.kind == HandoffKind.PickUp) Res.string.today_request_pickup else Res.string.today_request_dropoff,
        from,
        joinNames(r.children.map { it.name }),
        whenText,
    ).trim()
    Banner(text, kind = BannerKind.Warning, icon = PussletIcons.Swap, actions = {
        SmallAction(stringResource(Res.string.today_request_answer)) { onOpenOccurrence(r.occurrence) }
    })
}

/** Joins the parts of one line ("Vecka 41 · Hos Ahmed"). */
internal const val SEPARATOR = " · "
