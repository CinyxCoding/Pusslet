package se.pusslet.core.tasks

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.ChoreRotation
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Role
import se.pusslet.core.model.TaskKind
import se.pusslet.core.recurrence.RRule
import se.pusslet.core.recurrence.RecurrenceExpander
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.minusDays
import se.pusslet.core.time.plusDays

/** One concrete thing to do on a date (recurring tasks expanded, rotations resolved). */
data class TaskOccurrence(
    val task: FamilyTask,
    val date: LocalDate?,
    val assigneeId: String?,
    val done: Boolean,
) {
    val key: String get() = date?.toString() ?: "once"
}

object TaskScheduler {
    /**
     * How far back a missed occurrence of a recurring task still shows as overdue. Only the latest one counts: a newer
     * occurrence (today's reading, next Saturday's vacuuming) replaces a missed one.
     */
    const val OVERDUE_LOOKBACK_DAYS = 7

    /** One-off tasks older than this are not looked at any more (as before). */
    private const val ONE_OFF_LOOKBACK_DAYS = 365

    /** How far ahead [current] looks for the next occurrence of a recurring task: first a month, then a year. */
    private const val NEXT_SHORT_DAYS = 35
    private const val NEXT_LONG_DAYS = 400

    /**
     * Who does a task in the week of [date]. For a rotating chore: a "Byt vecka" override for that week, else the rotation
     * by ISO week ([ChoreRotation]); otherwise the task's own assignee.
     */
    fun assigneeFor(task: FamilyTask, date: LocalDate): String? {
        val r = task.rotation ?: return task.assigneeId
        if (r.memberIds.isEmpty()) return task.assigneeId
        val week = date.isoWeek
        return task.turnOverrides[week.key] ?: rotationTurn(r, week)
    }

    /** The rotation's own turn in [week], without "Byt vecka": memberIds[(weeksSinceAnchor / everyWeeks) % size]. */
    fun rotationTurn(r: ChoreRotation, week: IsoWeek): String? {
        if (r.memberIds.isEmpty()) return null
        val weeks = ((week.monday.toEpochDays() - r.anchorWeek.monday.toEpochDays()) / DAYS_PER_WEEK).toInt()
        val period = IntMath.floorDiv(weeks, r.everyWeeks.coerceAtLeast(1))
        return r.memberIds[IntMath.floorMod(period, r.memberIds.size)]
    }

    /** True for a chore that takes turns between at least one member. */
    fun isRotating(task: FamilyTask): Boolean = task.rotation?.memberIds?.isNotEmpty() == true

    /** "Vecka 43: Sara, vecka 44: Johan …": whose turn it is in [count] weeks from [from] ("Byt vecka" included). */
    fun turns(task: FamilyTask, from: IsoWeek, count: Int): List<Pair<IsoWeek, String?>> =
        (0 until count).map { offset -> from.plusWeeks(offset).let { it to assigneeFor(task, it.monday) } }

    /**
     * "Byt vecka": the turns of [week] and the week after change places. Overrides that only repeat the rotation are
     * dropped, so swapping twice leaves the task as it was. Nothing changes when both weeks are the same person's.
     */
    fun swapWeeks(task: FamilyTask, week: IsoWeek): FamilyTask {
        val r = task.rotation ?: return task
        if (r.memberIds.isEmpty()) return task
        val next = week.next()
        val first = assigneeFor(task, week.monday)
        val second = assigneeFor(task, next.monday)
        if (first == null || second == null || first == second) return task
        val overrides = task.turnOverrides + mapOf(week.key to second, next.key to first)
        return task.copy(turnOverrides = overrides.filter { (key, member) -> IsoWeek.parse(key)?.let { rotationTurn(r, it) } != member })
    }

    /**
     * Occurrences in [from]..[to]. A non-recurring task appears once (on its due date, or undated); a rotating one-off chore
     * is done by whoever has the turn in its due week, or in the week of [today] when it has no date.
     */
    fun occurrences(task: FamilyTask, from: LocalDate, to: LocalDate, today: LocalDate? = null): List<TaskOccurrence> {
        val rule = task.rrule?.let { RRule.parse(it) }
        if (rule == null) {
            if (task.due != null && (task.due < from.minusDays(ONE_OFF_LOOKBACK_DAYS) || task.due > to)) return emptyList()
            return listOf(TaskOccurrence(task, task.due, oneOffAssignee(task, today), task.done))
        }
        // Without a due date the series starts on the rotation's first Monday (or the window). That start is only a real
        // occurrence when the rule matches it: "every Saturday" must not also fall on that Monday.
        val derived = task.due == null
        val anchor = task.due ?: task.rotation?.anchorWeek?.monday ?: from
        val start = LocalDateTime(anchor, LocalTime(0, 0))
        return RecurrenceExpander.expand(rule, start, from, to)
            .map { it.date }
            .filter { d -> !derived || d != anchor || matches(rule, d) }
            .map { d -> TaskOccurrence(task, d, assigneeFor(task, d), task.completions.containsKey(d.toString())) }
    }

