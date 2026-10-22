package se.pusslet.data.notify

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toInstant
import se.pusslet.core.model.Child
import se.pusslet.core.model.Event
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.Member
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.model.Role
import se.pusslet.core.notify.NotificationKind
import se.pusslet.core.notify.NotificationPayload
import se.pusslet.core.notify.NotificationTarget
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.sources.MutableClock
import se.pusslet.data.sources.SubscriptionWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PushMessagesTest {
    private val day = LocalDate(2026, 10, 22)
    private val clock = MutableClock()

    private suspend fun world(): Pair<SubscriptionWorld, PushMessages> {
        val world = SubscriptionWorld(clock)
        clock.now = LocalDateTime(LocalDate(2026, 10, 21), LocalTime(16, 0)).toInstant(world.zone)
        val family =
            FamilyRepository(world.docs, world.sessions, LocalOnlyFamilyRemote, LocalOnlyAuthRepository(), SequentialIdGenerator(), clock)
        family.saveMember(Member("m-sara", "Sara"))
        family.saveMember(Member("m-bengt", "Bengt", Role.Caregiver))
        family.saveChild(Child("c-ella", "Ella"))
        world.calendar.saveEvent(
            Event(
                "e-skola",
                "Skola",
                LocalDateTime(day, LocalTime(8, 15)),
                LocalDateTime(day, LocalTime(15, 0)),
                participantIds = listOf("c-ella"),
                pickUp = Handoff("m-sara", LocalTime(15, 45)),
            ),
        )
        return world to PushMessages(world.calendar, family, world.sessions, world.prefs, clock, world.zone)
    }

    private fun data(vararg pairs: Pair<String, String>) = mapOf("familyId" to "f") + pairs

    private val request = data(
        "type" to "request",
        "requestId" to "r1",
        "eventId" to "e-skola",
        "date" to "2026-10-22",
        "kind" to "pickup",
        "from" to "m-bengt",
    )

    @Test
    fun aRequestIsWordedFromThisPhonesOwnData() = runTest {
        val (_, push) = world()
        val n = push.planned(request)
        assertEquals(NotificationKind.Requests, n?.kind)
        assertEquals(
            NotificationPayload.Request("e-skola", day, HandoffKind.PickUp, "Bengt", listOf("Ella"), LocalTime(15, 45), "Skola"),
            n?.payload,
        )
        assertEquals(NotificationTarget.Occurrence("e-skola", day), n?.target)
        assertEquals(clock.now, n?.at)
    }

    @Test
    fun answersAndShopping() = runTest {
        val (_, push) = world()
        val no = push.planned(
            data(
                "type" to "answer",
                "requestId" to "r1",
                "eventId" to "e-skola",
                "date" to "2026-10-22",
                "status" to "declined",
                "by" to "m-bengt",
            ),
        )
        assertEquals(NotificationPayload.Answer("e-skola", day, HandoffKind.PickUp, "Bengt", false, listOf("Ella"), "Skola"), no?.payload)

        val item = push.planned(data("type" to "shopping", "listId" to "l1", "itemId" to "i1", "by" to "m-bengt", "item" to "Mjölk"))
        assertEquals(NotificationPayload.ShoppingAdded("Bengt", "Mjölk"), item?.payload)
        assertEquals(NotificationKind.Shopping, item?.kind)
    }

    @Test
    fun aRequestWaitsForTheQuietHoursToEndButAnItemWhileShoppingDoesNot() = runTest {
        val (world, push) = world()
        clock.now = LocalDateTime(LocalDate(2026, 10, 21), LocalTime(22, 10)).toInstant(world.zone)
        assertEquals(LocalDateTime(day, LocalTime(6, 30)).toInstant(world.zone), push.planned(request)?.at)
        val item = push.planned(data("type" to "shopping", "listId" to "l1", "by" to "m-bengt", "item" to "Mjölk"))
        assertEquals(clock.now, item?.at)
    }

    @Test
    fun aRequestThisPhoneKnowsIsSettledIsLeftOut() = runTest {
        val (world, push) = world()
        val pending = HandoffRequest("r1", "e-skola", day, fromMemberId = "m-bengt")
        world.calendar.saveHandoffRequest(pending)
        assertNotNull(push.planned(request), "still open")

        world.calendar.saveHandoffRequest(pending.copy(declines = mapOf("m-sara" to clock.now)))
        assertNull(push.planned(request), "I already said no")

        world.calendar.saveHandoffRequest(pending.copy(status = RequestStatus.Accepted, respondedBy = "m-johan"))
        assertNull(push.planned(request), "someone else took it")
    }

    @Test
    fun aSickChildIsWordedFromThisPhonesOwnNames() = runTest {
        val (_, push) = world()
        val sick = data(
            "type" to "sick",
            "sickDayId" to "c-ella_2026-10-21",
            "childId" to "c-ella",
            "date" to "2026-10-21",
            "by" to "m-sara",
        )
        val n = push.planned(sick)
        assertEquals(NotificationKind.SickDay, n?.kind)
        assertEquals(NotificationPayload.Sick("Ella", LocalDate(2026, 10, 21), tomorrow = false, by = "Sara", homeWith = null), n?.payload)
        val home = push.planned(sick + ("homeWith" to "m-bengt"))
        assertEquals("Bengt", (home?.payload as NotificationPayload.Sick).homeWith)
        assertEquals(n?.key, home.key, "who stays home replaces the first message")
        assertNull(push.planned(sick + ("date" to "2026-10-20")), "a day that has passed")
        assertEquals(true, (push.planned(sick + ("date" to "2026-10-22"))?.payload as NotificationPayload.Sick).tomorrow)
    }

    @Test
    fun anotherFamilyOrAnUnknownEventIsLeftAlone() = runTest {
        val (_, push) = world()
        assertNull(push.planned(mapOf("familyId" to "annan", "type" to "shopping", "item" to "Mjölk")))
        assertNull(push.planned(data("type" to "request", "eventId" to "borta", "date" to "2026-10-22")))
        assertNull(push.planned(data("type" to "shopping", "item" to " ")))
        assertNull(push.planned(data("type" to "okänd")))
    }
}
