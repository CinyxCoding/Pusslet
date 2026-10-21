package se.pusslet.core.notify

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationTargetTest {
    @Test
    fun everyTargetSurvivesTheIntent() {
        val targets = listOf(
            NotificationTarget.Today,
            NotificationTarget.Shopping,
            NotificationTarget.Occurrence("e-school", LocalDate(2026, 10, 21)),
            NotificationTarget.Occurrence("uid/with%2Fslash@skolan.se", LocalDate(2026, 12, 31)),
            NotificationTarget.Task("t-1"),
        )
        targets.forEach { assertEquals(it, NotificationTarget.decode(it.encode())) }
    }

    @Test
    fun theEncodingIsReadable() {
        assertEquals("event/e-school/2026-10-21", NotificationTarget.Occurrence("e-school", LocalDate(2026, 10, 21)).encode())
        assertEquals("event/a%2Fb%25c/2026-10-21", NotificationTarget.Occurrence("a/b%c", LocalDate(2026, 10, 21)).encode())
        assertEquals("task/t-1", NotificationTarget.Task("t-1").encode())
        assertEquals("today", NotificationTarget.Today.encode())
    }

    @Test
    fun anythingElseOpensNothing() {
        listOf(null, "", "today/x", "event/e1", "event/e1/not-a-date", "event//2026-10-21", "task/", "task/a%zz", "join/ABC").forEach {
            assertNull(NotificationTarget.decode(it), "decode($it)")
        }
    }

    @Test
    fun notificationIdsAreStable() {
        val n = PlannedNotification(
            "morning:2026-10-22",
            kotlin.time.Instant.parse("2026-10-22T04:45:00Z"),
            NotificationKind.Morning,
            NotificationPayload.Evening(LocalDate(2026, 10, 22), emptyList()),
        )
        assertEquals(n.notificationId, n.copy(at = kotlin.time.Instant.parse("2026-10-22T05:00:00Z")).notificationId)
        assertEquals("morning:2026-10-22".fold(17) { h, c -> 31 * h + c.code }, n.notificationId)
    }
}
