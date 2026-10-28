package se.pusslet.core.notify

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.calendar.EffectiveHandoffs
import se.pusslet.core.medicine.MedicineRules
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.Role
import se.pusslet.core.sick.SickDays
import se.pusslet.core.tasks.TaskScheduler
import se.pusslet.core.time.TimeWindow
import se.pusslet.core.time.datesUntil
import se.pusslet.core.time.minusDays
import se.pusslet.core.time.plusDays
import se.pusslet.core.today.ChildPacking
import se.pusslet.core.today.HandoffLine
import se.pusslet.core.today.Pickups
import se.pusslet.core.today.TimelineItem
import se.pusslet.core.today.TodayInput
import se.pusslet.core.today.TodayPlanner
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The user's quiet hours (`Prefs.QuietHours` and `Prefs.QuietHoursEnabled`). */
data class QuietSettings(val window: TimeWindow, val enabled: Boolean)

/**
 * What the planner needs. [base] is the same data Idag is built from (`TodayInputSource`): occurrences from today on,
 * tasks, hand-off requests, people, children, packing; its `today` and `now` are replaced per notification.
 */
data class ReminderInput(
    val base: TodayInput,
    val now: Instant,
    /** The viewer's role; null (no family yet) plans nothing that needs one. */
    val role: Role?,
    /** Morgonkollen's time when it is switched on (`Prefs.Morning`), else null. */
    val morningAt: LocalTime? = null,
    val quiet: QuietSettings? = null,
    val horizon: Duration = ReminderPlanner.HORIZON,
    /** "Varna om en hämtning inte bockas av" (`Prefs.PickupWarnings`, ADR-048). */
    val pickupWarnings: Boolean = true,
)

/**
 * Local notifications for the next [ReminderInput.horizon], as data (BRIEF §4.10). Everything is planned in the
 * family's zone ([TodayInput.zone]) from local times, so a reminder at 07:30 stays at 07:30 across daylight saving.
 *
 * Rules:
 * - **Event reminders** ([NotificationKind.Reminder]) from `Event.reminders`, for the family's own events (subscriptions
 *   carry none, and a device calendar reminds by itself) that concern me: I take part, I have a hand-off on it, or – for a
 *   parent – no adult takes part (a child's event or one for the whole family). Another adult's own event stays quiet.
 *   Whole days go back by calendar day, so "dagen innan" is the same clock time the day before, also across DST.
 *   An all-day event gets one reminder at [ALL_DAY_REMINDER_TIME] the evening before, whatever was chosen.
 * - **Hand-off soon** ([NotificationKind.HandoffSoon]) [HANDOFF_LEAD] before my own drop-off or pick-up of a child, with
 *   accepted answers counted ([EffectiveHandoffs]) so the caregiver who said yes is the one reminded.
 * - **To-dos** ([NotificationKind.Reminder]) on their day at their time, or [TASK_DEFAULT_TIME] without one: mine, and for a
 *   parent also those nobody has. A recurring to-do once per occurrence; done ones and undated ones never.
 * - **Morgonkollen** ([NotificationKind.Morning]) at the chosen time when it is on: the greeting, the day's hand-offs and
 *   what to bring, as Idag shows them at that time ([TodayPlanner]).
 * - **Kvällskollen** ([NotificationKind.Evening]) for parents at [EVENING_TIME] when tomorrow has something left to pack
 *   ([TodayPlanner]'s `TomorrowPrep`).
 *
 * Times that have passed are skipped. [schedule] then applies [QuietHours] and [Bundling].
 */
object ReminderPlanner {
    val HORIZON: Duration = 48.hours

    /** "Du hämtar Ella 15:45" comes this long before. A constant for now; it can become a preference later. */
    val HANDOFF_LEAD: Duration = 30.minutes

    /** A to-do without a time is reminded of in the morning of its day. */
    val TASK_DEFAULT_TIME: LocalTime = LocalTime(9, 0)

    /** Kvällskollen: after dinner, while there is still time to pack. */
    val EVENING_TIME: LocalTime = LocalTime(19, 0)

    /** "Anmäl frånvaro": before school starts, after most have reported a sick child. */
    val ABSENCE_TIME: LocalTime = LocalTime(7, 45)

    /** All-day events are reminded of the evening before, never at midnight. */
    val ALL_DAY_REMINDER_TIME: LocalTime = LocalTime(18, 0)

    /**
     * How far back [schedule] plans before it drops what has passed. A quiet window is shorter than a day, so a reminder
     * held back to its end is still found after its own time, and a summary that was already shown keeps its later
     * members from coming again on their own.
     */
    val LOOKBACK: Duration = 24.hours

    private const val MINUTES_PER_DAY = 24 * 60