    /**
     * The open occurrences of one task up to [horizon]: a one-off that is not done (overdue and undated included); for a
     * recurring task the latest occurrence of the last [OVERDUE_LOOKBACK_DAYS] days when it is not done (today's, or a
     * missed one that then shows as overdue), and the ones after today up to [horizon].
     */
    fun open(task: FamilyTask, today: LocalDate, horizon: LocalDate): List<TaskOccurrence> {
        if (!isRecurring(task)) return occurrences(task, today, horizon, today).filter { !it.done }
        val latest = occurrences(task, today.minusDays(OVERDUE_LOOKBACK_DAYS), today, today).lastOrNull()?.takeIf { !it.done }
        val ahead = if (horizon > today) occurrences(task, today.plusDays(1), horizon, today).filter { !it.done } else emptyList()
        return listOfNotNull(latest) + ahead
    }

    /**
     * The one occurrence to show for a task in the task list: a one-off that is not done, or for a recurring task the latest
     * open one ([open]) and otherwise the next one that is not done. Null when there is nothing left to do.
     */
    fun current(task: FamilyTask, today: LocalDate): TaskOccurrence? {
        if (!isRecurring(task)) return if (task.done) null else TaskOccurrence(task, task.due, oneOffAssignee(task, today), done = false)
        open(task, today, today).firstOrNull()?.let { return it }
        return next(task, today, NEXT_SHORT_DAYS) ?: next(task, today, NEXT_LONG_DAYS)
    }

    private fun next(task: FamilyTask, today: LocalDate, days: Int): TaskOccurrence? =
        occurrences(task, today.plusDays(1), today.plusDays(days), today).firstOrNull { !it.done }

    /**
     * Open things for [memberId]: due by [horizon] (overdue included) or undated. Without a [role] only the ones that are
     * the member's (Idag's "Att göra", BRIEF §4.1). With a role, tasks nobody has are included too: for a parent all of
     * them, for a caregiver only the ones shared with caregivers.
     */
    fun openFor(
        memberId: String,
        tasks: List<FamilyTask>,
        today: LocalDate,
        horizon: LocalDate,
        role: Role? = null,
    ): List<TaskOccurrence> = tasks.flatMap { open(it, today, horizon) }
        .filter { o -> o.assigneeId == memberId || (o.assigneeId == null && seesUnassigned(role, o.task)) }
        .sortedWith(compareBy({ it.date == null }, { it.date }, { it.task.kind != TaskKind.Task }))

    /** Parents see tasks nobody has; caregivers only the shared ones. */
    fun seesUnassigned(role: Role?, task: FamilyTask): Boolean = when (role) {
        null -> false
        Role.Parent -> true
        else -> task.sharedWithCaregivers
    }

    fun isOverdue(o: TaskOccurrence, today: LocalDate): Boolean = !o.done && o.date != null && o.date < today

    /** A task with a valid RRULE; one with a rule that cannot be read is treated as a one-off (as before). */
    fun isRecurring(task: FamilyTask): Boolean = task.rrule?.let { RRule.parse(it) } != null

    private fun oneOffAssignee(task: FamilyTask, today: LocalDate?): String? {
        if (!isRotating(task)) return task.assigneeId
        val date = task.due ?: today ?: return task.assigneeId
        return assigneeFor(task, date)
    }

    /** Whether [date] fits the rule's BYDAY, BYMONTHDAY and BYMONTH (the parts a derived start can miss). */
    private fun matches(rule: RRule, date: LocalDate): Boolean {
        val days = rule.byDay.map { it.day }
        if (days.isNotEmpty() && date.dayOfWeek !in days) return false
        val monthDays = rule.byMonthDay.filter { it > 0 }
        if (monthDays.isNotEmpty() && date.day !in monthDays) return false
        if (rule.byMonth.isNotEmpty() && date.month.ordinal + 1 !in rule.byMonth) return false
        return true
    }

    private const val DAYS_PER_WEEK = 7
}

private object IntMath {
    fun floorDiv(a: Int, b: Int): Int = kotlin.math.floor(a.toDouble() / b).toInt()

    fun floorMod(a: Int, b: Int): Int = ((a % b) + b) % b
}
