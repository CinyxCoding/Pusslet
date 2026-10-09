package se.pusslet.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.conflicts.Conflict
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.PackingReason
import se.pusslet.core.time.plusDays
import se.pusslet.core.today.ContextCard
import se.pusslet.core.today.Greeting
import se.pusslet.core.today.TimelineItem
import se.pusslet.core.today.TodayPlan
import se.pusslet.ui.components.AvatarStack
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.CheckRow
import se.pusslet.ui.components.ColorBadge
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PersonAvatar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.SmallAction
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.formatTimeRange
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.format.relativeDay
import se.pusslet.ui.format.weekLong
import se.pusslet.ui.format.weekdayName
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import kotlin.math.roundToInt

@Composable
fun TodayRoute(
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onPlanDinner: (LocalDate) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHandover: (String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: TodayViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TodayScreen(
        state = state,
        contentPadding = contentPadding,
        onTogglePacking = viewModel::setPacked,
        onToggleTask = viewModel::setTaskDone,
        onTakeHandoff = viewModel::takeHandoff,
        onAddGiftTask = viewModel::addGiftTask,
        onOpenOccurrence = onOpenOccurrence,
        onPlanDinner = onPlanDinner,
        onOpenSettings = onOpenSettings,
        onOpenHandover = onOpenHandover,
    )
}

@Composable
fun TodayScreen(
    state: TodayUiState,
    contentPadding: PaddingValues = PaddingValues(),
    onTogglePacking: (LocalDate, se.pusslet.core.model.PackingItem, Boolean) -> Unit = { _, _, _ -> },
    onToggleTask: (se.pusslet.core.tasks.TaskOccurrence, Boolean) -> Unit = { _, _ -> },
    onTakeHandoff: (EventOccurrence, HandoffKind) -> Unit = { _, _ -> },
    onAddGiftTask: (EventOccurrence, String) -> Unit = { _, _ -> },
    onOpenOccurrence: (EventOccurrence) -> Unit = {},
    onPlanDinner: (LocalDate) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenHandover: (String) -> Unit = {},
) {
    val plan = state.plan
    if (state.loading || plan == null) {
        LoadingBox()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screenHorizontal,
            end = Spacing.screenHorizontal,
            top = Spacing.lg + contentPadding.calculateTopPadding(),
            bottom = Spacing.xxl * 3 + contentPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "header") { Header(state, plan) }
        item(key = "weather") { WeatherCard(state, plan, onOpenSettings) }
        items(plan.cards, key = { cardKey(it) }) { card ->
            ContextCardView(card, state, onTakeHandoff, onAddGiftTask, onOpenOccurrence, onOpenHandover)
        }
        item(key = "timeline") { TimelineCard(plan, onOpenOccurrence) }
        if (plan.packing.isNotEmpty()) item(key = "packing") { PackingCard(plan, onTogglePacking) }
        item(key = "meals") { MealsCard(plan, onPlanDinner) }
        item(key = "tasks") { TasksCard(plan, onToggleTask) }
        item(key = "upcoming") { UpcomingCard(plan, onOpenOccurrence) }
    }
}

private fun cardKey(card: ContextCard): String = when (card) {
    is ContextCard.Problem -> "conflict-${card.conflict.occurrence.key}-${card.conflict::class.simpleName}"
    is ContextCard.StudyDay -> "study-${card.date}"
    is ContextCard.PartyGift -> "party-${card.date}-${card.eventTitle}"
    is ContextCard.SqueezeDay -> "squeeze-${card.date}"
    is ContextCard.HandoverSoon -> "handover-${card.schedule.id}-${card.handover.date}"
}

