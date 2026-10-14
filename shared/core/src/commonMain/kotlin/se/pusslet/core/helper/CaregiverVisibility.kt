package se.pusslet.core.helper

import se.pusslet.core.calendar.EffectiveHandoffs.matches
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.HandoffRequest

/**
 * What a caregiver (morfar Bengt) may see, mirroring firestore.rules (ADR-031): the family's own events shared with
 * caregivers, red days, and shared tasks – never subscriptions or device calendars, even when they carry the flag on a
 * parent's phone. Used by the helper view as an extra guard (old data after a role change) and by the parents' preview
 * ("Så ser Bengt appen"), so the preview shows exactly what his phone would.
 */
object CaregiverVisibility {
    fun canSee(occurrence: EventOccurrence): Boolean = when (occurrence.source) {
        EventSource.Family -> occurrence.event.sharedWithCaregivers
        EventSource.Holiday -> true
        EventSource.Subscription, EventSource.Device -> false
    }

    fun canSee(task: FamilyTask): Boolean = task.sharedWithCaregivers

    fun occurrences(all: List<EventOccurrence>): List<EventOccurrence> = all.filter(::canSee)

    fun tasks(all: List<FamilyTask>): List<FamilyTask> = all.filter(::canSee)

    /** Requests about occurrences the caregiver can see; a request about a private event never reaches him. */
    fun requests(all: List<HandoffRequest>, visible: List<EventOccurrence>): List<HandoffRequest> =
        all.filter { request -> visible.any { request.matches(it) } }

    /** How many of [all] are hidden from the caregiver ("Bengt ser inte 2 händelser den här veckan"). */
    fun hiddenCount(all: List<EventOccurrence>): Int = all.count { it.source == EventSource.Family && !canSee(it) }
}
