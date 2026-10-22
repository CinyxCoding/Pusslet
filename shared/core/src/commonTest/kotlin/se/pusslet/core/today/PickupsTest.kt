package se.pusslet.core.today

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toInstant
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffReceipt
import se.pusslet.core.model.Role
import se.pusslet.core.notify.NotificationKind
import se.pusslet.core.notify.NotificationPayload
import se.pusslet.core.notify.NotifyFixtures.base
import se.pusslet.core.notify.NotifyFixtures.bengt
import se.pusslet.core.notify.NotifyFixtures.ella
import se.pusslet.core.notify.NotifyFixtures.input
import se.pusslet.core.notify.NotifyFixtures.johan
import se.pusslet.core.notify.NotifyFixtures.people
import se.pusslet.core.notify.NotifyFixtures.sara
import se.pusslet.core.notify.NotifyFixtures.wednesday
import se.pusslet.core.notify.NotifyFixtures.zone
import se.pusslet.core.notify.ReminderPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** "Hämtad ✓" (ADR-048). Sara picks Simon up at 15:30 and Ella at 15:45 (NotifyFixtures); Johan drops Simon off. */
class PickupsTest {
    private val byId = people.associateBy { it.id }

    private fun at(time: LocalTime): Instant = LocalDateTime(wednesday, time).toInstant(zone)

    private fun schoolToday(): EventOccurrence = base().occurrences.single { it.event.id == "e-school" && it.occurrenceDate == wednesday }

    private fun receipt(o: EventOccurrence, by: String = sara.id) = HandoffReceipt(
        id = HandoffReceipt.idFor(o, HandoffKind.PickUp),
        eventId = HandoffReceipt.seriesOf(o),
        occurrenceDate = o.occurrenceDate,
        childIds = listOf(ella.id),
        doneBy = by,
        doneAt = at(LocalTime(15, 52)),
    )

    @Test
    fun myPickUpCanBeTickedOffFromHalfAnHourBeforeAndStaysUntilItIs() {
        fun plan(time: LocalTime, receipts: List<HandoffReceipt> = emptyList()) =
            TodayPlanner.build(base(now = at(time)).copy(receipts = receipts))

        val before = plan(LocalTime(14, 50)).hero.handoffs.single { it.isMe && it.kind == HandoffKind.PickUp }
        assertTrue(!before.confirmable, "too early: the first is Simon at 15:30")
        assertTrue(plan(LocalTime(15, 5)).hero.handoffs.single { it.isMe && it.kind == HandoffKind.PickUp }.confirmable)
        val late = plan(LocalTime(17, 0)).hero.handoffs.single { it.isMe && it.kind == HandoffKind.PickUp }
        assertTrue(late.confirmable, "still there after the time, until ticked off")
        val done = plan(LocalTime(17, 0), listOf(receipt(schoolToday())))
        val left = done.hero.handoffs.single { it.isMe && it.kind == HandoffKind.PickUp }
        assertEquals(listOf("Simon"), left.stops.map { it.child.name }, "Ella is ticked off, Simon not yet")
        assertEquals(listOf(ella.id), done.pickedUp.single().children.map { it.id })
        assertTrue(done.pickedUp.single().byMe)
    }

    @Test
    fun somebodyElsesPickUpThatNobodyTickedOffIsAProblemAfterAQuarter() {
        // Johan's view of Sara's pick-ups; her own would be in her Morgonkollen instead.
        val early = TodayPlanner.build(base(now = at(LocalTime(15, 40)), me = johan.id))
        assertTrue(early.unconfirmedPickups.isEmpty(), "not yet a quarter past")
        val late = TodayPlanner.build(base(now = at(LocalTime(15, 46)), me = johan.id))
        val saras = late.unconfirmedPickups.single()
        assertEquals(sara.id, saras.person?.id)
        assertEquals(LocalTime(15, 30), saras.pickup.time, "Simon's; Ella's is not a quarter late yet")
        val preschool = saras.pickup.occurrence
        val done = TodayPlanner.build(base(now = at(LocalTime(15, 46)), me = johan.id).copy(receipts = listOf(receipt(preschool))))
        assertTrue(done.unconfirmedPickups.isEmpty())
    }

    @Test
    fun theWarningGoesToTheParentsAndTheOneWhoWasToPickUp() {
        val morning = at(LocalTime(9, 0))
        val sarasWarnings = ReminderPlanner.plan(input(now = morning)).filter { it.kind == NotificationKind.Pickups }
        val ellas = sarasWarnings.single { it.key == "pickup-check:${schoolToday().key}/PickUp" }
        assertEquals(at(LocalTime(16, 0)), ellas.at, "15:45 and a quarter")
        val payload = ellas.payload as NotificationPayload.PickupUnconfirmed
        assertTrue(payload.isMe)
        assertEquals(listOf("Ella"), payload.children)
        val johans = ReminderPlanner.plan(input(now = morning, me = johan.id)).filter { it.kind == NotificationKind.Pickups }
        assertTrue(
            johans.isNotEmpty() && johans.all {
                (it.payload as NotificationPayload.PickupUnconfirmed).by == "Sara"
            },
            "Sara's, as a parent",
        )

        val bengts = ReminderPlanner.plan(input(now = morning, me = bengt.id, role = Role.Caregiver)).filter {
            it.kind ==
                NotificationKind.Pickups
        }
        assertTrue(bengts.isEmpty(), "a caregiver is only warned about his own")

        val ticked = input(now = morning).let { it.copy(base = it.base.copy(receipts = listOf(receipt(schoolToday())))) }
        assertTrue(ReminderPlanner.plan(ticked).none { it.key == "pickup-check:${schoolToday().key}/PickUp" })
        val off = input(now = morning).copy(pickupWarnings = false)
        assertTrue(ReminderPlanner.plan(off).none { it.kind == NotificationKind.Pickups })
    }

    @Test
    fun aReceiptIsFoundForAMovedOccurrenceOfTheSeries() {
        val o = schoolToday()
        val moved = o.copy(event = o.event.copy(id = "override-1", seriesId = o.event.id, rrule = null))
        val r = receipt(o)
        assertTrue(r.matches(moved, HandoffKind.PickUp))
        assertNull(Pickups.receiptFor(listOf(r), o, HandoffKind.DropOff))
        assertEquals(listOf<FamilyPerson>(), Pickups.on(wednesday, emptyList(), byId).flatMap { it.children })
    }
}
