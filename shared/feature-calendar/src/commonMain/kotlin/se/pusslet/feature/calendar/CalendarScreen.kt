package se.pusslet.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.holidays.HolidayKind
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import se.pusslet.core.time.startOfWeek
import se.pusslet.data.repository.CalendarLayer
import se.pusslet.data.repository.CalendarView
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.NavRow
import se.pusslet.ui.components.QuietLine
import se.pusslet.ui.components.RegisterAddButton
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.components.WeekSwitcher
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.monthName
import se.pusslet.ui.format.monthShort
import se.pusslet.ui.format.weekdayShort
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.PussletThemeExtras
import se.pusslet.ui.theme.Spacing

/** The calendar tab. [onOpenDay] opens the day screen (`Route.CalendarDay`) from a day header. */
@Composable
fun CalendarRoute(
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
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
        onOpenDay = onOpenDay,
        onSelectDay = viewModel::selectDay,
        onTogglePerson = viewModel::togglePerson,
        onClearPeople = viewModel::clearPeople,
        onToggleLayer = viewModel::toggleLayer,
        onShowAll = viewModel::showAll,
        onOpenOccurrence = onOpenOccurrence,
        onTake = viewModel::take,
        onQuickAdd = onQuickAdd,
    )
}

/**
 * One control row on top (UX plan, Kalender 1): the week (or month) with ‹ ›, "Vecka | Månad" and the filter. The week is a
 * list with the date in a column on the left; school and preschool are quiet lines under the date.
 */
@Composable
fun CalendarScreen(
    state: CalendarUiState,
    contentPadding: PaddingValues = PaddingValues(),
    onView: (CalendarView) -> Unit = {},
    onStep: (Boolean) -> Unit = {},
    onToday: () -> Unit = {},
    onOpenDay: (LocalDate) -> Unit = {},
    onSelectDay: (LocalDate) -> Unit = {},
    onTogglePerson: (String) -> Unit = {},
    onClearPeople: () -> Unit = {},
    onToggleLayer: (CalendarLayer) -> Unit = {},
    onShowAll: () -> Unit = {},
    onOpenOccurrence: (EventOccurrence) -> Unit = {},
    onTake: (EventOccurrence, HandoffKind) -> Unit = { _, _ -> },
    onQuickAdd: (LocalDate?) -> Unit = {},
) {
    // The round + opens quick add on the shown week or the selected day (UX plan 1.2). Caregivers cannot add events (1.13).
    RegisterAddButton(label = stringResource(Res.string.action_add), enabled = state.canEdit) { onQuickAdd(state.addDate) }
    val anchor = state.anchor ?: return
    val today = state.today ?: anchor
    val people = state.people.associateBy { it.id }
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    // "Idag" (only offered on another week or month) also brings today into view in the week list.
    var todayRequests by remember { mutableIntStateOf(0) }
    val bottom = contentPadding.calculateBottomPadding() + Spacing.listBottomInset
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Controls(
            state = state,
            anchor = anchor,
            today = today,
            onStep = onStep,
            onView = onView,
            onToday = {
                todayRequests++
                onToday()
            },
            onFilter = { filterOpen = true },
        )
        filterSummary(state)?.let { FilterLine(it, onShowAll) }
        if (state.view == CalendarView.Month) {
            MonthView(state, anchor, today, people, bottom, onSelectDay, onOpenDay, onOpenOccurrence, onTake)
        } else {
            WeekList(state, today, people, bottom, todayRequests, onOpenDay, onOpenOccurrence, onTake)
        }
    }
    if (filterOpen) {
        CalendarFilterSheet(state, onTogglePerson, onClearPeople, onToggleLayer, onDismiss = { filterOpen = false })
    }
}

@Composable
private fun Controls(
    state: CalendarUiState,
    anchor: LocalDate,
    today: LocalDate,
    onStep: (Boolean) -> Unit,
    onView: (CalendarView) -> Unit,
    onToday: () -> Unit,
    onFilter: () -> Unit,
) {
    val month = state.view == CalendarView.Month
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screenHorizontal)) {
        WeekSwitcher(
            title = if (month) monthTitle(anchor, today) else weekTitle(anchor, today),
            subtitle = if (month) null else custodyWeekText(state.custodyWeek),
            onPrevious = { onStep(false) },
            onNext = { onStep(true) },
            onToday = if (state.showsToday) null else onToday,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow {
                val views = listOf(CalendarView.Week to Res.string.calendar_view_week, CalendarView.Month to Res.string.calendar_view_month)
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
            Spacer(Modifier.weight(1f))
            FilterButton(state, onFilter)
        }
    }
}

