package se.pusslet.core.tasks

import kotlinx.datetime.LocalDate
import se.pusslet.core.recurrence.Frequency
import se.pusslet.core.recurrence.RRule

/** The repeat choices of the task editor. [Custom] keeps a rule that does not fit the others ("mån och tors") as it is. */
enum class TaskRepeat { None, Daily, Weekly, Biweekly, Monthly, Custom }

/** Between the editor's simple choices and an RRULE, like the event editor's: the series starts on the due date. */
object TaskRepeats {
    fun ruleFor(repeat: TaskRepeat, due: LocalDate, custom: String? = null): String? = when (repeat) {
        TaskRepeat.None -> null
        TaskRepeat.Daily -> RRule.daily().format()
        TaskRepeat.Weekly -> RRule.weekly(due.dayOfWeek).format()
        TaskRepeat.Biweekly -> RRule.weekly(due.dayOfWeek, interval = 2).format()
        TaskRepeat.Monthly -> RRule.monthlyOnDay(due.day).format()
        TaskRepeat.Custom -> custom
    }

    /** Maps a stored rule to a choice; anything richer is [TaskRepeat.Custom] and comes back as the second value. */
    fun repeatOf(rrule: String?, due: LocalDate?): Pair<TaskRepeat, String?> {
        if (rrule.isNullOrBlank()) return TaskRepeat.None to null
        val rule = RRule.parse(rrule) ?: return TaskRepeat.Custom to rrule
        val plain = rule.count == null && rule.until == null && rule.byMonth.isEmpty() && rule.bySetPos.isEmpty() &&
            rule.unsupported.isEmpty()
        val days = rule.byDay.map { it.day }
        val onDueDay = days.isEmpty() || (due != null && days == listOf(due.dayOfWeek))
        val onDueDate = rule.byDay.isEmpty() && (rule.byMonthDay.isEmpty() || (due != null && rule.byMonthDay == listOf(due.day)))
        val option = when {
            !plain -> TaskRepeat.Custom
            rule.freq == Frequency.DAILY && rule.interval == 1 && days.isEmpty() -> TaskRepeat.Daily
            due == null -> TaskRepeat.Custom
            rule.freq == Frequency.WEEKLY && rule.interval == 1 && onDueDay -> TaskRepeat.Weekly
            rule.freq == Frequency.WEEKLY && rule.interval == 2 && onDueDay -> TaskRepeat.Biweekly
            rule.freq == Frequency.MONTHLY && rule.interval == 1 && onDueDate -> TaskRepeat.Monthly
            else -> TaskRepeat.Custom
        }
        return option to rrule.takeIf { option == TaskRepeat.Custom }
    }
}
