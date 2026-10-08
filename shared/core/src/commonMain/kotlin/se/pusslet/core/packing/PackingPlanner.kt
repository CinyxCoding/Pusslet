package se.pusslet.core.packing

import kotlinx.datetime.LocalDate
import se.pusslet.core.custody.CustodyCalculator
import se.pusslet.core.model.Child
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.PackingChecks
import se.pusslet.core.model.PackingItem
import se.pusslet.core.model.PackingReason
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.PackingTrigger
import se.pusslet.core.time.isoWeek

/** Builds "Ska med idag" per child from recurring packing rules, tagged events and one-off event items. */
object PackingPlanner {
    fun itemsFor(
        date: LocalDate,
        children: List<Child>,
        rules: List<PackingRule>,
        occurrences: List<EventOccurrence>,
        custody: List<CustodySchedule> = emptyList(),
        checks: Map<String, PackingChecks> = emptyMap(),
    ): Map<String, List<PackingItem>> = children.associate { child ->
        val checked = checks[PackingChecks.docId(date, child.id)]?.checked.orEmpty()
        val todays = occurrences.filter { it.overlapsDate(date) && child.id in it.event.participantIds }
        val items = ArrayList<PackingItem>()

        for (rule in rules) {
            if (!rule.active || (rule.childId != null && rule.childId != child.id)) continue
            val reason: PackingReason? = when (val trigger = rule.trigger) {
                is PackingTrigger.Weekly -> if (matchesWeekly(trigger, date)) PackingReason.Rule(rule.id) else null
                is PackingTrigger.EventTag -> todays.firstOrNull { o -> o.event.tags.any { it.equals(trigger.tag, ignoreCase = true) } }
                    ?.let { PackingReason.ForEvent(it.event.title) }
                is PackingTrigger.OnDate -> if (trigger.date == date) PackingReason.Rule(rule.id) else null
                PackingTrigger.Handover -> if (custody.any { child.id in it.childIds && CustodyCalculator.isHandoverDay(it, date) }) {
                    PackingReason.Handover
                } else {
                    null
                }
            }
            if (reason != null) {
                val key = "rule:${rule.id}"
                items += PackingItem(key, child.id, rule.item, reason, checked[key] == true)
            }
        }
        for (o in todays) {
            o.event.packingItems.forEachIndexed { i, text ->
                val key = "event:${o.event.id}:$i"
                items += PackingItem(key, child.id, text, PackingReason.ForEvent(o.event.title), checked[key] == true)
            }
        }
        child.id to items.distinctBy { it.text.lowercase() }
    }

    fun matchesWeekly(trigger: PackingTrigger.Weekly, date: LocalDate): Boolean {
        if (date.dayOfWeek !in trigger.days) return false
        if (trigger.intervalWeeks <= 1) return true
        val anchor = trigger.anchorWeek ?: return true
        val weeks = ((date.isoWeek.monday.toEpochDays() - anchor.monday.toEpochDays()) / 7).toInt()
        return ((weeks % trigger.intervalWeeks) + trigger.intervalWeeks) % trigger.intervalWeeks == 0
    }
}
