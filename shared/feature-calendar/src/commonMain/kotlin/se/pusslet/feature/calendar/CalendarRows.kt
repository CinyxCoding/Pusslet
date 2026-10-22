package se.pusslet.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.custody.CustodyPeriod
import se.pusslet.core.holidays.Holiday
import se.pusslet.core.holidays.HolidayKind
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.formatTimeRange
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.format.weekdayShort
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.PussletThemeExtras
import se.pusslet.ui.theme.Spacing

/**
 * The rows of one day, the same in the week list, under the month grid and on the day screen: holidays, custody changeovers,
 * routine lines, then events. With [timed] false the timed events are left out (the day screen draws them in its hour grid).
 * The week list leaves out the [changeovers]: the line under the week already says where the children are.
 */
@Composable
internal fun DayContent(
    day: CalendarDay,
    people: Map<String, FamilyPerson>,
    canTake: Boolean,
    onOpen: (EventOccurrence) -> Unit,
    onTake: (EventOccurrence, HandoffKind) -> Unit,
    timed: Boolean = true,
    changeovers: Boolean = true,
) {
    day.holidays.forEach { HolidayLine(it) }
    day.sick.forEach { SickDayLine(it) }
    if (changeovers) day.changeovers.forEach { ChangeoverLine(it) }
    day.routines.forEach { RoutineRow(it, canTake, onOpen, onTake) }
    day.events.filter { timed || it.allDay }.forEach { OccurrenceRow(it, day.date, people, onOpen) }
}

/** "Ella sjuk · Sara hemma" (ADR-047): quiet, like a holiday; the sick child's activities are already left out. */
@Composable
internal fun SickDayLine(line: SickLine) {
    val home = line.homeWith
    Text(
        if (home == null) {
            stringResource(Res.string.sick_line, line.child.name)
        } else {
            stringResource(Res.string.sick_line_home, line.child.name, home.name)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = Spacing.xs),
    )
}

/** "Alla helgons dag": red for public holidays, quiet for the rest ("Vintertid börjar"). */
@Composable
internal fun HolidayLine(holiday: Holiday) {
    Text(
        stringResource(Labels.holiday(holiday.id)),
        style = MaterialTheme.typography.bodyMedium,
        color = if (holiday.kind ==
            HolidayKind.PublicHoliday
        ) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(vertical = Spacing.xs),
    )
}

