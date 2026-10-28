package se.pusslet.feature.calendar.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import se.pusslet.core.custody.SharedHome
import se.pusslet.core.model.Event
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.Handoff
import se.pusslet.core.quickadd.EventPrefill
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.core.recurrence.Frequency
import se.pusslet.core.recurrence.RRule
import se.pusslet.core.time.plusDays
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.HandoffAssigner
import se.pusslet.data.usecase.OtherHomeSharing
import se.pusslet.data.usecase.SharedSpace
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** The repeat choices offered in the editor. [Custom] keeps an imported/parsed rule that does not fit the others. */
enum class RepeatOption { None, Daily, Weekdays, Weekly, Biweekly, Monthly, Yearly, Custom }

data class EventDraft(
    val title: String = "",
    val allDay: Boolean = false,
    val startDate: LocalDate,
    val startTime: LocalTime = LocalTime(9, 0),
    val endDate: LocalDate = startDate,
    val endTime: LocalTime = LocalTime(10, 0),
    val repeat: RepeatOption = RepeatOption.None,
    val customRule: String? = null,
    val participants: Set<String> = emptySet(),
    val dropOffBy: String? = null,
    val dropOffTime: LocalTime? = null,
    val pickUpBy: String? = null,
    val pickUpTime: LocalTime? = null,
    val location: String = "",
    val tags: Set<String> = emptySet(),
    val packing: List<String> = emptyList(),
    val reminder: Int? = null,
    val note: String = "",
    val sharedWithCaregivers: Boolean = true,
    /** Chosen in the editor; null = decided by what the event is about (ADR-056). */
    val shareWithOtherHome: Boolean? = null,
) {
    val startDateTime: LocalDateTime get() = LocalDateTime(startDate, if (allDay) LocalTime(0, 0) else startTime)

    /** All-day ends are stored exclusive (the day after the last day). */
    val endDateTime: LocalDateTime get() = if (allDay) {
        LocalDateTime(
            endDate.plusDays(1),
            LocalTime(0, 0),
        )
    } else {
        LocalDateTime(endDate, endTime)
    }

    val endsBeforeStart: Boolean get() = endDateTime < startDateTime
}

enum class EditorError { TitleRequired, EndBeforeStart }

data class EventEditorState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    /** Editing one occurrence of a series (saved as an override). */
    val singleOccurrence: Boolean = false,
    val draft: EventDraft? = null,
    val people: List<FamilyPerson> = emptyList(),
    val errors: Set<EditorError> = emptySet(),
    val saving: Boolean = false,
    val done: Boolean = false,
    /** Spaces shared with the other home, for the "shared with the other home" switch. */
    val spaces: List<SharedSpace> = emptyList(),
    /** The event already lives in a shared space. */
    val wasShared: Boolean = false,
) {
    val adults: List<FamilyPerson> get() = people.filter { !it.isChild }

    private val sharedChildren: Set<String> get() = spaces.flatMap { s -> s.children.map { it.id } }.toSet()

    /** The switch is offered for events about a shared child (and for ones already shared). */
    fun canShare(d: EventDraft): Boolean =
        spaces.isNotEmpty() && !singleOccurrence && (wasShared || d.participants.any { it in sharedChildren })

    /** What the switch shows: the user's choice, else where the event is or would go. */
    fun shares(d: EventDraft): Boolean = d.shareWithOtherHome ?: if (isNew) {
        SharedHome(sharedChildren, people.filter { it.isChild }.map { it.id }.toSet()).shares(
            Event("", "", d.startDateTime, d.endDateTime, participantIds = d.participants.toList()),
        )
    } else {
        wasShared
    }

    /** Adults offered for drop-off and pick-up: the other home's too when the event is shared. */
    fun handoffAdults(d: EventDraft): List<FamilyPerson> =
        if (canShare(d) && shares(d)) adults + otherAdults else adults

    private val otherAdults: List<FamilyPerson>
        get() = spaces.flatMap { it.otherAdults }.distinctBy { it.id }.map { FamilyPerson.of(it).copy(otherHome = true) }
}

/**
 * Creates and edits family events. Editing a single occurrence of a recurring event writes an override document
 * (`seriesId@date`), so the rest of the series is untouched.
 */
