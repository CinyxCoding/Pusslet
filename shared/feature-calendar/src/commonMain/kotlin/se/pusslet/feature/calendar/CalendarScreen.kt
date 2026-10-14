package se.pusslet.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.core.time.isoWeek
import se.pusslet.data.repository.CalendarLayer
import se.pusslet.data.repository.CalendarView
import se.pusslet.ui.components.AvatarStack
import se.pusslet.ui.components.ColorBadge
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.PersonChips
import se.pusslet.ui.components.RegisterAddButton
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.formatTimeRange
import se.pusslet.ui.format.monthName
import se.pusslet.ui.format.weekLong
import se.pusslet.ui.format.weekShort
import se.pusslet.ui.format.weekdayShort
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.PussletThemeExtras
import se.pusslet.ui.theme.Spacing

@Composable
fun CalendarRoute(
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onNewEvent: (LocalDate) -> Unit,
    onQuickAdd: (LocalDate?) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: CalendarViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CalendarScreen(
        state = state,
        contentPadding = contentPadding,
        onView = viewModel::setView,
        onStep = viewModel::step,
        onToday = viewModel::goToday,
        onDay = viewModel::showDay,
        onTogglePerson = viewModel::togglePerson,
        onClearFilter = viewModel::clearFilter,
        onToggleLayer = viewModel::toggleLayer,
        onOpenOccurrence = onOpenOccurrence,
        onNewEvent = onNewEvent,
        onQuickAdd = onQuickAdd,
    )
}

@Composable
fun CalendarScreen(
    state: CalendarUiState,
    contentPadding: PaddingValues = PaddingValues(),
    onView: (CalendarView) -> Unit = {},
    onStep: (Boolean) -> Unit = {},
    onToday: () -> Unit = {},
    onDay: (LocalDate) -> Unit = {},
    onTogglePerson: (String) -> Unit = {},
    onClearFilter: () -> Unit = {},
    onToggleLayer: (CalendarLayer) -> Unit = {},
    onOpenOccurrence: (EventOccurrence) -> Unit = {},
    onNewEvent: (LocalDate) -> Unit = {},
    onQuickAdd: (LocalDate?) -> Unit = {},
) {
    // The round + opens quick add on the shown week or the selected day (UX plan 1.2). Day has no +: an empty hour opens
    // the editor there. Caregivers cannot add events (1.13).
    RegisterAddButton(
        label = stringResource(Res.string.action_add),
        enabled = state.canEdit && state.view != CalendarView.Day,
    ) { onQuickAdd(state.addDate) }
    val anchor = state.anchor ?: return
    val today = state.today ?: anchor
    val people = state.people.associateBy { it.id }
    // "Idag" always brings today into view, also when the shown week already is the current one (UX plan 1.10).
    var todayRequests by remember { mutableIntStateOf(0) }
    val goToday = {
        todayRequests++
        onToday()
    }
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Header(state, anchor, today, onStep, goToday, onToggleLayer)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Spacing.screenHorizontal)) {
            val views = listOf(
                CalendarView.Day to Res.string.calendar_view_day,
                CalendarView.Week to Res.string.calendar_view_week,
                CalendarView.Month to Res.string.calendar_view_month,
                CalendarView.Agenda to Res.string.calendar_view_agenda,
            )
            views.forEachIndexed { i, (v, label) ->
                SegmentedButton(
                    selected = state.view == v,
                    onClick = { onView(v) },
                    shape = SegmentedButtonDefaults.itemShape(i, views.size),
                    colors = SelectionColors.segmentedButton(),
                    label = { Text(stringResource(label), maxLines = 1) },
                )
            }
        }
        if (state.people.isNotEmpty()) {
            PersonChips(
                state.people,
                state.personFilter,
                onTogglePerson,
                Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
                allLabel = stringResource(Res.string.calendar_filter_all),
                onAll = onClearFilter,
            )
        }
        val bottom = contentPadding.calculateBottomPadding() + Spacing.listBottomInset
        when (state.view) {
            CalendarView.Day -> DayView(state.days.firstOrNull(), people, bottom, onOpenOccurrence, onNewEvent, canAdd = state.canEdit)
            CalendarView.Week -> ListView(
                state.days,
                today,
                people,
                bottom,
                onOpenOccurrence,
                onDay,
                showEmptyDays = true,
                todayRequests = todayRequests,
            )
            CalendarView.Agenda -> ListView(
                state.days.filter {
                    it.occurrences.isNotEmpty() || it.holidays.isNotEmpty()
                },
                today,
                people,
                bottom,
                onOpenOccurrence,
                onDay,
                showEmptyDays = false,
                todayRequests = todayRequests,
            )
            CalendarView.Month -> MonthView(state.days, anchor, today, people, onDay)
        }
    }
}

