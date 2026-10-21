package se.pusslet.core.notify

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class BundlingTest {
    private val day = LocalDate(2026, 10, 22)

    private fun task(id: String, at: String, kind: NotificationKind = NotificationKind.Reminder) =
        PlannedNotification("task:$id:$day", Instant.parse(at), kind, NotificationPayload.Task(id, day, "Köp present", null))

    @Test
    fun threeAtTheSameTimeBecomeOne() {
        val list = Bundling.bundle(
            listOf(task("b", "2026-10-22T04:30:00Z"), task("a", "2026-10-22T04:30:00Z"), task("c", "2026-10-22T04:30:00Z")),
        )
        val n = list.single()
        assertEquals("bundle:Reminder:task:a:$day", n.key)
        assertEquals(Instant.parse("2026-10-22T04:30:00Z"), n.at)
        val bundle = assertIs<NotificationPayload.Bundle>(n.payload)
        assertEquals(listOf("a", "b", "c"), bundle.items.map { (it.payload as NotificationPayload.Task).taskId })
        assertEquals(NotificationKind.Reminder, n.kind)
    }

    @Test
    fun withinFiveMinutesOfTheFirst() {
        val list = Bundling.bundle(
            listOf(
                task("a", "2026-10-22T13:00:00Z"),
                task("b", "2026-10-22T13:05:00Z"),
                task("c", "2026-10-22T13:08:00Z"),
                task("d", "2026-10-22T13:20:00Z"),
            ),
        )
        // 13:08 is more than five minutes after 13:00, so it starts a run of its own.
        assertEquals(listOf("bundle:Reminder:task:a:$day", "task:c:$day", "task:d:$day"), list.map { it.key })
    }

    @Test
    fun differentKindsStayApart() {
        val list = Bundling.bundle(listOf(task("a", "2026-10-22T13:00:00Z"), task("b", "2026-10-22T13:00:00Z", NotificationKind.Requests)))
        assertEquals(2, list.size)
    }

    @Test
    fun morningAndEveningAreNeverMerged() {
        val morning = PlannedNotification(
            "morning:$day",
            Instant.parse("2026-10-22T04:45:00Z"),
            NotificationKind.Morning,
            NotificationPayload.Morning(day, se.pusslet.core.today.Greeting.Morning, emptyList(), emptyList()),
        )
        val list = Bundling.bundle(listOf(morning, morning.copy(key = "morning:x"), task("a", "2026-10-22T04:45:00Z")))
        assertEquals(listOf("morning:$day", "morning:x", "task:a:$day"), list.map { it.key })
    }

    @Test
    fun aSummaryOpensWhatAllOfItOpens() {
        val pickUps = listOf("a", "b").map { id ->
            PlannedNotification(
                "handoff:$id",
                Instant.parse("2026-10-22T13:15:00Z"),
                NotificationKind.HandoffSoon,
                NotificationPayload.Handoff(
                    "e-$id",
                    day,
                    se.pusslet.core.model.HandoffKind.PickUp,
                    LocalTime(15, 45),
                    listOf("Ella"),
                    "Skolan",
                ),
                userTimed = true,
            )
        }
        val different = Bundling.bundle(pickUps).single()
        assertEquals(NotificationTarget.Today, different.target)
        assertEquals(true, different.userTimed)
        val sameEvent = pickUps.map { it.copy(payload = (it.payload as NotificationPayload.Handoff).copy(eventId = "e-1")) }
        assertEquals(NotificationTarget.Occurrence("e-1", day), Bundling.bundle(sameEvent).single().target)
    }

    @Test
    fun oneAloneIsLeftAsItIs() {
        val single = task("a", "2026-10-22T13:00:00Z")
        assertEquals(listOf(single), Bundling.bundle(listOf(single)))
    }
}
