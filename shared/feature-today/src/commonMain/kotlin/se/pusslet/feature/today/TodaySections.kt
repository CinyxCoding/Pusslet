package se.pusslet.feature.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.today.DayReminder
import se.pusslet.core.today.TimelineItem
import se.pusslet.core.today.TodayPlan
import se.pusslet.ui.components.CheckRow
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.NavRow
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.Section
import se.pusslet.ui.components.SmallAction
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.format.relativeDay
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.card_action_remind
import se.pusslet.ui.resources.shopping_shopper_now
import se.pusslet.ui.resources.today_block_shopping
import se.pusslet.ui.resources.today_block_tasks
import se.pusslet.ui.resources.today_block_timeline
import se.pusslet.ui.resources.today_block_upcoming
import se.pusslet.ui.resources.today_overdue
import se.pusslet.ui.resources.today_reminder_gift
import se.pusslet.ui.resources.today_reminder_squeeze
import se.pusslet.ui.resources.today_reminder_study_day
import se.pusslet.ui.resources.today_shopping_left
import se.pusslet.ui.resources.today_show_all_count
import se.pusslet.ui.resources.today_show_whole_day
import se.pusslet.ui.theme.Spacing

/**
 * "Idag händer": time, name and place. Hand-offs are only here while the Morgonkollen row is off (one fact once). Passed rows
 * are dimmed and a line marks now; after five rows the rest is in Kalender ("Visa hela dagen").
 */
@Composable
internal fun TimelineSection(state: TodayUiState, plan: TodayPlan, actions: TodayActions) {
    val all = plan.timelineFor(state.layout)
    val rows = TodayModel.timelineWindow(all, plan.date, plan.now)
    val passed = rows.map { TodayModel.isPassed(it, plan.date, plan.now) }
    val firstComing = passed.indexOfFirst { !it }
    val nowLineAt = if (firstComing > 0 && passed[firstComing - 1]) firstComing else -1
    Section(stringResource(Res.string.today_block_timeline)) {
        rows.forEachIndexed { index, item ->
            if (index == nowLineAt) NowLine()
            when (item) {
                is TimelineItem.HandoffItem -> {
                    val mine = item.person != null && item.person?.id == state.me?.id
                    val children = joinNames(item.children.map { it.name })
                    TimedRow(
                        time = formatTime(item.time),
                        text = handoffSentence(item.kind, item.person, mine, children),
                        emphasized = mine,
                        dimmed = passed[index],
                        onClick = { actions.onOpenOccurrence(item.occurrence) },
                    )
                }
                is TimelineItem.EventItem -> {
                    val o = item.occurrence
                    TimedRow(
                        time = if (o.allDay) null else formatTime(o.start.time),
                        text = o.event.title,
                        supporting = details(item.people.map { it.name }, o),
                        dimmed = passed[index],
                        onClick = { actions.onOpenOccurrence(o) },
                    )
                }
            }
        }
        if (all.size > rows.size) NavRow(stringResource(Res.string.today_show_whole_day), actions.onOpenCalendar)
    }
}

/** "Ella · Eriksdalsbadet": who and where, at most one quiet line. */
@Composable
private fun details(names: List<String>, o: EventOccurrence): String? =
    listOfNotNull(joinNames(names).ifBlank { null }, o.event.location.ifBlank { null }).joinToString(SEPARATOR).ifBlank { null }

/** The "now" between passed and coming rows: a thin sage line, decorative only. */
@Composable
private fun NowLine() {
    HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
}

/** "Att göra": my open tasks, at most three. "Visa alla (n)" only when there is somewhere to show them. */
@Composable
internal fun TasksSection(plan: TodayPlan, actions: TodayActions) {
    Section(stringResource(Res.string.today_block_tasks)) {
        plan.myTasks.take(TodayModel.MAX_TASKS).forEach { t ->
            // Today's need no date; Idag is about today.
            val supporting = t.date?.let { d ->
                when {
                    d < plan.date -> stringResource(Res.string.today_overdue)
                    d == plan.date -> null
                    else -> relativeDay(d, plan.date)
                }
            }
            CheckRow(t.task.title, t.done, onCheckedChange = { actions.onToggleTask(t, it) }, supporting = supporting)
        }
        val openAll = actions.onOpenTasks
        if (openAll != null && plan.myTasks.size > TodayModel.MAX_TASKS) {
            NavRow(stringResource(Res.string.today_show_all_count, plan.myTasks.size), openAll)
        }
    }
}