@Composable
private fun Header(
    state: CalendarUiState,
    anchor: LocalDate,
    today: LocalDate,
    onStep: (Boolean) -> Unit,
    onToday: () -> Unit,
    onToggleLayer: (CalendarLayer) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onStep(false) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(Res.string.calendar_previous))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            // The year only shows when it is not this year ("oktober", "januari 2027").
            val month = monthName(anchor.month.ordinal + 1) + if (anchor.year != today.year) " " + anchor.year else ""
            val title = when (state.view) {
                CalendarView.Day -> formatDateLong(anchor, referenceYear = today.year).replaceFirstChar { it.uppercase() }
                CalendarView.Week -> weekLong(anchor.isoWeek.week)
                CalendarView.Month, CalendarView.Agenda -> month.replaceFirstChar { it.uppercase() }
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }, maxLines = 1)
            if (state.view == CalendarView.Week || state.view == CalendarView.Day) {
                Text(
                    if (state.view == CalendarView.Day) weekShort(anchor) else month,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val goToday = stringResource(Res.string.calendar_go_today)
        TextButton(onClick = onToday, modifier = Modifier.semantics { contentDescription = goToday }) {
            Text(stringResource(Res.string.today))
        }
        IconButton(onClick = { onStep(true) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(Res.string.calendar_next))
        }
        Box {
            IconButton(onClick = {
                menu = true
            }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.calendar_layers)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Text(
                    stringResource(Res.string.calendar_layers),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(Spacing.md),
                )
                listOf(
                    CalendarLayer.Family to Res.string.layer_family,
                    CalendarLayer.Subscriptions to Res.string.layer_subscriptions,
                    CalendarLayer.Device to Res.string.layer_device,
                    CalendarLayer.Holidays to Res.string.layer_holidays,
                    CalendarLayer.Custody to Res.string.layer_custody,
                ).forEach { (layer, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        trailingIcon = { Switch(checked = layer in state.layers, onCheckedChange = null) },
                        onClick = { onToggleLayer(layer) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ListView(
    days: List<CalendarDay>,
    today: LocalDate,
    people: Map<String, FamilyPerson>,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onOpen: (EventOccurrence) -> Unit,
    onDay: (LocalDate) -> Unit,
    showEmptyDays: Boolean,
    todayRequests: Int,
) {
    val empty = days.isEmpty() || (days.all { it.occurrences.isEmpty() && it.holidays.isEmpty() } && !showEmptyDays)
    val listState = rememberLazyListState()
    // Open the week at today (not Monday); other weeks open at their start. "Idag" scrolls back to today.
    LaunchedEffect(days.firstOrNull()?.date, todayRequests) {
        val index = days.indexOfFirst { it.date >= today }
        listState.scrollToItem(if (index > 0) index + (if (empty) 1 else 0) else 0)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (empty) {
            item {
                Text(
                    stringResource(Res.string.calendar_empty_week),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = Spacing.xl),
                )
            }
        }
        items(days, key = { it.date.toString() }) { day ->
            DaySection(day, today, people, onOpen, onDay)
        }
    }
}

@Composable
private fun DaySection(
    day: CalendarDay,
    today: LocalDate,
    people: Map<String, FamilyPerson>,
    onOpen: (EventOccurrence) -> Unit,
    onDay: (LocalDate) -> Unit,
) {
    val isToday = day.date == today
    val dateText = formatDateLong(day.date, referenceYear = today.year).replaceFirstChar { it.uppercase() }
    // An empty day is quiet on screen; TalkBack still hears "Inget planerat" with the date.
    val emptyDay = stringResource(Res.string.calendar_empty_day)
    val headerDescription = if (day.occurrences.isEmpty()) "$dateText, $emptyDay" else null
    Row(Modifier.fillMaxWidth()) {
        // Custody band: a thin coloured strip per schedule ("bakgrundsband").
        day.custody.forEach { h ->
            val colors = PussletThemeExtras.colors.member(h.color)
            Box(Modifier.width(4.dp).heightIn(min = 56.dp).background(colors.container).semantics { contentDescription = h.name })
            Spacer(Modifier.width(2.dp))
        }
        Column(Modifier.weight(1f).padding(start = Spacing.sm)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .semantics { if (headerDescription != null) contentDescription = headerDescription }
                    .clickable { onDay(day.date) }
                    .padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                DateBadge(day.date, isToday, day.redDay)
                Text(
                    dateText,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (day.redDay) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            day.holidays.forEach { h ->
                Text(
                    stringResource(Labels.holiday(h.id)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            day.occurrences.forEach { o -> OccurrenceRow(o, day.date, people, onOpen) }
        }
    }
}

@Composable
private fun DateBadge(date: LocalDate, isToday: Boolean, red: Boolean) {
    val bg = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = when {
        isToday -> MaterialTheme.colorScheme.onPrimary
        red -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(Modifier.size(36.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(date.day.toString(), color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun OccurrenceRow(o: EventOccurrence, onDate: LocalDate, people: Map<String, FamilyPerson>, onOpen: (EventOccurrence) -> Unit) {
    val participants = o.event.participantIds.mapNotNull { people[it] }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).clickable {
            onOpen(o)
        }.padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        val time = when {
            o.allDay -> stringResource(Res.string.all_day)
            o.start.date < onDate -> "…" + formatTime(o.end.time)
            else -> formatTime(o.start.time)
        }
        Text(time, style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 52.dp))
        when {
            participants.isNotEmpty() -> AvatarStack(participants, size = 24.dp)
            o.sourceColor != null -> o.sourceColor?.let {
                ColorBadge(it, o.sourceName?.take(1).orEmpty(), o.sourceName.orEmpty(), size = 24.dp)
            }
            else -> Spacer(Modifier.width(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                o.event.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // The start time stands in its own column, so the detail line only says when it ends ("till 16:00").
            val endsHere = !o.allDay && o.start.date == onDate && o.end.date == onDate && o.end.time != o.start.time
            val details = buildList {
                if (endsHere) add(stringResource(Res.string.calendar_until, formatTime(o.end.time)))
                if (o.event.location.isNotBlank()) add(o.event.location)
            }
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Day view: all-day events on top, then an hour grid from 06 to 22 with events placed at their start hour. */
@Composable
private fun DayView(
    day: CalendarDay?,
    people: Map<String, FamilyPerson>,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onOpen: (EventOccurrence) -> Unit,
    onNew: (LocalDate) -> Unit,
    canAdd: Boolean,
) {
    if (day == null) return
    val timed = day.occurrences.filter { !it.allDay }
    val newEvent = stringResource(Res.string.calendar_new_event)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, bottom = bottomPadding),
    ) {
        item {
            day.holidays.forEach { h ->
                Text(
                    stringResource(Labels.holiday(h.id)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            day.occurrences.filter { it.allDay }.forEach { OccurrenceRow(it, day.date, people, onOpen) }
        }
        val firstHour = minOf(DAY_START, timed.minOfOrNull { if (it.start.date < day.date) 0 else it.start.hour } ?: DAY_START)
        val lastHour = maxOf(DAY_END, timed.maxOfOrNull { it.end.hour } ?: DAY_END)
        items((firstHour..lastHour).toList(), key = { "h$it" }) { hour ->
            val starting = timed.filter { (if (it.start.date < day.date) 0 else it.start.hour) == hour }
            // An empty hour opens the editor for that day; caregivers cannot add events (UX plan 1.13).
            val addHere = canAdd && starting.isEmpty()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    .then(if (addHere) Modifier.clickable(onClickLabel = newEvent) { onNew(day.date) } else Modifier),
            ) {
                // 24-hour labels without ":00" ("08").
                Text(
                    hour.toString().padStart(2, '0'),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(48.dp).padding(top = Spacing.xs),
                )
                Column(Modifier.weight(1f)) {
                    starting.forEach { o -> EventBlock(o, people, onOpen) }
                }
            }
        }
    }
}

@Composable
private fun EventBlock(o: EventOccurrence, people: Map<String, FamilyPerson>, onOpen: (EventOccurrence) -> Unit) {
    val first = o.event.participantIds.firstNotNullOfOrNull { people[it] }
    val colors = first?.let { PussletThemeExtras.colors.member(it.color) }
    val minutes = EventExpander.wallClockDuration(o.start, o.end).inWholeMinutes.coerceIn(30, 180)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(2.dp)
            .heightIn(min = (minutes * 0.8).dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors?.container ?: MaterialTheme.colorScheme.secondaryContainer)
            .clickable { onOpen(o) }
            .padding(Spacing.sm),
    ) {
        Text(
            o.event.title,
            color = colors?.content ?: MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            formatTimeRange(o.start.time, o.end.time),
            color = colors?.content ?: MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Month grid with ISO week numbers in the first column. Dots show who has something that day. */
@Composable
private fun MonthView(
    days: List<CalendarDay>,
    anchor: LocalDate,
    today: LocalDate,
    people: Map<String, FamilyPerson>,
    onDay: (LocalDate) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                stringResource(Res.string.calendar_week_abbr),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(32.dp),
                textAlign = TextAlign.Center,
            )
            kotlinx.datetime.DayOfWeek.entries.forEach { d ->
                Text(
                    weekdayShort(d),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
        }
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    week.first().date.isoWeek.week.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp).padding(top = Spacing.md),
                    textAlign = TextAlign.Center,
                )
                week.forEach { day ->
                    val inMonth = day.date.month == anchor.month
                    Column(
                        Modifier.weight(1f).aspectRatio(0.8f).clip(RoundedCornerShape(8.dp)).clickable { onDay(day.date) }.padding(2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val isToday = day.date == today
                        Box(
                            Modifier.size(
                                28.dp,
                            ).clip(
                                CircleShape,
                            ).background(
                                if (isToday) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                day.date.day.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = when {
                                    isToday -> MaterialTheme.colorScheme.onPrimary
                                    day.redDay -> MaterialTheme.colorScheme.error
                                    !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                        day.custody.firstOrNull()?.let { h ->
                            Box(Modifier.fillMaxWidth().height(3.dp).background(PussletThemeExtras.colors.member(h.color).container))
                        }
                        val who = day.occurrences.flatMap { o -> o.event.participantIds.mapNotNull { people[it] } }.distinctBy { it.id }
                        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                            who.take(MAX_DOTS).forEach { p ->
                                Box(Modifier.size(6.dp).clip(CircleShape).background(PussletThemeExtras.colors.member(p.color).container))
                            }
                        }
                        val extra = day.occurrences.size
                        if (extra >
                            0
                        ) {
                            Text(
                                extra.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val DAY_START = 6
private const val DAY_END = 22
private const val MAX_DOTS = 4