/** "Vecka 41 · oktober", "Vecka 44 · okt–nov"; the year only when it is not this year. */
@Composable
private fun weekTitle(anchor: LocalDate, today: LocalDate): String {
    val monday = anchor.startOfWeek()
    val sunday = monday.plusDays(6)
    val year = if (sunday.year != today.year) " " + sunday.year else ""
    val months = if (monday.month == sunday.month) {
        monthName(monday.month.ordinal + 1)
    } else {
        stringResource(Res.string.calendar_range, monthShort(monday.month.ordinal + 1), monthShort(sunday.month.ordinal + 1))
    }
    return stringResource(Res.string.calendar_week_title, anchor.isoWeek.week, months + year)
}

/** "Oktober", "Januari 2027". */
@Composable
private fun monthTitle(anchor: LocalDate, today: LocalDate): String =
    monthName(anchor.month.ordinal + 1).replaceFirstChar { it.uppercase() } + if (anchor.year != today.year) " " + anchor.year else ""

/** The filter icon, with a dot while a filter is on. */
@Composable
private fun FilterButton(state: CalendarUiState, onClick: () -> Unit) {
    val summary = filterSummary(state)
    IconButton(onClick = onClick, modifier = Modifier.semantics { if (summary != null) stateDescription = summary }) {
        BadgedBox(badge = { if (state.filterActive) Badge(containerColor = MaterialTheme.colorScheme.primary) }) {
            Icon(CalendarIcons.Filter, contentDescription = stringResource(Res.string.calendar_layers))
        }
    }
}

/** "Bara Simon · Visa alla": an active filter is never only a dot (UX plan, Kalender 3). */
@Composable
private fun FilterLine(summary: String, onShowAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.screenHorizontal, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onShowAll) { Text(stringResource(Res.string.action_show_all)) }
    }
}

@Composable
private fun WeekList(
    state: CalendarUiState,
    today: LocalDate,
    people: Map<String, FamilyPerson>,
    bottomPadding: Dp,
    todayRequests: Int,
    onOpenDay: (LocalDate) -> Unit,
    onOpen: (EventOccurrence) -> Unit,
    onTake: (EventOccurrence, HandoffKind) -> Unit,
) {
    val days = state.days
    val empty = days.all { it.isEmpty }
    val first = if (empty) 1 else 0
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // The week opens at Monday so that most of it shows (UX plan, Kalender 1: at least five days). Today is scrolled into
    // view only when its date would be hidden under the bottom bar; "Idag" does the same.
    LaunchedEffect(days.firstOrNull()?.date, todayRequests) {
        listState.scrollToItem(0)
        val index = days.indexOfFirst { it.date == today }
        if (index < 0) return@LaunchedEffect
        val layout = snapshotFlow { listState.layoutInfo }.first { it.visibleItemsInfo.isNotEmpty() }
        val item = layout.visibleItemsInfo.firstOrNull { it.index == index + first }
        val limit = layout.viewportEndOffset - layout.afterContentPadding + with(density) { Spacing.listBottomInset.roundToPx() }
        if (item == null || item.offset + with(density) { DateCellHeight.roundToPx() } > limit) listState.scrollToItem(index + first)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = Spacing.screenHorizontal,
            end = Spacing.screenHorizontal,
            top = Spacing.xs,
            bottom = bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (empty) item(key = "empty") { QuietLine(stringResource(Res.string.calendar_empty_week)) }
        itemsIndexed(days, key = { _, day -> day.date.toString() }) { index, day ->
            Column {
                // A thin line between the days, so each date reads as its own block.
                if (index > 0) HorizontalDivider(Modifier.padding(bottom = Spacing.sm), color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    DateCell(day, day.date == today, today, onOpenDay)
                    Column(Modifier.weight(1f)) { DayContent(day, people, state.canEdit, onOpen, onTake, changeovers = false) }
                }
            }
        }
    }
}

/** The day header of the week list: weekday over the date, today in sage, red days in red. Opens the day screen. */
@Composable
private fun DateCell(day: CalendarDay, isToday: Boolean, today: LocalDate, onOpenDay: (LocalDate) -> Unit) {
    val date = day.date
    val colors = MaterialTheme.colorScheme
    val long = formatDateLong(date, referenceYear = today.year).replaceFirstChar { it.uppercase() }
    // An empty day is quiet on screen; TalkBack still hears "Inget planerat" with the date.
    val description = if (day.isEmpty) long + ", " + stringResource(Res.string.calendar_empty_day) else long
    val openDay = stringResource(Res.string.calendar_open_day)
    Column(
        Modifier
            .width(DateColumn)
            .heightIn(min = DateCellHeight)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = openDay, role = Role.Button) { onOpenDay(date) }
            .semantics {
                heading()
                contentDescription = description
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            weekdayShort(date.dayOfWeek),
            style = MaterialTheme.typography.labelMedium,
            color = if (day.redDay) colors.error else colors.onSurfaceVariant,
        )
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(if (isToday) colors.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.day.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    isToday -> colors.onPrimary
                    day.redDay -> colors.error
                    else -> colors.onSurface
                },
            )
        }
    }
}

