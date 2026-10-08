package se.pusslet.core.custody

import kotlinx.datetime.LocalDate
import se.pusslet.core.model.Child
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.PackingTrigger
import se.pusslet.core.model.TaskCategory

/**
 * Överlämningskort: what the other home needs to know before the children move. Contains only what is listed here –
 * the other household never gets access to the rest of the family's data.
 */
data class HandoverCard(
    val handover: Handover,
    val children: List<Child>,
    /** Things that travel with the children. */
    val bring: List<String>,
    val homework: List<String>,
    val upcoming: List<UpcomingItem>,
    val note: String = "",
)

data class UpcomingItem(val date: LocalDate, val title: String, val childNames: List<String>)

object HandoverCardBuilder {
    /**
     * @param occurrences occurrences in the next household period (already expanded).
     * @param periodEnd last day the children stay in the receiving household.
     */
    fun build(
        handover: Handover,
        children: List<Child>,
        rules: List<PackingRule>,
        tasks: List<FamilyTask>,
        occurrences: List<EventOccurrence>,
        periodEnd: LocalDate,
        note: String = "",
    ): HandoverCard {
        val childIds = handover.childIds.toSet()
        val involved = children.filter { it.id in childIds }
        val bring = rules.filter { r ->
            r.active && r.trigger == PackingTrigger.Handover && (r.childId == null || r.childId in childIds)
        }.map { it.item }.distinct()
        val homework = tasks.filter { t ->
            !t.done && t.category == TaskCategory.Homework && (t.due == null || t.due <= periodEnd)
        }.map { it.title }
        val names = involved.associate { it.id to it.firstName }
        val upcoming = occurrences.filter { o ->
            o.start.date >= handover.date && o.start.date <= periodEnd && o.event.participantIds.any { it in childIds }
        }.sortedBy { it.start }.map { o ->
            UpcomingItem(o.start.date, o.event.title, o.event.participantIds.mapNotNull { names[it] })
        }
        return HandoverCard(handover, involved, bring, homework, upcoming.distinct(), note)
    }
}
