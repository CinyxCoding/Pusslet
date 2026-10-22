package se.pusslet.core.notify

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.notify.NotifyFixtures.zone
import se.pusslet.core.time.TimeWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** Tysta timmar 21:00–06:30 (the default) in Stockholm; 21:00 local is 19:00Z in October before the change. */
class QuietHoursTest {
    private val night = TimeWindow(LocalTime(21, 0), LocalTime(6, 30))

    private fun adjust(
        at: String,
        kind: NotificationKind,
        userTimed: Boolean = false,
        window: TimeWindow = night,
        enabled: Boolean = true,
    ) = QuietHours.adjust(Instant.parse(at), window, enabled, kind, zone, userTimed)

    @Test
    fun aToDoAtElevenWaitsUntilTheNextMorning() {
        // Wednesday 23:00 -> Thursday 06:30.
        assertEquals(Instant.parse("2026-10-22T04:30:00Z"), adjust("2026-10-21T21:00:00Z", NotificationKind.Reminder))
    }

    @Test
    fun afterMidnightItWaitsUntilTheSameMorning() {
        // Thursday 02:00 -> Thursday 06:30.
        assertEquals(Instant.parse("2026-10-22T04:30:00Z"), adjust("2026-10-22T00:00:00Z", NotificationKind.Requests))
    }

    @Test
    fun theEdgesOfTheWindow() {
        // 21:00 is inside, 20:59 and 06:30 are outside.
        assertEquals(Instant.parse("2026-10-22T04:30:00Z"), adjust("2026-10-21T19:00:00Z", NotificationKind.Conflicts))
        assertEquals(Instant.parse("2026-10-21T18:59:00Z"), adjust("2026-10-21T18:59:00Z", NotificationKind.Conflicts))
        assertEquals(Instant.parse("2026-10-22T04:30:00Z"), adjust("2026-10-22T04:30:00Z", NotificationKind.Conflicts))
    }

    @Test
    fun whatTheUserAskedForKeepsItsTime() {
        val late = "2026-10-21T21:00:00Z" // 23:00
        assertEquals(Instant.parse(late), adjust(late, NotificationKind.Reminder, userTimed = true))
        assertEquals(Instant.parse(late), adjust(late, NotificationKind.HandoffSoon))
        assertEquals(Instant.parse(late), adjust(late, NotificationKind.Shopping))
        // Morgonkollen at 06:00, inside the window, comes at 06:00.
        assertEquals(Instant.parse("2026-10-22T04:00:00Z"), adjust("2026-10-22T04:00:00Z", NotificationKind.Morning))
    }

    @Test
    fun kvallskollInsideTheWindowIsDropped() {
        val evening = TimeWindow(LocalTime(18, 30), LocalTime(6, 30))
        assertNull(adjust("2026-10-21T17:00:00Z", NotificationKind.Evening, window = evening))
        // Outside the window it stays.
        assertEquals(Instant.parse("2026-10-21T17:00:00Z"), adjust("2026-10-21T17:00:00Z", NotificationKind.Evening))
    }

    @Test
    fun switchedOffChangesNothing() {
        assertEquals(Instant.parse("2026-10-21T21:00:00Z"), adjust("2026-10-21T21:00:00Z", NotificationKind.Requests, enabled = false))
        assertEquals(Instant.parse("2026-10-21T21:00:00Z"), adjust("2026-10-21T21:00:00Z", NotificationKind.Evening, enabled = false))
    }

    @Test
    fun aWindowWithinTheDay() {
        val nap = TimeWindow(LocalTime(13, 0), LocalTime(15, 0))
        // 14:00 -> 15:00 the same day.
        assertEquals(Instant.parse("2026-10-21T13:00:00Z"), adjust("2026-10-21T12:00:00Z", NotificationKind.Requests, window = nap))
    }

    @Test
    fun theMorningAfterTheAutumnChange() {
        // Saturday 24 October 23:00 CEST -> Sunday 06:30, which is CET (05:30Z) after the change at 03:00.
        assertEquals(Instant.parse("2026-10-25T05:30:00Z"), adjust("2026-10-24T21:00:00Z", NotificationKind.Reminder))
    }

    @Test
    fun applyDefersDropsAndKeepsKeys() {
        fun n(key: String, at: String, kind: NotificationKind) =
            PlannedNotification(key, Instant.parse(at), kind, NotificationPayload.Evening(LocalDate(2026, 10, 22), emptyList()))
        val list = QuietHours.apply(
            listOf(
                n("a", "2026-10-21T21:00:00Z", NotificationKind.Requests),
                n("b", "2026-10-21T21:00:00Z", NotificationKind.Evening),
                n("c", "2026-10-21T12:00:00Z", NotificationKind.Requests),
            ),
            QuietSettings(night, enabled = true),
            zone,
        )
        assertEquals(
            listOf("a" to Instant.parse("2026-10-22T04:30:00Z"), "c" to Instant.parse("2026-10-21T12:00:00Z")),
            list.map {
                it.key to
                    it.at
            },
        )
    }

    @Test
    fun whichKindsPass() {
        val passing = NotificationKind.entries.filter { QuietHours.passes(it, userTimed = false) }
        assertEquals(
            listOf(NotificationKind.HandoffSoon, NotificationKind.Morning, NotificationKind.Shopping, NotificationKind.Pickups),
            passing,
            "a child waiting to be picked up never waits for the quiet hours",
        )
    }
}
