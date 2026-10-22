package se.pusslet.core.sick

import kotlinx.datetime.LocalDate
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.SickDay
import se.pusslet.core.time.plusDays

/**
 * What a sick child changes in the family's day (ADR-047), as pure rules shared by Idag, the reminders, the widgets, the
 * helper view and Kalender.
 *
 * The events themselves are never changed: a sick child is marked [EventOccurrence.absent] on that day's occurrences, so
 * their drop-off and pick-up disappear wherever hand-offs are worked out from [EventOccurrence.attending], and an
 * occurrence that only the sick children would have gone to is left out as cancelled. Whatever is written back to an event
 * (taking a hand-off, say) still comes from the event, so nobody is removed from it for good.
 */
object SickDays {
    /** The day's occurrences with sick children marked absent, and the ones left out because nobody goes. */
    data class Applied(val occurrences: List<EventOccurrence>, val cancelled: List<EventOccurrence>)

    /**
     * Marks the [sick] children absent on single-day occurrences of their sick day. One that had children and has none
     * left goes to [Applied.cancelled]; events of adults only, and events over several days, are not touched.
     */
    fun apply(occurrences: List<EventOccurrence>, sick: List<SickDay>, childIds: Set<String>): Applied {
        if (sick.isEmpty()) return Applied(occurrences, emptyList())
        val byDate = sick.groupBy({ it.date }, { it.childId }).mapValues { it.value.toSet() }
        val kept = ArrayList<EventOccurrence>(occurrences.size)
        val cancelled = ArrayList<EventOccurrence>()
        for (o in occurrences) {
            val home = byDate[o.start.date]
            val children = o.event.participantIds.filter { it in childIds }
            val absent = home?.let { h -> children.filter { it in h } }.orEmpty()
            when {
                absent.isEmpty() || o.lastDate != o.start.date -> kept += o
                absent.size == children.size -> cancelled += o
                else -> kept += o.copy(absent = o.absent + absent)
            }
        }
        return Applied(kept, cancelled)
    }

    fun on(sick: List<SickDay>, date: LocalDate): List<SickDay> = sick.filter { it.date == date }

    /**
     * "Ella var sjuk igår. Fortfarande sjuk?": a child's latest sick day in the [LOOK_BACK_DAYS] days before [today]
     * (a weekend in between), unless it was marked well again or today is already reported.
     */
    fun stillSick(sick: List<SickDay>, today: LocalDate): List<SickDay> {
        val todays = on(sick, today).map { it.childId }.toSet()
        return sick.filter { it.date < today && it.date >= today.plusDays(-LOOK_BACK_DAYS) && it.childId !in todays }
            .groupBy { it.childId }
            .mapNotNull { (_, days) -> days.maxBy { it.date }.takeUnless { it.wellAgain } }
            .sortedBy { it.childId }
    }

    /** A child's VAB days that [memberId] still has to report to Försäkringskassan, once the child is well. */
    data class VabToReport(val childId: String, val days: List<SickDay>) {
        val first: LocalDate get() = days.first().date
        val last: LocalDate get() = days.last().date
        val total: Double get() = days.sumOf { it.vabPart.share }
    }

    /**
     * The VAB days [memberId] took and has not reported, per child, from periods that are over (nothing reported for that
     * child [today]) and within Försäkringskassan's [REPORT_WITHIN_DAYS] days.
     */
    fun vabToReport(sick: List<SickDay>, memberId: String?, today: LocalDate): List<VabToReport> {
        if (memberId == null) return emptyList()
        val sickToday = on(sick, today).map { it.childId }.toSet()
        return sick.filter {
            it.vab && it.homeWith == memberId && !it.vabReported && it.date < today &&
                it.date >= today.plusDays(-REPORT_WITHIN_DAYS) && it.childId !in sickToday
        }
            .groupBy { it.childId }
            .map { (child, days) -> VabToReport(child, days.sortedBy { it.date }) }
            .sortedBy { it.first }
    }

    /** VAB per member in [year], in days (half days count as 0.5): "VAB i år: Sara 4,5 · Johan 2". */
    fun vabInYear(sick: List<SickDay>, year: Int): Map<String, Double> = sick
        .filter { it.vab && it.date.year == year && it.homeWith != null }
        .groupBy { it.homeWith!! }
        .mapValues { (_, days) -> days.sumOf { it.vabPart.share } }

    /** Sick days are kept for this year's and last year's VAB, then removed (PRIVACY.md). */
    fun expired(sick: List<SickDay>, today: LocalDate): List<SickDay> = sick.filter { it.date.year < today.year - 1 }

    const val LOOK_BACK_DAYS = 3

    /** Försäkringskassan wants VAB reported within 90 days. */
    const val REPORT_WITHIN_DAYS = 90
}