@Composable
private fun Header(state: TodayUiState, plan: TodayPlan) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                formatDateLong(plan.date).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SuggestionChip(onClick = {}, label = { Text(weekLong(plan.week.week)) })
        }
        val greeting = stringResource(
            when (plan.greeting) {
                Greeting.Morning -> Res.string.greeting_morning
                Greeting.Day -> Res.string.greeting_day
                Greeting.Evening -> Res.string.greeting_evening
                Greeting.Night -> Res.string.greeting_night
            },
        )
        Text(
            state.me?.let { stringResource(Res.string.greeting_named, greeting, it.name) } ?: greeting,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (plan.holidays.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                plan.holidays.forEach { h -> AssistChip(onClick = {}, label = { Text(stringResource(Labels.holiday(h.id))) }) }
            }
        }
        plan.custody.forEach { c ->
            val household = c.household ?: return@forEach
            Text(stringResource(Res.string.today_custody, household.name), style = MaterialTheme.typography.bodyLarge)
            c.handoverToday?.let { h ->
                Text(
                    stringResource(Res.string.today_custody_handover, formatTime(h.time), h.to.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun WeatherCard(state: TodayUiState, plan: TodayPlan, onOpenSettings: () -> Unit) {
    val summary = plan.weather
    SectionCard(title = null) {
        when (val w = state.weather) {
            WeatherState.NoPlace -> {
                Text(stringResource(Res.string.today_weather_missing), style = MaterialTheme.typography.bodyMedium)
                SmallAction(stringResource(Res.string.settings_weather_place), onOpenSettings)
            }
            WeatherState.Unavailable -> Text(
                stringResource(Res.string.today_weather_unavailable),
                style = MaterialTheme.typography.bodyMedium,
            )
            WeatherState.Loading -> Text(stringResource(Res.string.loading), style = MaterialTheme.typography.bodyMedium)
            is WeatherState.Ready -> if (summary == null) {
                Text(stringResource(Res.string.today_weather_unavailable), style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(Labels.weatherEmoji(summary.condition), style = MaterialTheme.typography.displaySmall)
                    Column(Modifier.weight(1f)) {
                        val nowTemp = summary.now?.temperature ?: summary.maxTemperature
                        Text(
                            stringResource(Res.string.today_weather_now, nowTemp.roundToInt()) + " · " +
                                stringResource(Labels.weather(summary.condition)),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(
                                Res.string.today_weather_range,
                                summary.minTemperature.roundToInt(),
                                summary.maxTemperature.roundToInt(),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (w.stale) {
                            Text(
                                stringResource(Res.string.today_weather_stale, formatTime(w.fetchedAt.time)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                plan.clothing.filter { it.advice.isNotEmpty() }.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        PersonAvatar(c.child, size = 28.dp)
                        Text(
                            c.advice.map { stringResource(Labels.advice(it)) }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Text(
                    stringResource(Res.string.weather_source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ContextCardView(
    card: ContextCard,
    state: TodayUiState,
    onTakeHandoff: (EventOccurrence, HandoffKind) -> Unit,
    onAddGiftTask: (EventOccurrence, String) -> Unit,
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onOpenHandover: (String) -> Unit,
) {
    val plan = state.plan ?: return
    val people = state.people

    fun names(ids: List<String>) = ids.mapNotNull { people[it]?.name }
    when (card) {
        is ContextCard.Problem -> {
            val c = card.conflict
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
            Banner(prefix + text, kind = BannerKind.Problem, actions = {
                val kind = when (c) {
                    is Conflict.NobodyAvailable -> c.kind
                    is Conflict.AssigneeBusy -> c.kind
                    is Conflict.Unassigned -> c.kind
                    is Conflict.DoubleBooked -> null
                }
                if (kind != null && !(c is Conflict.AssigneeBusy && c.personId == state.me?.id)) {
                    SmallAction(stringResource(Res.string.card_action_take)) { onTakeHandoff(c.occurrence, kind) }
                }
                SmallAction(stringResource(Res.string.card_action_open)) { onOpenOccurrence(c.occurrence) }
            })
        }
        is ContextCard.StudyDay -> Banner(
            stringResource(Res.string.card_study_day, card.title, cardDay(card.date, plan.date)),
            kind = BannerKind.Warning,
        )
        is ContextCard.PartyGift -> {
            val giftTitle = stringResource(Res.string.card_action_add_task)
            Banner(
                stringResource(Res.string.card_party_gift, cardDay(card.date, plan.date)),
                kind = BannerKind.Warning,
                actions = if (card.taskId == null) {
                    {
                        val occurrence = plan.upcoming.flatMap { it.events }.firstOrNull { it.event.title == card.eventTitle }
                        val presentTitle = card.eventTitle
                        if (occurrence != null) SmallAction(giftTitle) { onAddGiftTask(occurrence, presentTitle) }
                    }
                } else {
                    null
                },
            )
        }
        is ContextCard.SqueezeDay -> Banner(
            stringResource(Res.string.card_squeeze_day, relativeDay(card.date, plan.date)),
            kind = BannerKind.Info,
        )
        is ContextCard.HandoverSoon -> Banner(
            stringResource(
                Res.string.card_handover,
                if (card.handover.date == plan.date) stringResource(Res.string.card_today) else stringResource(Res.string.card_tomorrow),
                card.handover.to.name,
            ),
            kind = BannerKind.Info,
            actions = { SmallAction(stringResource(Res.string.card_action_open)) { onOpenHandover(card.schedule.id) } },
        )
    }
}

@Composable
private fun TimelineCard(plan: TodayPlan, onOpenOccurrence: (EventOccurrence) -> Unit) {
    SectionCard(stringResource(Res.string.today_timeline_title)) {
        if (plan.timeline.isEmpty()) {
            Text(
                stringResource(Res.string.today_quiet),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        plan.timeline.forEach { item ->
            when (item) {
                is TimelineItem.HandoffItem -> HandoffRow(item)
                is TimelineItem.EventItem -> EventRow(item, onOpenOccurrence)
            }
        }
    }
}

@Composable
private fun TimeLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 52.dp), maxLines = 1)
}

@Composable
private fun HandoffRow(item: TimelineItem.HandoffItem) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        TimeLabel(formatTime(item.time))
        AvatarStack(item.children, size = 24.dp)
        val label = stringResource(if (item.kind == HandoffKind.PickUp) Res.string.today_pickup else Res.string.today_dropoff)
        Column(Modifier.weight(1f)) {
            Text(label + " · " + joinNames(item.children.map { it.name }), style = MaterialTheme.typography.bodyLarge)
            val who = item.person
            if (who == null) {
                Text(
                    stringResource(Res.string.today_handoff_nobody),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    PersonAvatar(who, size = 20.dp)
                    Text(who.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EventRow(item: TimelineItem.EventItem, onOpenOccurrence: (EventOccurrence) -> Unit) {
    val o = item.occurrence
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        TimeLabel(if (o.allDay) stringResource(Res.string.all_day) else formatTime(o.start.time))
        if (item.people.isNotEmpty()) {
            AvatarStack(item.people, size = 24.dp)
        } else {
            val color = o.sourceColor
            if (color != null) {
                ColorBadge(color, o.sourceName?.take(1).orEmpty(), o.sourceName.orEmpty(), size = 24.dp)
            } else {
                Spacer(Modifier.width(24.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            SmallTitle(o.event.title, onClick = { onOpenOccurrence(o) })
            val details = buildList {
                if (!o.allDay) add(formatTimeRange(o.start.time, o.end.time))
                if (o.event.location.isNotBlank()) add(o.event.location)
                when (o.source) {
                    EventSource.Subscription -> add(stringResource(Res.string.event_source_subscription, o.sourceName.orEmpty()))
                    EventSource.Device -> add(stringResource(Res.string.event_source_device))
                    else -> Unit
                }
            }
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SmallTitle(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PackingCard(plan: TodayPlan, onToggle: (LocalDate, se.pusslet.core.model.PackingItem, Boolean) -> Unit) {
    SectionCard(stringResource(Res.string.today_packing_title)) {
        val any = plan.packing.any { it.items.isNotEmpty() }
        if (!any) {
            Text(
                stringResource(Res.string.today_packing_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        plan.packing.filter { it.items.isNotEmpty() }.forEach { cp ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PersonAvatar(cp.child, size = 24.dp)
                Text(cp.child.name, style = MaterialTheme.typography.titleSmall)
            }
            cp.items.forEach { item ->
                val reason = when (val r = item.reason) {
                    is PackingReason.ForEvent -> stringResource(Res.string.today_packing_reason_event, r.eventTitle)
                    PackingReason.Handover -> stringResource(Res.string.today_packing_reason_handover)
                    is PackingReason.Rule -> null
                }
                CheckRow(item.text, item.checked, onCheckedChange = { onToggle(plan.date, item, it) }, supporting = reason)
            }
        }
    }
}

@Composable
private fun MealsCard(plan: TodayPlan, onPlanDinner: (LocalDate) -> Unit) {
    SectionCard(stringResource(Res.string.today_meals_title)) {
        plan.lunches.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PersonAvatar(l.child, size = 24.dp)
                Text(stringResource(Res.string.today_lunch, l.child.name, l.dishes.first()), style = MaterialTheme.typography.bodyMedium)
            }
        }
        val dinner = plan.dinner
        if (dinner == null) {
            Text(stringResource(Res.string.today_no_dinner), style = MaterialTheme.typography.bodyLarge)
            SmallAction(stringResource(Res.string.today_plan_dinner)) { onPlanDinner(plan.date) }
        } else {
            Text(stringResource(Res.string.today_dinner, dinner.title), style = MaterialTheme.typography.titleMedium)
            plan.lunchDinnerClash.forEach { (childId, keys) ->
                val child = plan.lunches.firstOrNull { it.child.id == childId }?.child ?: return@forEach
                Banner(stringResource(Res.string.today_clash, child.name, keys.joinToString(", ")), kind = BannerKind.Warning)
            }
        }
    }
}

@Composable
private fun TasksCard(plan: TodayPlan, onToggle: (se.pusslet.core.tasks.TaskOccurrence, Boolean) -> Unit) {
    SectionCard(stringResource(Res.string.today_tasks_title)) {
        if (plan.myTasks.isEmpty()) {
            Text(
                stringResource(Res.string.today_no_tasks),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        plan.myTasks.take(MAX_TASKS).forEach { t ->
            val supporting = t.date?.let { d ->
                if (d < plan.date) stringResource(Res.string.today_overdue) else relativeDay(d, plan.date)
            }
            CheckRow(t.task.title, t.done, onCheckedChange = { onToggle(t, it) }, supporting = supporting)
        }
    }
}

@Composable
private fun UpcomingCard(plan: TodayPlan, onOpenOccurrence: (EventOccurrence) -> Unit) {
    SectionCard(stringResource(Res.string.today_upcoming_title)) {
        plan.upcoming.forEach { day ->
            Column(Modifier.padding(vertical = Spacing.xs)) {
                Text(relativeDay(day.date, plan.date).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
                day.holidays.forEach {
                    Text(
                        stringResource(Labels.holiday(it.id)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (day.events.isEmpty() && day.dinner == null) {
                    Text(
                        stringResource(Res.string.today_upcoming_nothing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                day.events.take(MAX_UPCOMING).forEach { o ->
                    val time = if (o.allDay) stringResource(Res.string.all_day) else formatTime(o.start.time)
                    SmallTitle("$time  ${o.event.title}") { onOpenOccurrence(o) }
                }
                day.dinner?.let { Text(stringResource(Res.string.today_dinner, it), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

private const val MAX_TASKS = 6
private const val MAX_UPCOMING = 4

/** "idag", "imorgon" or "på fredag" for a card about the coming days. */
@Composable
private fun cardDay(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(Res.string.card_today)
    today.plusDays(1) -> stringResource(Res.string.card_tomorrow)
    else -> stringResource(Res.string.on_weekday, weekdayName(date.dayOfWeek))
}
