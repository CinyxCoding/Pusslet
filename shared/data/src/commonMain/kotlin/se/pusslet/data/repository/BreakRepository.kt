package se.pusslet.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.breaks.SchoolBreak
import se.pusslet.core.model.BreakDay
import se.pusslet.core.model.BreakPlan
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.StaySchedule
import se.pusslet.core.time.plusDays
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sync.Collections
import se.pusslet.data.sync.DocumentStore

/** "Lov och vistelsetider" (ADR-051): the plan for each school break, and each child's hours at preschool or fritids. */
class BreakRepository(
    private val docs: DocumentStore,
    private val calendar: CalendarRepository,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
) {
    val plans: Flow<List<BreakPlan>> = docs.observeAll(Collections.BreakPlans, BreakPlan.serializer())
    val schedules: Flow<List<StaySchedule>> = docs.observeAll(Collections.StaySchedules, StaySchedule.serializer())

    /** "Lägg till lov": an all-day event tagged as a break, for [childIds] (empty = the whole family). */
    suspend fun addBreak(title: String, start: LocalDate, end: LocalDate, childIds: List<String>): Event {
        val event = Event(
            id = ids.newId(),
            title = title.trim(),
            start = LocalDateTime(start, LocalTime(0, 0)),
            end = LocalDateTime(maxOf(start, end).plusDays(1), LocalTime(0, 0)),
            allDay = true,
            participantIds = childIds,
            tags = listOf(EventTags.HOLIDAY_BREAK),
            createdBy = sessions.session.value.memberId,
        )
        calendar.saveEvent(event)
        return event
    }

    /** Who looks after the children on [date]; null clears it. Merged per day, so two parents can plan at once. */
    suspend fun setDay(b: SchoolBreak, date: LocalDate, day: BreakDay?) = change(b) { p ->
        p.copy(days = if (day == null) p.days - date.toString() else p.days + (date.toString() to day))
    }

    suspend fun setDeadline(b: SchoolBreak, deadline: LocalDate?) = change(b) { it.copy(deadline = deadline) }

    suspend fun setReported(b: SchoolBreak, reported: Boolean) = change(b) { it.copy(reported = reported) }

    suspend fun saveSchedule(schedule: StaySchedule) =
        docs.put(Collections.StaySchedules, schedule.childId, schedule, StaySchedule.serializer())

    private suspend fun change(b: SchoolBreak, transform: (BreakPlan) -> BreakPlan) {
        val current = docs.get(Collections.BreakPlans, b.planId, BreakPlan.serializer()) ?: b.plan
        docs.put(Collections.BreakPlans, b.planId, transform(current).copy(title = b.title), BreakPlan.serializer())
    }
}