    /**
     * [plan], then quiet hours (deferred or dropped) and bundling: what the Android side should schedule. Both are applied
     * from [LOOKBACK] before now and passed times are dropped last, so planning again at any moment gives the same answer.
     */
    fun schedule(input: ReminderInput): List<PlannedNotification> {
        val until = input.now + input.horizon
        val planned = candidates(input, from = input.now - LOOKBACK, until = until)
        val quiet = input.quiet?.let { QuietHours.apply(planned, it, input.base.zone) } ?: planned
        return Bundling.bundle(quiet).filter { it.at > input.now && it.at <= until }
    }

    /** Every notification in (now, now + horizon], sorted by time and key; keys are unique. */
    fun plan(input: ReminderInput): List<PlannedNotification> = candidates(input, from = input.now, until = input.now + input.horizon)

    private fun candidates(input: ReminderInput, from: Instant, until: Instant): List<PlannedNotification> {
        val zone = input.base.zone
        val ctx = Context(
            input = input,
            zone = zone,
            people = input.base.people.associateBy { it.id },
            occurrences = EffectiveHandoffs.apply(input.base.occurrences, input.base.requests),
            days = from.toLocalDateTime(zone).date.datesUntil(until.toLocalDateTime(zone).date),
        )
        val all = eventReminders(ctx) + handoffs(ctx) + tasks(ctx) + mornings(ctx) + evenings(ctx) + sickReminders(ctx) +
            pickupChecks(ctx) + medicineReminders(ctx)
        return all.filter { it.at > from && it.at <= until }
            .distinctBy { it.key }
            .sortedWith(compareBy({ it.at }, { it.key }))
    }

    private class Context(
        val input: ReminderInput,
        val zone: TimeZone,
        val people: Map<String, FamilyPerson>,
        val occurrences: List<EventOccurrence>,
        /** The local dates the planned span touches, the first one first. */
        val days: List<LocalDate>,
    ) {
        val me: String? get() = input.base.me

        fun childrenOf(o: EventOccurrence): List<FamilyPerson> = o.attending.mapNotNull { people[it] }.filter { it.isChild }
    }

    private fun eventReminders(ctx: Context): List<PlannedNotification> = ctx.occurrences
        .filter { o -> o.source == EventSource.Family && o.event.reminders.any { it >= 0 } && concernsMe(o, ctx) }
        .flatMap { o ->
            val children = ctx.childrenOf(o).map { it.name }
            if (o.allDay) {
                val at = LocalDateTime(o.start.date.minusDays(1), ALL_DAY_REMINDER_TIME).toInstant(ctx.zone)
                listOf(PlannedNotification("event:${o.key}:allday", at, NotificationKind.Reminder, eventPayload(o, 0, children)))
            } else {
                o.event.reminders.filter { it >= 0 }.distinct().map { minutes ->
                    PlannedNotification(
                        key = "event:${o.key}:$minutes",
                        at = reminderAt(o.start, minutes, ctx.zone),
                        kind = NotificationKind.Reminder,
                        payload = eventPayload(o, minutes, children),
                        userTimed = true,
                    )
                }
            }
        }

    /** [minutes] before [start]: whole days by the calendar (same clock time), the rest by the clock. */
    internal fun reminderAt(start: LocalDateTime, minutes: Int, zone: TimeZone): Instant {
        val days = minutes / MINUTES_PER_DAY
        val rest = minutes % MINUTES_PER_DAY
        return LocalDateTime(start.date.minusDays(days), start.time).toInstant(zone) - rest.minutes
    }

    private fun eventPayload(o: EventOccurrence, minutes: Int, children: List<String>) = NotificationPayload.EventReminder(
        eventId = o.event.id,
        occurrenceDate = o.occurrenceDate,
        title = o.event.title,
        start = o.start,
        end = o.end,
        allDay = o.allDay,
        minutesBefore = minutes,
        location = o.event.location,
        children = children,
    )

    /** I take part, I have a hand-off on it, or – for a parent – no adult takes part. */
    private fun concernsMe(o: EventOccurrence, ctx: Context): Boolean {
        val me = ctx.me ?: return false
        val ids = o.attending
        if (me in ids || o.event.dropOff?.personId == me || o.event.pickUp?.personId == me) return true
        return ctx.input.role == Role.Parent && ids.none { ctx.people[it]?.isChild == false }
    }

