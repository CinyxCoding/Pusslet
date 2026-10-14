package se.pusslet.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.conflicts.Conflict
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.PackingItem
import se.pusslet.core.tasks.TaskOccurrence
import se.pusslet.core.today.CustodyNote
import se.pusslet.core.today.TodayBlock
import se.pusslet.core.today.TodayPlan
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
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
import se.pusslet.ui.format.weekdayShort
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.action_add
import se.pusslet.ui.resources.card_action_open
import se.pusslet.ui.resources.card_action_take
import se.pusslet.ui.resources.card_busy_dropoff
import se.pusslet.ui.resources.card_busy_pickup
import se.pusslet.ui.resources.card_double_booked
import se.pusslet.ui.resources.card_nobody_dropoff
import se.pusslet.ui.resources.card_nobody_pickup
import se.pusslet.ui.resources.card_unassigned_dropoff
import se.pusslet.ui.resources.card_unassigned_pickup
import se.pusslet.ui.resources.today_custody
import se.pusslet.ui.resources.today_custody_handover
import se.pusslet.ui.resources.today_customize
import se.pusslet.ui.resources.today_demo_family
import se.pusslet.ui.resources.today_more_problems
import se.pusslet.ui.resources.today_quiet_end
import se.pusslet.ui.resources.today_request_answer
import se.pusslet.ui.resources.today_request_dropoff
import se.pusslet.ui.resources.today_request_pickup
import se.pusslet.ui.resources.today_request_someone
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
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: TodayViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
)

@Composable
fun TodayScreen(state: TodayUiState, contentPadding: PaddingValues = PaddingValues(), actions: TodayActions = TodayActions()) {
    val plan = state.plan
    if (state.loading || plan == null) {
        LoadingBox()
        return
    }
    val problems = plan.problems
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
        item(key = "header") { Header(state, plan) }
        // "Att lösa" can never be hidden: problems today and tomorrow, then requests to me.
        items(problems.take(TodayModel.MAX_PROBLEMS), key = { problemKey(it) }) { ProblemBanner(it, state, actions) }
        if (problems.size > TodayModel.MAX_PROBLEMS) {
            item(key = "more-problems") {
                NavRow(stringResource(Res.string.today_more_problems, problems.size - TodayModel.MAX_PROBLEMS), actions.onOpenCalendar)
            }
        }
        items(state.requests, key = { "request-${it.request.id}" }) { RequestBanner(it, plan.date, actions.onOpenOccurrence) }
        if (state.hero.isNotEmpty()) item(key = "hero") { MorningCheck(state, plan, actions) }
        items(state.sections, key = { "section-${it.id}" }) { block ->
            // Flat sections, separated by air (sectionGap), never by cards.
            Column(Modifier.padding(top = Spacing.sectionGap - Spacing.md)) {
                when (block) {
                    TodayBlock.Timeline -> TimelineSection(state, plan, actions)
                    TodayBlock.Tasks -> TasksSection(plan, actions)
                    TodayBlock.Upcoming -> UpcomingSection(state, plan, actions)
                    TodayBlock.Shopping -> ShoppingSection(plan, actions)
                    // Morgonkollen rows are never sections.
                    TodayBlock.Handoffs, TodayBlock.Packing, TodayBlock.Dinner, TodayBlock.Weather -> Unit
                }
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
private fun Header(state: TodayUiState, plan: TodayPlan) {
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
    TabHeader(
        title = formatDateLong(plan.date).replaceFirstChar { it.uppercase() },
        subtitle = subtitle,
        actions = { if (state.demo) InfoPill(stringResource(Res.string.today_demo_family)) },
    )
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