class EventEditorViewModel(
    private val calendar: CalendarRepository,
    private val family: FamilyRepository,
    private val sessions: SessionProvider,
    private val clock: Clock,
    private val zone: TimeZone,
    /** Spaces shared with the other home (ADR-056); null in tests without sharing. */
    private val otherHome: OtherHomeSharing? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(EventEditorState())
    val state: StateFlow<EventEditorState> = _state.asStateFlow()
    private var original: Event? = null
    private var occurrenceDate: LocalDate? = null

    /** [prefill] is what quick add understood ("Fyll i själv", UX plan 4.6); it only applies to a new event. */
    fun load(eventId: String?, date: LocalDate?, occurrence: LocalDate?, prefill: EventPrefill? = null) {
        if (_state.value.loaded) return
        viewModelScope.launch {
            val people = family.people.first()
            val existing = eventId?.let { calendar.event(it) }
            original = existing
            occurrenceDate = existing?.originalDate ?: occurrence
            val single = existing != null && (existing.isOverride || (existing.isRecurring && occurrence != null))
            val draft = when {
                existing == null && prefill != null -> draftFrom(prefill)
                existing == null -> newDraft(date ?: clock.todayIn(zone))
                existing.isOverride -> draftOf(existing)
                single -> {
                    // Prefer an existing override for that date, else start from the series moved to the date.
                    val override = calendar.event(HandoffAssigner.overrideId(existing.id, requireNotNull(occurrence)))
                    if (override != null) {
                        draftOf(override)
                    } else {
                        val duration = EventExpander.wallClockDuration(existing.start, existing.end)
                        val start = LocalDateTime(occurrence, existing.start.time)
                        draftOf(existing.copy(rrule = null, start = start, end = EventExpander.addWallClock(start, duration)))
                    }
                }
                else -> draftOf(existing)
            }
            val spaces = otherHome?.spaces?.first().orEmpty()
            val wasShared = existing?.let { calendar.sharedSpaceOf(it.id) } != null
            _state.value = EventEditorState(
                loaded = true,
                isNew = existing == null,
                singleOccurrence = single,
                draft = draft,
                people = people,
                spaces = spaces,
                wasShared = wasShared,
            )
        }
    }

    private fun newDraft(date: LocalDate): EventDraft {
        val now = clock.now().toLocalDateTime(zone)
        // New events start at the next whole hour today, otherwise at 09:00.
        val start = if (date == now.date && now.hour < LAST_SUGGESTED_HOUR) LocalTime(now.hour + 1, 0) else LocalTime(9, 0)
        val end = LocalTime((start.hour + 1).coerceAtMost(23), if (start.hour + 1 > 23) 59 else 0)
        return EventDraft(startDate = date, startTime = start, endDate = date, endTime = end)
    }

    /** A new event from quick add's text: what it understood replaces the defaults, the rest stays as for any new event. */
    private fun draftFrom(p: EventPrefill): EventDraft {
        val base = newDraft(p.date)
        val start = p.startTime ?: base.startTime
        val end = p.endTime ?: p.startTime?.let { LocalTime((it.hour + 1).coerceAtMost(23), if (it.hour + 1 > 23) 59 else it.minute) }
            ?: base.endTime
        val (repeat, custom) = repeatOf(p.rrule, p.date)
        return base.copy(
            title = p.title,
            allDay = p.allDay,
            startTime = start,
            // An evening that runs past midnight ends the next day.
            endDate = p.endDate ?: if (!p.allDay && end < start) p.date.plusDays(1) else p.date,
            endTime = end,
            repeat = repeat,
            customRule = custom,
            participants = p.participantIds.toSet(),
            location = p.location,
            tags = p.tags.toSet(),
            packing = p.packingItems,
        )
    }

    fun edit(transform: (EventDraft) -> EventDraft) = _state.update { s ->
        val next = s.draft?.let(transform) ?: return@update s
        // Clear an error as soon as the user has fixed it; new errors only appear on save.
        val fixed = setOfNotNull(
            EditorError.TitleRequired.takeIf { next.title.isNotBlank() },
            EditorError.EndBeforeStart.takeIf { !next.endsBeforeStart },
        )
        s.copy(draft = next, errors = s.errors - fixed)
    }

    /** Moving the start keeps the duration, like most calendars. */
    fun setStartDate(date: LocalDate) = moveStart(date, null)

    fun setStartTime(time: LocalTime) = moveStart(null, time)

    private fun moveStart(date: LocalDate?, time: LocalTime?) = edit { d ->
        val span = EventExpander.wallClockDuration(d.startDateTime, d.endDateTime).let { if (d.allDay) it - 24.hours else it }
        val newDate = date ?: d.startDate
        val newTime = time ?: d.startTime
        val start = LocalDateTime(newDate, if (d.allDay) LocalTime(0, 0) else newTime)
        val end = EventExpander.addWallClock(start, span.coerceAtLeast(Duration.ZERO))
        d.copy(startDate = newDate, startTime = newTime, endDate = end.date, endTime = if (d.allDay) d.endTime else end.time)
    }

    fun togglePerson(id: String) = edit { d ->
        d.copy(
            participants = if (id in
                d.participants
            ) {
                d.participants - id
            } else {
                d.participants + id
            },
        )
    }

    fun toggleTag(tag: String) = edit { d -> d.copy(tags = if (tag in d.tags) d.tags - tag else d.tags + tag) }

    fun addPacking(item: String) = edit { d -> if (item.isBlank()) d else d.copy(packing = (d.packing + item.trim()).distinct()) }

    fun removePacking(item: String) = edit { d -> d.copy(packing = d.packing - item) }

    fun save() {
        val s = _state.value
        val d = s.draft ?: return
        val errors = buildSet {
            if (d.title.isBlank()) add(EditorError.TitleRequired)
            if (d.endsBeforeStart) add(EditorError.EndBeforeStart)
        }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val base = original
            val occurrence = occurrenceDate
            val event = when {
                base == null -> apply(
                    Event(
                        id = family.newId(),
                        title = "",
                        start = d.startDateTime,
                        end = d.endDateTime,
                        createdBy = sessions.session.value.memberId,
                        // New events use the device zone; edits keep the event's own zone so its wall-clock times stay put.
                        timeZoneId = zone.id,
                    ),
                    d,
                )
                base.isOverride -> apply(base, d)
                s.singleOccurrence && occurrence != null -> {
                    val id = HandoffAssigner.overrideId(base.id, occurrence)
                    val start =
                        calendar.event(id) ?: base.copy(id = id, seriesId = base.id, originalDate = occurrence, exDates = emptyList())
                    apply(start, d).copy(rrule = null)
                }
                else -> apply(base, d)
            }
            calendar.saveEvent(event)
            placeForOtherHome(s, d, event, base)
            _state.update { it.copy(saving = false, done = true) }
        }
    }

    /**
     * Puts the event where the switch says: in the space of the shared child it is about, or back home. A changed
     * occurrence follows its series.
     */
    private suspend fun placeForOtherHome(s: EventEditorState, d: EventDraft, event: Event, base: Event?) {
        if (s.spaces.isEmpty()) return
        if (s.singleOccurrence && base != null) {
            calendar.moveEvent(event.id, calendar.sharedSpaceOf(base.seriesId ?: base.id))
            return
        }
        if (!s.canShare(d)) return
        val target = if (s.shares(d)) {
            s.spaces.firstOrNull { space -> space.children.any { it.id in d.participants } }?.id ?: s.spaces.first().id
        } else {
            null
        }
        if (calendar.sharedSpaceOf(event.id) != target) calendar.moveEvent(event.id, target)
    }

    /** Deletes the whole event/series, or only the occurrence being edited. */
    fun delete(onlyThisOccurrence: Boolean) {
        val base = original ?: return
        viewModelScope.launch {
            val occurrence = occurrenceDate
            when {
                base.isOverride -> {
                    // An override is "this occurrence": remove it and keep the series from showing that date.
                    base.seriesId?.let { series -> base.originalDate?.let { calendar.skipOccurrence(series, it) } }
                    calendar.deleteEvent(base.id)
                }
                onlyThisOccurrence && base.isRecurring && occurrence != null -> {
                    calendar.skipOccurrence(base.id, occurrence)
                    calendar.event(HandoffAssigner.overrideId(base.id, occurrence))?.let { calendar.deleteEvent(it.id) }
                }
                else -> calendar.deleteEvent(base.id)
            }
            _state.update { it.copy(done = true) }
        }
    }

    private fun apply(event: Event, d: EventDraft): Event = event.copy(
        title = d.title.trim(),
        allDay = d.allDay,
        start = d.startDateTime,
        end = d.endDateTime,
        rrule = if (event.isOverride) null else ruleFor(d),
        participantIds = d.participants.toList(),
        dropOff = if (d.allDay || (d.dropOffBy == null && d.dropOffTime == null)) null else Handoff(d.dropOffBy, d.dropOffTime),
        pickUp = if (d.allDay || (d.pickUpBy == null && d.pickUpTime == null)) null else Handoff(d.pickUpBy, d.pickUpTime),
        location = d.location.trim(),
        tags = d.tags.toList(),
        packingItems = d.packing,
        reminders = listOfNotNull(d.reminder),
        note = d.note.trim(),
        sharedWithCaregivers = d.sharedWithCaregivers,
    )

    companion object {
        private const val LAST_SUGGESTED_HOUR = 22
        val reminderOptions: List<Int?> = listOf(null, 10, 30, 60, 120, 24 * 60)

        fun ruleFor(d: EventDraft): String? {
            val day = d.startDate.dayOfWeek
            return when (d.repeat) {
                RepeatOption.None -> null
                RepeatOption.Daily -> RRule.daily().format()
                RepeatOption.Weekdays -> RRule.weekly(
                    DayOfWeek.MONDAY,
                    DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY,
                    DayOfWeek.FRIDAY,
                ).format()
                RepeatOption.Weekly -> RRule.weekly(day).format()
                RepeatOption.Biweekly -> RRule.weekly(day, interval = 2).format()
                RepeatOption.Monthly -> RRule.monthlyOnDay(d.startDate.day).format()
                RepeatOption.Yearly -> RRule.yearly().format()
                RepeatOption.Custom -> d.customRule
            }
        }

        /** Maps a stored RRULE to the editor's simple choices; anything richer stays [RepeatOption.Custom] and is kept as-is. */
        fun repeatOf(rrule: String?, start: LocalDate): Pair<RepeatOption, String?> {
            if (rrule.isNullOrBlank()) return RepeatOption.None to null
            val rule = RRule.parse(rrule) ?: return RepeatOption.Custom to rrule
            val bounded = rule.count != null || rule.until != null
            val plain = !bounded && rule.byMonth.isEmpty() && rule.bySetPos.isEmpty() && rule.unsupported.isEmpty()
            val days = rule.byDay.map { it.day }
            val onStartDay = days.isEmpty() || days == listOf(start.dayOfWeek)
            val onStartDate = rule.byDay.isEmpty() && (rule.byMonthDay.isEmpty() || rule.byMonthDay == listOf(start.day))
            val option = when {
                !plain -> RepeatOption.Custom
                rule.freq == Frequency.DAILY && rule.interval == 1 && days.isEmpty() -> RepeatOption.Daily
                rule.freq == Frequency.WEEKLY && rule.interval == 1 && days.toSet() == WEEKDAYS -> RepeatOption.Weekdays
                rule.freq == Frequency.WEEKLY && rule.interval == 1 && onStartDay -> RepeatOption.Weekly
                rule.freq == Frequency.WEEKLY && rule.interval == 2 && onStartDay -> RepeatOption.Biweekly
                rule.freq == Frequency.MONTHLY && rule.interval == 1 && onStartDate -> RepeatOption.Monthly
                rule.freq == Frequency.YEARLY && rule.interval == 1 && days.isEmpty() && rule.byMonthDay.isEmpty() -> RepeatOption.Yearly
                else -> RepeatOption.Custom
            }
            return option to rrule.takeIf { option == RepeatOption.Custom }
        }

        private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

        fun draftOf(e: Event): EventDraft {
            val (repeat, custom) = repeatOf(e.rrule, e.start.date)
            val lastDay = if (e.allDay) e.end.date.plusDays(-1).let { maxOf(it, e.start.date) } else e.end.date
            return EventDraft(
                title = e.title,
                allDay = e.allDay,
                startDate = e.start.date,
                startTime = e.start.time,
                endDate = lastDay,
                endTime = e.end.time,
                repeat = repeat,
                customRule = custom,
                participants = e.participantIds.toSet(),
                dropOffBy = e.dropOff?.personId,
                dropOffTime = e.dropOff?.time,
                pickUpBy = e.pickUp?.personId,
                pickUpTime = e.pickUp?.time,
                location = e.location,
                tags = e.tags.toSet(),
                packing = e.packingItems,
                reminder = e.reminders.firstOrNull(),
                note = e.note,
                sharedWithCaregivers = e.sharedWithCaregivers,
            )
        }
    }
}
