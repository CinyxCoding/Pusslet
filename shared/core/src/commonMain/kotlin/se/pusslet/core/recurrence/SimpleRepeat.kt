package se.pusslet.core.recurrence

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/** The everyday choices an editor offers; anything richer is kept as [Custom] with the original RRULE. */
enum class RepeatOption { None, Daily, Weekdays, Weekly, Biweekly, Monthly, Yearly, Custom }

/** Maps between [RepeatOption] (anchored on a start date) and RRULE text. Shared by the event and task editors. */
object SimpleRepeat {
    private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    fun ruleFor(option: RepeatOption, start: LocalDate, customRule: String? = null): String? {
        val day = start.dayOfWeek
        return when (option) {
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
            RepeatOption.Monthly -> RRule.monthlyOnDay(start.day).format()
            RepeatOption.Yearly -> RRule.yearly().format()
            RepeatOption.Custom -> customRule
        }
    }

    /** A stored RRULE as an editor choice; the second value is the rule to keep when it is [RepeatOption.Custom]. */
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
}