/** Month (UX plan, Kalender 5): the grid on top, the selected day below. A tap on a day selects it and stays in the month. */
@Composable
private fun MonthView(
    state: CalendarUiState,
    anchor: LocalDate,
    today: LocalDate,
    people: Map<String, FamilyPerson>,
    bottomPadding: Dp,
    onSelect: (LocalDate) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onOpen: (EventOccurrence) -> Unit,
    onTake: (EventOccurrence, HandoffKind) -> Unit,
) {
    val selected = state.selectedDay
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, bottom = bottomPadding),
    ) {
        item(key = "grid") { MonthGrid(state.days, anchor, today, state.selected, people, onSelect) }
        if (selected != null) {
            item(key = "header") {
                NavRow(
                    text = formatDateLong(selected.date, referenceYear = today.year).replaceFirstChar { it.uppercase() },
                    onClick = { onOpenDay(selected.date) },
                    modifier = Modifier.padding(top = Spacing.sm).semantics { heading() },
                )
            }
            item(key = "day") {
                Column {
                    if (selected.isEmpty) {
                        QuietLine(stringResource(Res.string.calendar_empty_day))
                    } else {
                        DayContent(selected, people, state.canEdit, onOpen, onTake)
                    }
                }
            }
        }
    }
}

/** Month grid with ISO week numbers in the first column. */
@Composable
private fun MonthGrid(
    days: List<CalendarDay>,
    anchor: LocalDate,
    today: LocalDate,
    selected: LocalDate?,
    people: Map<String, FamilyPerson>,
    onSelect: (LocalDate) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
            Text(
                stringResource(Res.string.calendar_week_abbr),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.width(WeekNumberColumn),
                textAlign = TextAlign.Center,
            )
            DayOfWeek.entries.forEach { d ->
                Text(
                    weekdayShort(d),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
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
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.width(WeekNumberColumn).padding(top = Spacing.sm),
                    textAlign = TextAlign.Center,
                )
                week.forEach { day ->
                    MonthCell(
                        day = day,
                        inMonth = day.date.month == anchor.month,
                        isToday = day.date == today,
                        isSelected = day.date == selected,
                        people = people,
                        onSelect = onSelect,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthCell(
    day: CalendarDay,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    people: Map<String, FamilyPerson>,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val extras = PussletThemeExtras.colors
    val redDayName = day.holidays.firstOrNull { it.kind == HolidayKind.PublicHoliday }?.let { stringResource(Labels.holiday(it.id)) }
    val description = buildList {
        add(formatDateLong(day.date))
        redDayName?.let { add(it) }
        val count = day.events.size
        if (count == 1) add(stringResource(Res.string.calendar_events_one))
        if (count > 1) add(stringResource(Res.string.calendar_events_many, count))
    }.joinToString(", ")
    Column(
        modifier
            .heightIn(min = MonthCellHeight)
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = isSelected, onClick = { onSelect(day.date) })
            .semantics { contentDescription = description }
            .padding(vertical = Spacing.xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        val background = when {
            isToday -> colors.primary
            isSelected -> colors.primaryContainer
            else -> Color.Transparent
        }
        Box(Modifier.size(32.dp).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
            Text(
                day.date.day.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    isToday -> colors.onPrimary
                    isSelected -> colors.onPrimaryContainer
                    day.redDay -> colors.error
                    !inMonth -> colors.onSurfaceVariant.copy(alpha = 0.5f)
                    else -> colors.onSurface
                },
            )
        }
        // Red days get the holiday's name, as much of it as fits; the selected day below shows it in full.
        redDayName?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = colors.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        day.custody.firstOrNull()?.let { h ->
            Box(Modifier.fillMaxWidth(0.6f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(extras.member(h.color).container))
        }
        // Dots only for what is not routine: at most three, then "+N".
        if (day.events.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                day.events.take(MAX_DOTS).forEach { o ->
                    val color = o.event.participantIds.firstNotNullOfOrNull { people[it] }?.let { extras.member(it.color).container }
                        ?: o.sourceColor?.let { extras.member(it).container }
                        ?: colors.onSurfaceVariant
                    Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                }
                if (day.events.size > MAX_DOTS) {
                    Text(
                        stringResource(Res.string.calendar_more_count, day.events.size - MAX_DOTS),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The date column of the week list; also the minimum height of a day. */
private val DateColumn = 48.dp
private val DateCellHeight = 56.dp
private val WeekNumberColumn = 28.dp
private val MonthCellHeight = 52.dp
private const val MAX_DOTS = 3
