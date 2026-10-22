package se.pusslet.feature.today

import kotlinx.datetime.LocalDate
import se.pusslet.core.model.Child
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.Role
import se.pusslet.core.model.SickDay
import se.pusslet.core.sick.SickDays
import se.pusslet.core.time.plusDays

/** "Ella är sjuk idag" on Idag (ADR-047): what is off, who stays home, VAB and the reports. */
data class SickCard(
    val day: SickDay,
    val child: FamilyPerson,
    /** Null = nobody chosen yet. */
    val homeWith: FamilyPerson?,
    /** What the sick day cancelled for this child (school, dance …), by start. */
    val cancelled: List<EventOccurrence>,
    /** "Frånvaro anmäld till Björkängsskolan"; null = "till skolan". */
    val school: String?,
    /** VAB is for a parent; another adult (morfar) just stays home. */
    val homeWithIsParent: Boolean,
    val tomorrow: Boolean,
)

/** "Är Ella fortfarande sjuk?" the morning after a sick day. */
data class StillSickCard(val day: SickDay, val child: FamilyPerson)

/** "Anmäl VAB för Ella: 2 dagar · 20–21 okt" for the parent who took it, once the child is well. */
data class VabReportCard(val report: SickDays.VabToReport, val child: FamilyPerson)

/** Everything about sick days that Idag shows. */
data class SickToday(
    val cards: List<SickCard> = emptyList(),
    val stillSick: List<StillSickCard> = emptyList(),
    val vabToReport: List<VabReportCard> = emptyList(),
    /** "VAB i år: Sara 4,5 · Johan 2", most first; only members with VAB. */
    val vabThisYear: List<Pair<FamilyPerson, Double>> = emptyList(),
    /** Who can stay home: me, the other parents, then other adults. */
    val adults: List<FamilyPerson> = emptyList(),
    /** Who can be reported sick. */
    val children: List<FamilyPerson> = emptyList(),
) {
    val isEmpty: Boolean get() = cards.isEmpty() && stillSick.isEmpty() && vabToReport.isEmpty()
}

object SickModel {
    fun build(
        today: LocalDate,
        me: String?,
        sickDays: List<SickDay>,
        cancelled: List<EventOccurrence>,
        people: Map<String, FamilyPerson>,
        children: List<Child>,
    ): SickToday {
        val tomorrow = today.plusDays(1)
        val childById = children.associateBy { it.id }
        val cards = (SickDays.on(sickDays, today) + SickDays.on(sickDays, tomorrow)).mapNotNull { day ->
            val child = people[day.childId] ?: return@mapNotNull null
            val home = day.homeWith?.let { people[it] }
            SickCard(
                day = day,
                child = child,
                homeWith = home,
                cancelled = cancelled.filter { it.start.date == day.date && day.childId in it.event.participantIds }.sortedBy { it.start },
                school = childById[day.childId]?.school?.name,
                homeWithIsParent = home?.role == Role.Parent,
                tomorrow = day.date == tomorrow,
            )
        }.sortedWith(compareBy({ it.day.date }, { it.child.name }))
        return SickToday(
            cards = cards,
            stillSick = SickDays.stillSick(sickDays, today).mapNotNull { d -> people[d.childId]?.let { StillSickCard(d, it) } },
            vabToReport = SickDays.vabToReport(sickDays, me, today).mapNotNull { r -> people[r.childId]?.let { VabReportCard(r, it) } },
            vabThisYear = SickDays.vabInYear(sickDays, today.year).mapNotNull { (id, days) -> people[id]?.let { it to days } }
                .sortedByDescending { it.second },
            // Me first, then the other parents, then other adults, in the family's order.
            adults = people.values.filter { !it.isChild }.sortedWith(compareBy({ it.id != me }, { it.role != Role.Parent })),
            children = children.mapNotNull { people[it.id] },
        )
    }

    /** "4,5", "2", "0,25": a VAB count with at most two decimals and the [decimalSeparator] of the language. */
    fun days(value: Double, decimalSeparator: Char): String {
        val hundredths = kotlin.math.round(value * HUNDRED).toLong()
        val whole = hundredths / HUNDRED.toLong()
        val rest = hundredths % HUNDRED.toLong()
        if (rest == 0L) return whole.toString()
        val fraction = rest.toString().padStart(2, '0').trimEnd('0')
        return "$whole$decimalSeparator$fraction"
    }

    private const val HUNDRED = 100.0
}
