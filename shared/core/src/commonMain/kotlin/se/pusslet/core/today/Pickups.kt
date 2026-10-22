package se.pusslet.core.today

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffReceipt
import se.pusslet.core.time.plusDays
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** A pick-up on a day: who does it, when, which children. */
data class Pickup(val occurrence: EventOccurrence, val time: LocalTime, val personId: String?, val children: List<FamilyPerson>) {
    val key: String get() = "${occurrence.key}/${HandoffKind.PickUp.name}"
}

/** "Ingen har bockat av att Ella är hämtad (15:45) · Bengt skulle hämta" (ADR-048). */
data class UnconfirmedPickup(val pickup: Pickup, val person: FamilyPerson?)

/** "Ella hämtad 15:52 · Bengt" on Idag; [byMe] can still be undone. */
data class PickedUp(val receipt: HandoffReceipt, val children: List<FamilyPerson>, val by: FamilyPerson?, val byMe: Boolean)

/**
 * "Hämtad ✓" (ADR-048): which pick-ups are done, which can be ticked off, and which nobody has ticked off [GRACE] after
 * their time. Pick-ups are worked out like Idag's hand-offs: on the last day of an occurrence, for the children attending
 * it (a sick child has none).
 */
object Pickups {
    /** "Hämtad" is offered from this long before the pick-up. */
    val CONFIRM_FROM: Duration = 30.minutes

    /** Nobody ticked it off this long after the time: a warning. */
    val GRACE: Duration = 15.minutes

    fun on(date: LocalDate, occurrences: List<EventOccurrence>, people: Map<String, FamilyPerson>): List<Pickup> =
        occurrences.filter { it.overlapsDate(date) && lastDay(it) == date && it.event.pickUp != null }.mapNotNull { o ->
            val children = o.attending.mapNotNull { people[it] }.filter { it.isChild }
            val time = o.pickUpTime() ?: return@mapNotNull null
            if (children.isEmpty()) null else Pickup(o, time, o.event.pickUp?.personId, children)
        }

    fun receiptFor(receipts: List<HandoffReceipt>, occurrence: EventOccurrence, kind: HandoffKind = HandoffKind.PickUp): HandoffReceipt? =
        receipts.firstOrNull { it.matches(occurrence, kind) }

    /** Pick-ups somebody was to do, [GRACE] past their time at [now], with no receipt. */
    fun unconfirmed(
        date: LocalDate,
        now: LocalTime,
        occurrences: List<EventOccurrence>,
        people: Map<String, FamilyPerson>,
        receipts: List<HandoffReceipt>,
    ): List<UnconfirmedPickup> = on(date, occurrences, people)
        .filter { p -> p.personId != null && pastGrace(p.time, now) && receiptFor(receipts, p.occurrence) == null }
        .map { UnconfirmedPickup(it, people[it.personId]) }
        .sortedBy { it.pickup.time }

    /** Whether "Hämtad" is offered for a pick-up at [time] at [now]. */
    fun confirmable(time: LocalTime, now: LocalTime): Boolean = now.toSecondOfDay() >= time.toSecondOfDay() - CONFIRM_FROM.inWholeSeconds

    /** [now] is [GRACE] or more after [time] (the same day). */
    fun pastGrace(time: LocalTime, now: LocalTime): Boolean = now.toSecondOfDay() >= time.toSecondOfDay() + GRACE.inWholeSeconds

    private fun lastDay(o: EventOccurrence): LocalDate =
        if (o.allDay || o.end.time == LocalTime(0, 0)) maxOf(o.start.date, o.end.date.plusDays(-1)) else o.end.date
}
