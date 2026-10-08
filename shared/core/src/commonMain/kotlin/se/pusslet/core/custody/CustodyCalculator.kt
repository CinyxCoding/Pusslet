package se.pusslet.core.custody

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.CustodyPatternType
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.Household
import se.pusslet.core.time.minusDays
import se.pusslet.core.time.plusDays

/** A continuous stretch of days in one household (used for the background bands in the calendar). */
data class CustodyPeriod(val start: LocalDate, val endInclusive: LocalDate, val household: Household)

/** The children move from one home to the other on [date] at [time]. */
data class Handover(
    val date: LocalDate,
    val time: LocalTime,
    val from: Household,
    val to: Household,
    val childIds: List<String>,
)

/** Pure calculations for växelvis boende. */
object CustodyCalculator {
    /** Household index per day of the repeating cycle, starting at the schedule's anchor date. */
    fun cycle(schedule: CustodySchedule): List<Int> = when (schedule.pattern) {
        CustodyPatternType.AlternatingWeeks -> List(7) { 0 } + List(7) { 1 }
        // 2-2-5-5: AA BB AAAAA BBBBB
        CustodyPatternType.TwoTwoFiveFive -> listOf(0, 0, 1, 1, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1)
        // 2-2-3: AA BB AAA / BB AA BBB
        CustodyPatternType.TwoTwoThree -> listOf(0, 0, 1, 1, 0, 0, 0, 1, 1, 0, 0, 1, 1, 1)
        CustodyPatternType.Custom -> schedule.customCycle.ifEmpty { listOf(0) }
    }

    fun householdIndexOn(schedule: CustodySchedule, date: LocalDate): Int? {
        if (schedule.households.isEmpty()) return null
        schedule.exceptions.lastOrNull { date in it.start..it.end }?.let { ex ->
            val idx = schedule.households.indexOfFirst { it.id == ex.householdId }
            if (idx >= 0) return idx
        }
        val c = cycle(schedule)
        val days = (date.toEpochDays() - schedule.anchorDate.toEpochDays()).toInt()
        val pos = ((days % c.size) + c.size) % c.size
        return c[pos].coerceIn(0, schedule.households.lastIndex)
    }

    fun householdOn(schedule: CustodySchedule, date: LocalDate): Household? =
        householdIndexOn(schedule, date)?.let { schedule.households[it] }

    fun periods(schedule: CustodySchedule, start: LocalDate, endInclusive: LocalDate): List<CustodyPeriod> {
        if (endInclusive < start || schedule.households.isEmpty()) return emptyList()
        val result = ArrayList<CustodyPeriod>()
        var periodStart = start
        var current = householdOn(schedule, start) ?: return emptyList()
        var d = start.plusDays(1)
        while (d <= endInclusive) {
            val h = householdOn(schedule, d) ?: break
            if (h.id != current.id) {
                result += CustodyPeriod(periodStart, d.minusDays(1), current)
                periodStart = d
                current = h
            }
            d = d.plusDays(1)
        }
        result += CustodyPeriod(periodStart, endInclusive, current)
        return result
    }

    /** The next handover on or after [from] (looking at most [horizonDays] ahead). */
    fun nextHandover(schedule: CustodySchedule, from: LocalDate, horizonDays: Int = 62): Handover? {
        var prev = householdOn(schedule, from.minusDays(1)) ?: return null
        var d = from
        repeat(horizonDays) {
            val h = householdOn(schedule, d) ?: return null
            if (h.id != prev.id) return Handover(d, schedule.handoverTime, prev, h, schedule.childIds)
            prev = h
            d = d.plusDays(1)
        }
        return null
    }

    fun isHandoverDay(schedule: CustodySchedule, date: LocalDate): Boolean {
        val today = householdOn(schedule, date) ?: return false
        val yesterday = householdOn(schedule, date.minusDays(1)) ?: return false
        return today.id != yesterday.id
    }

    /** Is the household that [memberId] belongs to having the children on [date]? ("min vecka / din vecka") */
    fun isWithMember(schedule: CustodySchedule, date: LocalDate, memberId: String): Boolean =
        householdOn(schedule, date)?.memberIds?.contains(memberId) == true
}