/** "Byte 15:00 till Ahmed", or "Ella: Byte 15:00 till Ahmed" when the schedule is not about every child. */
@Composable
internal fun ChangeoverLine(changeover: CustodyChangeover) {
    val h = changeover.handover
    val text = stringResource(Res.string.today_custody_handover, formatTime(h.time), h.to.name)
    Row(
        Modifier.padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            PussletIcons.Swap,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            if (changeover.children.isEmpty()) {
                text
            } else {
                stringResource(
                    Res.string.calendar_routine_child,
                    names(changeover.children),
                    text,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A routine as one quiet line (UX plan, Kalender 2): "Simon: Johan lämnar 07:50 · Morfar hämtar 15:30". A hand-off nobody
 * has taken says "Inte bestämt" in the error colour, with an icon and, for parents, "Jag tar det". The line opens the event.
 */
@Composable
internal fun RoutineRow(
    line: RoutineLine,
    canTake: Boolean,
    onOpen: (EventOccurrence) -> Unit,
    onTake: (EventOccurrence, HandoffKind) -> Unit,
) {
    val open = line.firstOpen
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = Spacing.touchTarget)
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button) { onOpen(line.occurrence) }
                .padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (open != null) {
                Icon(
                    PussletIcons.PriorityHigh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(routineText(line), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open != null && canTake && open.occurrence.source == EventSource.Family) {
            TextButton(onClick = { onTake(open.occurrence, open.kind) }) { Text(stringResource(Res.string.card_action_take)) }
        }
    }
}

/**
 * An event (UX plan, Kalender 4): the start time in its own column, a thin bar in the person's colour, the title and one line
 * "Simon · till 16:00 · Vallen". Events from another calendar name it there, without "Från".
 */
@Composable
internal fun OccurrenceRow(o: EventOccurrence, onDate: LocalDate, people: Map<String, FamilyPerson>, onOpen: (EventOccurrence) -> Unit) {
    val participants = o.attending.mapNotNull { people[it] }
    val extras = PussletThemeExtras.colors
    val bar = participants.firstOrNull()?.let { extras.member(it.color).container } ?: o.sourceColor?.let { extras.member(it).container }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onOpen(o) }
            .padding(vertical = Spacing.xs)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        val time = when {
            o.allDay -> stringResource(Res.string.all_day)
            o.start.date < onDate -> "…" + formatTime(o.end.time)
            else -> formatTime(o.start.time)
        }
        Text(time, style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = TimeColumn).padding(top = Spacing.xxs))
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(bar ?: Color.Transparent))
        Column(Modifier.weight(1f)) {
            Text(
                o.event.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // The start time stands in its own column, so the line only says when it ends ("till 16:00"); never the time twice.
            val endsHere = !o.allDay && o.start.date == onDate && o.end.date == onDate && o.end.time != o.start.time
            val details = buildList {
                if (participants.isNotEmpty()) add(names(participants)) else o.sourceName?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (endsHere) add(stringResource(Res.string.calendar_until, formatTime(o.end.time)))
                if (o.event.location.isNotBlank()) add(o.event.location)
            }
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(SEPARATOR),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One part of a sentence; [highlight] (a substring of [text]) gets [style]. */
internal data class Segment(val text: String, val highlight: String? = null, val style: SpanStyle? = null)

/**
 * "Johan lämnar 07:50", "Du lämnar 07:50" (sage, bold) or "Lämning 07:50 – Inte bestämt". "Inte bestämt" is red when the
 * hand-off is [planned] but nobody has it; a hand-off that was never planned stays quiet.
 */
@Composable
internal fun handoffSegment(kind: HandoffKind, time: LocalTime, person: FamilyPerson?, isMe: Boolean, planned: Boolean = true): Segment {
    val t = formatTime(time)
    val pickUp = kind == HandoffKind.PickUp
    val colors = MaterialTheme.colorScheme
    return when {
        person == null -> {
            val nobody = stringResource(Res.string.event_handoff_none)
            val text =
                stringResource(if (pickUp) Res.string.calendar_handoff_pickup_open else Res.string.calendar_handoff_dropoff_open, t, nobody)
            if (planned) Segment(text, nobody, SpanStyle(color = colors.error, fontWeight = FontWeight.SemiBold)) else Segment(text)
        }
        isMe -> {
            val text = stringResource(if (pickUp) Res.string.calendar_handoff_pickup_me else Res.string.calendar_handoff_dropoff_me, t)
            Segment(text, text, SpanStyle(color = colors.primary, fontWeight = FontWeight.SemiBold))
        }
        else -> Segment(
            stringResource(if (pickUp) Res.string.calendar_handoff_pickup else Res.string.calendar_handoff_dropoff, person.name, t),
        )
    }
}

/** The text of a [RoutineRow]. */
@Composable
internal fun routineText(line: RoutineLine): AnnotatedString {
    val segments = if (line.stops.isEmpty()) {
        val o = line.occurrence
        listOf(Segment(o.event.title + " " + formatTimeRange(o.start.time, o.end.time)))
    } else {
        line.stops.map { handoffSegment(it.kind, it.time, it.person, it.isMe) }
    }
    val full = stringResource(Res.string.calendar_routine_child, names(line.children), segments.joinToString(SEPARATOR) { it.text })
    return annotate(full, segments)
}

/** Styles each segment's highlight where the segment appears in [full], in order. */
internal fun annotate(full: String, segments: List<Segment>): AnnotatedString = buildAnnotatedString {
    append(full)
    var cursor = 0
    segments.forEach { s ->
        val start = full.indexOf(s.text, cursor)
        if (start < 0) return@forEach
        val highlight = s.highlight
        val style = s.style
        val at = highlight?.let { s.text.lastIndexOf(it) } ?: -1
        if (highlight != null && style != null && at >= 0) addStyle(style, start + at, start + at + highlight.length)
        cursor = start + s.text.length
    }
}

/** The line under the week: "Hos Ahmed (mån–sön)", "Ella hos Sara (mån–sön)"; null without custody. */
@Composable
internal fun custodyWeekText(weeks: List<CustodyWeek>): String? {
    if (weeks.isEmpty()) return null
    // "Hos" from Idag's "Hos %1$s", so a home already named "Hos pappa" does not become "Hos Hos pappa".
    val word = stringResource(Res.string.today_custody, "").trim()
    return weeks.flatMap { w -> w.periods.map { custodyPeriodText(w.children, it, word) } }.joinToString(SEPARATOR)
}

@Composable
private fun custodyPeriodText(children: List<FamilyPerson>, period: CustodyPeriod, word: String): String {
    val from = weekdayShort(period.start.dayOfWeek)
    val range = if (period.start == period.endInclusive) {
        from
    } else {
        stringResource(Res.string.calendar_range, from, weekdayShort(period.endInclusive.dayOfWeek))
    }
    val raw = period.household.name.trim()
    val home = if (word.isNotEmpty() && raw.startsWith("$word ", ignoreCase = true)) raw.substring(word.length + 1).trim() else raw
    return if (children.isEmpty()) {
        stringResource(Res.string.calendar_custody_period, home, range)
    } else {
        stringResource(Res.string.calendar_custody_period_for, names(children), home, range)
    }
}

@Composable
internal fun names(people: List<FamilyPerson>): String = joinNames(people.map { it.name })

internal const val SEPARATOR = " · "

/** The start-time column of event rows. */
internal val TimeColumn = 48.dp