    private fun handoffs(ctx: Context): List<PlannedNotification> {
        val me = ctx.me ?: return emptyList()
        return ctx.occurrences.flatMap { o ->
            val children = ctx.childrenOf(o)
            if (children.isEmpty()) return@flatMap emptyList()
            HandoffKind.entries.mapNotNull { kind ->
                val handoff = if (kind == HandoffKind.PickUp) o.event.pickUp else o.event.dropOff
                if (handoff?.personId != me) return@mapNotNull null
                val time = (if (kind == HandoffKind.PickUp) o.pickUpTime() else o.dropOffTime()) ?: return@mapNotNull null
                val at = LocalDateTime(o.handoffDate(kind), time).toInstant(ctx.zone) - HANDOFF_LEAD
                val payload = NotificationPayload.Handoff(
                    eventId = o.event.id,
                    occurrenceDate = o.occurrenceDate,
                    kind = kind,
                    time = time,
                    children = children.map { it.name },
                    place = placeOf(o, children, ctx),
                )
                PlannedNotification("handoff:${o.key}:${kind.name}", at, NotificationKind.HandoffSoon, payload, userTimed = true)
            }
        }
    }

    /** The event's place; for a school block without one the child's school ("Björkängsskolan"); else the title. */
    private fun placeOf(o: EventOccurrence, children: List<FamilyPerson>, ctx: Context): String {
        if (o.event.location.isNotBlank()) return o.event.location.trim()
        if (TodayPlanner.isRoutine(o)) {
            val ids = children.map { it.id }.toSet()
            ctx.input.base.children.firstOrNull { it.id in ids && it.school != null }?.school?.name?.let { return it }
        }
        return o.event.title
    }

    private fun tasks(ctx: Context): List<PlannedNotification> {
        val me = ctx.me ?: return emptyList()
        val parent = ctx.input.role == Role.Parent
        val first = ctx.days.first()
        val last = ctx.days.last()
        return ctx.input.base.tasks.flatMap { task ->
            TaskScheduler.occurrences(task, first, last, today = ctx.input.now.toLocalDateTime(ctx.zone).date)
                .filter { o -> o.date != null && o.date >= first && !o.done && (o.assigneeId == me || (parent && o.assigneeId == null)) }
                .mapNotNull { o ->
                    val date = o.date ?: return@mapNotNull null
                    val at = LocalDateTime(date, task.dueTime ?: TASK_DEFAULT_TIME).toInstant(ctx.zone)
                    val payload = NotificationPayload.Task(task.id, date, task.title, task.dueTime)
                    PlannedNotification("task:${task.id}:$date", at, NotificationKind.Reminder, payload, userTimed = task.dueTime != null)
                }
        }
    }

    /**
     * Sick days (ADR-047): "Anmäl frånvaro för Ella" at [ABSENCE_TIME] on the day until it is ticked, to the one at home (or
     * who reported it while nobody is chosen), and "Anmäl VAB för Ella" at [EVENING_TIME] on the first day she is well again,
     * to the parent who took VAB and has not reported it.
     */
    private fun sickReminders(ctx: Context): List<PlannedNotification> {
        val me = ctx.me ?: return emptyList()
        val sick = ctx.input.base.sickDays
        if (sick.isEmpty()) return emptyList()
        val schools = ctx.input.base.children.associate { it.id to it.school?.name }
        val absence = ctx.days.flatMap { day ->
            SickDays.on(sick, day).filter { !it.absenceReported && (it.homeWith ?: it.reportedBy) == me }.mapNotNull { s ->
                val child = ctx.people[s.childId] ?: return@mapNotNull null
                val at = LocalDateTime(day, ABSENCE_TIME).toInstant(ctx.zone)
                PlannedNotification(
                    "sick:absence:${s.id}",
                    at,
                    NotificationKind.Reminder,
                    NotificationPayload.SickAbsence(child.name, schools[s.childId]),
                )
            }
        }
        val vab = ctx.days.flatMap { day ->
            SickDays.vabToReport(sick, me, day).filter { it.last == day.plusDays(-1) }.mapNotNull { r ->
                val child = ctx.people[r.childId] ?: return@mapNotNull null
                val at = LocalDateTime(day, EVENING_TIME).toInstant(ctx.zone)
                PlannedNotification(
                    "sick:vab:${r.childId}:${r.first}",
                    at,
                    NotificationKind.Reminder,
                    NotificationPayload.VabReport(child.name, r.total),
                )
            }
        }
        return absence + vab
    }

    /**
     * "Dags för Kåvepenin till Ella · 5 ml" at each planned dose of a course (ADR-050), to the parents, until someone has
     * logged it. Given doses are planned away, so the other parent is not reminded of a dose already given.
     */
    private fun medicineReminders(ctx: Context): List<PlannedNotification> {
        if (ctx.input.role != Role.Parent) return emptyList()
        val base = ctx.input.base
        if (base.medicineCourses.isEmpty()) return emptyList()
        return ctx.days.flatMap { day ->
            MedicineRules.slotsOn(base.medicineCourses, day, base.medicineLog).filter { !it.done }.mapNotNull { slot ->
                val child = ctx.people[slot.course.childId] ?: return@mapNotNull null
                PlannedNotification(
                    "medicine:${slot.course.id}:${slot.at}",
                    slot.at.toInstant(ctx.zone),
                    NotificationKind.Reminder,
                    NotificationPayload.MedicineDose(child.name, slot.course.medicine, slot.course.dose),
                    userTimed = true,
                )
            }
        }
    }

