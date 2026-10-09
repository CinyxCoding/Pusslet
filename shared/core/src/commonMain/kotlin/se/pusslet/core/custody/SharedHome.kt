package se.pusslet.core.custody

import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.DietProfile
import se.pusslet.core.model.Event
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.ManualLunchWeek
import se.pusslet.core.model.PackingChecks
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.WishItem

/**
 * What belongs in the space a home shares with the other home (ADR-030): things about the shared children and nothing
 * else. Something that is also about a child who is not shared (a half-sibling) stays in this home.
 *
 * @param shared the children both homes have.
 * @param children every child this home knows about (shared or not).
 */
class SharedHome(val shared: Set<String>, children: Set<String>) {
    private val kids = children + shared

    private fun about(ids: Collection<String>): Boolean {
        val mine = ids.filter { it in kids }
        return mine.isNotEmpty() && mine.all { it in shared }
    }

    /** The event's children (participants and whoever is dropped off or picked up). */
    fun shares(event: Event): Boolean =
        about(event.participantIds + listOfNotNull(event.dropOff?.personId, event.pickUp?.personId))

    /** A task done by a shared child (homework, a chore), alone or in turns. */
    fun shares(task: FamilyTask): Boolean = about(listOfNotNull(task.assigneeId) + task.rotation?.memberIds.orEmpty())

    /** A rule for one shared child; a rule for every child stays in each home. */
    fun shares(rule: PackingRule): Boolean = rule.childId != null && rule.childId in shared

    fun shares(checks: PackingChecks): Boolean = checks.childId in shared

    fun shares(schedule: CustodySchedule): Boolean = schedule.childIds.any { it in shared }

    fun shares(wish: WishItem): Boolean = wish.childId in shared

    fun shares(lunch: ManualLunchWeek): Boolean = lunch.childId in shared

    /** Allergies and diets are sensitive: only shared when the parent said so. */
    fun shares(diet: DietProfile, shareDiets: Boolean): Boolean = shareDiets && diet.personId in shared

    /** A request follows its event. */
    fun shares(request: HandoffRequest, sharedEventIds: Set<String>): Boolean = request.eventId in sharedEventIds
}