/**
 * "Kommande": the next three days with something on them. Events that are not routine, red days and reminders with their
 * action ("Påminn mig" on a party without a present). No dinners.
 */
@Composable
internal fun UpcomingSection(state: TodayUiState, plan: TodayPlan, actions: TodayActions) {
    Section(stringResource(Res.string.today_block_upcoming)) {
        plan.comingUp.forEach { day ->
            Text(
                relativeDay(day.date, plan.date).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            day.holidays.forEach {
                Text(
                    stringResource(Labels.holiday(it.id)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            day.reminders.forEach { ReminderRow(it, state.canEdit, actions) }
            day.events.forEach { o ->
                TimedRow(
                    time = if (o.allDay) null else formatTime(o.start.time),
                    text = o.event.title,
                    supporting = o.event.location.ifBlank { null },
                    onClick = { actions.onOpenOccurrence(o) },
                )
            }
        }
    }
}

/** A reminder on its day, with a terracotta topic icon (the old banners, ADR-028). */
@Composable
private fun ReminderRow(reminder: DayReminder, canEdit: Boolean, actions: TodayActions) {
    when (reminder) {
        is DayReminder.PartyGift -> {
            val o = reminder.occurrence
            // "Påminn mig" adds a to-do for the day before: only while there is none, and only for those who can add one.
            val remind: (@Composable () -> Unit)? = if (canEdit && reminder.taskId == null) {
                { SmallAction(stringResource(Res.string.card_action_remind)) { actions.onAddGiftTask(o, o.event.title) } }
            } else {
                null
            }
            TimedRow(
                time = if (o.allDay) null else formatTime(o.start.time),
                text = stringResource(Res.string.today_reminder_gift, o.event.title),
                icon = PussletIcons.Gift,
                onClick = { actions.onOpenOccurrence(o) },
                trailing = remind,
            )
        }
        is DayReminder.StudyDay -> TimedRow(
            time = null,
            text = stringResource(Res.string.today_reminder_study_day, reminder.occurrence.event.title),
            icon = PussletIcons.School,
            onClick = { actions.onOpenOccurrence(reminder.occurrence) },
        )
        DayReminder.SqueezeDay -> TimedRow(
            time = null,
            text = stringResource(Res.string.today_reminder_squeeze),
            icon = PussletIcons.CalendarMonth,
        )
    }
}

/** "Inköpslistan": "Veckohandling: 5 kvar" and who is in the shop right now. Opens Handla. */
@Composable
internal fun ShoppingSection(plan: TodayPlan, actions: TodayActions) {
    val shopping = plan.shopping ?: return
    val shoppers = if (shopping.shoppers.isEmpty()) {
        null
    } else {
        stringResource(Res.string.shopping_shopper_now, joinNames(shopping.shoppers.map { it.name }))
    }
    val left = if (shopping.remaining > 0) stringResource(Res.string.today_shopping_left, shopping.listName, shopping.remaining) else null
    Section(stringResource(Res.string.today_block_shopping)) {
        NavRow(
            text = left ?: shoppers.orEmpty(),
            onClick = actions.onOpenShopping,
            icon = Icons.Filled.ShoppingCart,
            supporting = if (left != null) shoppers else null,
        )
    }
}

/**
 * A row with the time in its own column ("17:30  Simskola"). An empty time keeps the column, so titles line up. [icon] is a
 * terracotta topic icon for reminders. With [onClick] the whole row opens something (›), unless [trailing] holds a button.
 */
@Composable
private fun TimedRow(
    time: String?,
    text: String,
    supporting: String? = null,
    emphasized: Boolean = false,
    dimmed: Boolean = false,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    val textColor = when {
        dimmed -> colors.onSurfaceVariant
        emphasized -> colors.primary
        else -> Color.Unspecified
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).then(interaction).padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            time.orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = if (dimmed) colors.onSurfaceVariant else Color.Unspecified,
            maxLines = 1,
            modifier = Modifier.widthIn(min = TIME_COLUMN),
        )
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
                fontWeight = if (emphasized && !dimmed) FontWeight.SemiBold else null,
            )
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(PussletIcons.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
    }
}

private val TIME_COLUMN = 52.dp