    /**
     * "Är Ella hämtad?" [Pickups.GRACE] after a pick-up that nobody has ticked off (ADR-048), to the one who was to do it and
     * to the parents. Planned again when a receipt arrives, so a ticked-off pick-up never warns.
     */
    private fun pickupChecks(ctx: Context): List<PlannedNotification> {
        val me = ctx.me ?: return emptyList()
        if (!ctx.input.pickupWarnings) return emptyList()
        val parent = ctx.input.role == Role.Parent
        return ctx.days.flatMap { day ->
            Pickups.on(day, ctx.occurrences, ctx.people)
                .filter { p -> p.personId != null && (p.personId == me || parent) }
                .filter { p -> Pickups.receiptFor(ctx.input.base.receipts, p.occurrence) == null }
                .map { p ->
                    val at = LocalDateTime(day, p.time).toInstant(ctx.zone) + Pickups.GRACE
                    val payload = NotificationPayload.PickupUnconfirmed(
                        eventId = p.occurrence.event.id,
                        occurrenceDate = p.occurrence.occurrenceDate,
                        children = p.children.map { it.name },
                        by = p.personId?.let { ctx.people[it]?.name },
                        isMe = p.personId == me,
                        time = p.time,
                    )
                    PlannedNotification("pickup-check:${p.key}", at, NotificationKind.Pickups, payload, userTimed = true)
                }
        }
    }

    private fun mornings(ctx: Context): List<PlannedNotification> {
        val time = ctx.input.morningAt ?: return emptyList()
        if (ctx.me == null) return emptyList()
        return ctx.days.mapNotNull { day ->
            val at = LocalDateTime(day, time).toInstant(ctx.zone)
            val plan = TodayPlanner.build(dayInput(ctx, day, time))
            val first = plan.timeline.filterIsInstance<TimelineItem.EventItem>()
                .firstOrNull { item -> item.time.let { it == null || it >= time } }
                ?.let { BriefEvent(it.occurrence.event.title, it.time) }
            val payload = NotificationPayload.Morning(
                date = day,
                greeting = TodayPlanner.greeting(time),
                handoffs = plan.hero.handoffs.map(::brief),
                packing = unpacked(plan.hero.packing.children),
                first = first,
            )
            PlannedNotification("morning:$day", at, NotificationKind.Morning, payload, userTimed = true)
        }
    }

    private fun evenings(ctx: Context): List<PlannedNotification> {
        if (ctx.me == null || ctx.input.role != Role.Parent) return emptyList()
        return ctx.days.mapNotNull { day ->
            val at = LocalDateTime(day, EVENING_TIME).toInstant(ctx.zone)
            // Kvällskollen must exist at this time, whatever hour Idag starts it from.
            val input = dayInput(ctx, day, EVENING_TIME).let { it.copy(tomorrowWeatherFrom = minOf(it.tomorrowWeatherFrom, EVENING_TIME)) }
            val prep = TodayPlanner.build(input).tomorrow ?: return@mapNotNull null
            val packing = unpacked(prep.packing.children).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            PlannedNotification("evening:$day", at, NotificationKind.Evening, NotificationPayload.Evening(prep.date, packing))
        }
    }

    /** Idag as it will look on [day] at [time]: no weather, no shopping (neither is in a notification). */
    private fun dayInput(ctx: Context, day: LocalDate, time: LocalTime): TodayInput = ctx.input.base.copy(
        today = day,
        now = time,
        occurrences = ctx.occurrences,
        forecast = null,
        shopping = null,
    )

    /** A Morgonkollen hand-off line as words to say; also the Idag widget's ([se.pusslet.core.widget.TodayGlances]). */
    internal fun brief(line: HandoffLine) = BriefHandoff(
        kind = line.kind,
        person = line.person?.name,
        isMe = line.isMe,
        stops = line.stops.map { BriefStop(it.child.name, it.time) },
    )

    /** What each child still has to bring, children with nothing left out. */
    internal fun unpacked(children: List<ChildPacking>): List<BriefPacking> = children.mapNotNull { cp ->
        val left = cp.items.filter { !it.checked }.map { it.text.trim() }.filter { it.isNotEmpty() }
        if (left.isEmpty()) null else BriefPacking(cp.child.name, left)
    }
}
