package se.pusslet.core.tasks

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Completion
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.TaskKind
import se.pusslet.core.time.minusDays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class WorkloadTest {
    private val zone = TimeZone.of("Europe/Stockholm")
    private val today = LocalDate(2026, 10, 8)

    private fun school(date: LocalDate, drop: String?, pick: String?, source: EventSource = EventSource.Family) = EventOccurrence(
        event = Event(
            "e-$date",
            "Förskola",
            LocalDateTime(date, LocalTime(8, 0)),
            LocalDateTime(date, LocalTime(16, 0)),
            dropOff = drop?.let { Handoff(it) },
            pickUp = pick?.let { Handoff(it) },
        ),
        start = LocalDateTime(date, LocalTime(8, 0)),
        end = LocalDateTime(date, LocalTime(16, 0)),
        occurrenceDate = date,
        source = source,
    )

    @Test
    fun countsWhatEachAdultHandledInTheLastFourWeeks() {
        val tasks = listOf(
            FamilyTask("t1", "Köp present", done = true, doneBy = "sara", doneAt = Instant.parse("2026-10-01T10:00:00Z")),
            FamilyTask("t2", "Gammal", done = true, doneBy = "sara", doneAt = Instant.parse("2026-08-01T10:00:00Z")),
            FamilyTask(
                "c1",
                "Dammsuga",
                kind = TaskKind.Chore,
                rrule = "FREQ=WEEKLY",
                completions = mapOf(
                    "2026-09-19" to Completion("johan", null),
                    "2026-09-26" to Completion("johan", null),
                    "2026-10-03" to Completion("sara", null),
                    "2026-08-01" to Completion("johan", null),
                ),
            ),
            FamilyTask("t3", "Morfars", done = true, doneBy = "bengt", doneAt = Instant.parse("2026-10-02T10:00:00Z")),
        )
        val occurrences = listOf(
            school(today.minusDays(1), drop = "sara", pick = "johan"),
            school(today.minusDays(2), drop = "johan", pick = "johan"),
            school(today.plusDaysLocal(3), drop = "sara", pick = "sara"), // future: not counted
            school(today.minusDays(40), drop = "sara", pick = "sara"), // too old
            school(today.minusDays(3), drop = "sara", pick = "sara", source = EventSource.Device),
        )
        val r = Workload.report(listOf("sara", "johan"), tasks, occurrences, today, zone)
        assertEquals(LocalDate(2026, 9, 11), r.from)
        val sara = r.people.first { it.personId == "sara" }
        val johan = r.people.first { it.personId == "johan" }
        assertEquals(PersonLoad("sara", tasks = 1, chores = 1, dropOffs = 1, pickUps = 0), sara)
        assertEquals(PersonLoad("johan", tasks = 0, chores = 2, dropOffs = 1, pickUps = 2), johan)
        assertEquals(2, r.maxCount)
        assertEquals(listOf("sara", "johan"), r.people.map { it.personId }, "helpers are not part of the comparison")
    }

    @Test
    fun emptyWhenNothingIsRecorded() {
        assertTrue(Workload.report(listOf("sara"), emptyList(), emptyList(), today, zone).isEmpty)
    }

    private fun LocalDate.plusDaysLocal(n: Int) = LocalDate.fromEpochDays(toEpochDays() + n)

    @Test
    fun aRotatingChoreWithoutDueDateOnlyHappensOnItsDays() {
        val week = se.pusslet.core.time.IsoWeek.of(today)
        val chore = FamilyTask(
            "c",
            "Dammsuga",
            kind = TaskKind.Chore,
            rrule = "FREQ=WEEKLY;BYDAY=SA",
            rotation = se.pusslet.core.model.ChoreRotation(listOf("sara", "johan"), 1, week),
        )
        val dates = TaskScheduler.occurrences(chore, week.monday, week.monday.plusDaysLocal(13)).map { it.date }
        assertEquals(listOf(LocalDate(2026, 10, 10), LocalDate(2026, 10, 17)), dates)
        assertEquals(
            listOf("sara", "johan"),
            TaskScheduler.occurrences(chore, week.monday, week.monday.plusDaysLocal(13)).map {
                it.assigneeId
            },
        )
    }
}
