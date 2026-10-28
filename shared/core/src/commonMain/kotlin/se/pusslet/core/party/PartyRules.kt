package se.pusslet.core.party

import kotlinx.datetime.LocalDate
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.PartyDetails
import se.pusslet.core.model.Rsvp
import se.pusslet.core.model.TaskCategory
import se.pusslet.core.time.plusDays

/** When a kalas needs something from the family: an answer (OSA) or a present. Rules only (ADR-001, ADR-054). */
object PartyRules {
    /** Remind about the present this many days before (BRIEF §4.8). */
    const val GIFT_REMINDER_DAYS = 3

    fun isParty(e: Event): Boolean = e.party != null || e.tags.any { it.equals(EventTags.PARTY, ignoreCase = true) }

    fun details(e: Event): PartyDetails = e.party ?: PartyDetails()

    /** To-dos about the present: linked to the party, or gift to-dos due between today and the party. */
    fun giftTasks(o: EventOccurrence, tasks: List<FamilyTask>, today: LocalDate): List<FamilyTask> = tasks.filter { t ->
        t.linkedEventId == o.event.id || t.linkedEventId == o.event.seriesId ||
            (t.category == TaskCategory.Gift && t.due != null && t.due <= o.start.date && t.due >= today)
    }

    /**
     * Someone else's party the family has not said no to, still ahead, and the present is neither marked bought nor
     * ticked off as a to-do.
     */
    fun needsGift(o: EventOccurrence, tasks: List<FamilyTask>, today: LocalDate): Boolean {
        if (!isParty(o.event) || o.start.date < today) return false
        val p = details(o.event)
        if (p.own || p.rsvp == Rsvp.No || p.gift.bought) return false
        return giftTasks(o, tasks, today).none { it.done }
    }

    /** An invitation with an "OSA senast" date that is not answered yet. */
    fun needsRsvp(e: Event, today: LocalDate): Boolean {
        if (!isParty(e)) return false
        val p = details(e)
        val by = p.rsvpBy ?: return false
        return !p.own && p.rsvp == Rsvp.Pending && by >= today
    }

    /** The day the present reminder is for (never before today). */
    fun giftReminderDay(o: EventOccurrence, today: LocalDate): LocalDate? {
        val day = o.start.date.plusDays(-GIFT_REMINDER_DAYS)
        return day.takeIf { it >= today }
    }
}
