package se.pusslet.core.notify

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import se.pusslet.core.model.ChoreRotation
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.TaskKind
import se.pusslet.core.time.IsoWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ReminderPlannerTest {
    private val zone = TimeZone.of("Europe/Stockholm")
    private val today = LocalDate(2026, 10, 8)
    private val now = LocalDateTime(today, LocalTime(7, 10)).toInstant(zone)
    private val adults = setOf("sara", "johan")

    private fun at(date: LocalDate, h: Int, m: Int = 0): Instant = LocalDateTime(date, LocalTime(h, m)).toInstant(zone)

    private fun occ(event: Event, source: EventSource = EventSource.Family) =
        EventOccurrence(event, event.start, event.end, event.start.date, source)

    private val football = Event(
        "fotboll",
        "Fotboll",
        LocalDateTime(today, LocalTime(17, 30)),
        LocalDateTime(today, LocalTime(18, 30)),
        participantIds = listOf("simon"),
        dropOff = Handoff("sara"),
        pickUp = Handoff("johan", LocalTime(18, 30)),
        reminders = listOf(60),
    )

    private fun plan(
        me: String,
        vararg o: EventOccurrence,
        tasks: List<FamilyTask> = emptyList(),
        settings: ReminderSettings = ReminderSettings(),
    ) = ReminderPlanner.plan(me, adults, o.toList(), tasks, settings, now, zone)

    @Test
    fun eventRemindersAndHandOffsGoToTheAdultsInvolved() {
        val sara = plan("sara", occ(football))
        assertEquals(
            listOf("event:fotboll@2026-10-08:60", "handoff:fotboll@2026-10-08:DropOff"),
            sara.map { it.key },
        )
        assertEquals(at(today, 16, 30), sara[0].at)
        val drop = sara[1] as PlannedNotification.Handoff
        assertEquals(at(today, 17, 15), drop.at, "15 minutes before drop-off")
        assertEquals(listOf("simon"), drop.childIds)

        val johan = plan("johan", occ(football))
        assertEquals(PlannedNotification.Handoff::class, johan.last()::class)
        assertEquals(HandoffKind.PickUp, (johan.last() as PlannedNotification.Handoff).kind)
        assertEquals(at(today, 18, 15), johan.last().at)
    }

    @Test
    fun anEventWithoutAdultsRemindsEveryParentAndOthersAreLeftAlone() {
        val dentist =
            Event(
                "tand",
                "Tandläkare Ella",
                LocalDateTime(today, LocalTime(14, 0)),
                LocalDateTime(today, LocalTime(15, 0)),
                participantIds = listOf("ella"),
                reminders = listOf(30),
            )
        val saraMeeting =
            Event(
                "mote",
                "Saras möte",
                LocalDateTime(today, LocalTime(9, 0)),
                LocalDateTime(today, LocalTime(10, 0)),
                participantIds = listOf("sara"),
                reminders = listOf(15),
            )
        assertEquals(1, plan("johan", occ(dentist)).size)
        assertTrue(plan("johan", occ(saraMeeting)).isEmpty())
        assertTrue(plan("sara", occ(saraMeeting, EventSource.Device)).isEmpty(), "only family events are reminded here")
    }

    @Test
    fun pastAndFarAwayRemindersAreSkipped() {
        val morning = football.copy(
            start = LocalDateTime(today, LocalTime(7, 30)),
            end = LocalDateTime(today, LocalTime(8, 0)),
            dropOff = null,
            pickUp = null,
        )
        assertTrue(plan("sara", occ(morning)).isEmpty(), "the reminder time (06:30) has passed")
        val later = football.copy(
            start = LocalDateTime(LocalDate(2026, 10, 12), LocalTime(17, 30)),
            end = LocalDateTime(LocalDate(2026, 10, 12), LocalTime(18, 30)),
        )
        assertTrue(plan("sara", occ(later)).isEmpty(), "beyond 48 hours")
    }

    @Test
    fun tasksWithATimeWaitForQuietHoursToEnd() {
        val tasks = listOf(
            FamilyTask("t1", "Ring skolan", assigneeId = "sara", due = today, dueTime = LocalTime(15, 0)),
            FamilyTask("t2", "Sätt på diskmaskinen", assigneeId = "sara", due = today, dueTime = LocalTime(22, 0)),
            FamilyTask("t3", "Johans", assigneeId = "johan", due = today, dueTime = LocalTime(15, 0)),
            FamilyTask("t4", "Klar", assigneeId = "sara", due = today, dueTime = LocalTime(15, 0), done = true),
        )
        val p = plan("sara", tasks = tasks)
        assertEquals(listOf("task:t1:2026-10-08", "task:t2:2026-10-08"), p.map { it.key })
        assertEquals(at(LocalDate(2026, 10, 9), 6, 30), p[1].at, "22:00 is inside 21:00–06:30")
        val noQuiet = plan("sara", tasks = tasks, settings = ReminderSettings(quietHours = null))
        assertEquals(at(today, 22, 0), noQuiet[1].at)
    }

    @Test
    fun rotatingChoresRemindWhoeverIsUp() {
        val chore = FamilyTask(
            "sopor",
            "Ta ut soporna",
            kind = TaskKind.Chore,
            rrule = "FREQ=WEEKLY;BYDAY=TH",
            due = LocalDate(2026, 10, 1),
            dueTime = LocalTime(19, 0),
            rotation = ChoreRotation(listOf("johan", "sara"), 1, IsoWeek.of(LocalDate(2026, 10, 1))),
        )
        assertTrue(plan("johan", tasks = listOf(chore)).isEmpty(), "week 41 is Sara's turn")
        assertEquals(at(today, 19, 0), plan("sara", tasks = listOf(chore)).single().at)
    }

    @Test
    fun morningBriefIsTomorrowOnceTodaysHasPassed() {
        val p = plan("sara", settings = ReminderSettings(morningEnabled = true))
        assertEquals(listOf("morning:2026-10-09"), p.map { it.key })
        assertEquals(at(LocalDate(2026, 10, 9), 6, 45), p.single().at)
    }

    @Test
    fun quietHoursAcrossMidnight() {
        val q = QuietHours(LocalTime(21, 0), LocalTime(6, 30))
        assertEquals(at(LocalDate(2026, 10, 9), 6, 30), q.deferred(at(today, 23, 15), zone))
        assertEquals(at(today, 6, 30), q.deferred(at(today, 3, 0), zone))
        assertEquals(at(today, 12, 0), q.deferred(at(today, 12, 0), zone))
        // Across the DST change at night (25 Oct): still 06:30 local time.
        assertEquals(at(LocalDate(2026, 10, 25), 6, 30), q.deferred(at(LocalDate(2026, 10, 24), 22, 0), zone))
    }
}
