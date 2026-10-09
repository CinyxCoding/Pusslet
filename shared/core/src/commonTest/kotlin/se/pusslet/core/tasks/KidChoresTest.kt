package se.pusslet.core.tasks

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import se.pusslet.core.model.Allowance
import se.pusslet.core.model.ChoreRotation
import se.pusslet.core.model.Completion
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.TaskKind
import se.pusslet.core.time.isoWeek
import kotlin.test.Test
import kotlin.test.assertEquals

class KidChoresTest {
    private val zone = TimeZone.of("Europe/Stockholm")
    private val thursday = LocalDate(2026, 10, 8)
    private val monday = LocalDate(2026, 10, 5)

    private fun at(d: LocalDate) = LocalDateTime(d, LocalTime(18, 0)).toInstant(zone)

    private val dishes = FamilyTask(
        "dishes",
        "Duka av",
        kind = TaskKind.Chore,
        assigneeId = "ella",
        due = monday,
        rrule = "FREQ=DAILY",
        stars = 1,
    )
    private val room = FamilyTask("room", "Städa rummet", assigneeId = "ella", due = thursday.plusDaysCompat(2), stars = 3)

    private fun LocalDate.plusDaysCompat(n: Int) = LocalDate.fromEpochDays(toEpochDays() + n)

    @Test
    fun theChildSeesTodayAndWhatIsLeftThisWeek() {
        val done = dishes.copy(
            completions = mapOf(
                "2026-10-05" to Completion("ella", at(monday)),
                "2026-10-06" to Completion("ella", at(LocalDate(2026, 10, 6)), approved = false),
            ),
        )
        val chores = KidChores.forChild("ella", listOf(done, room), thursday)
        // Wednesday was missed and can be caught up; Monday (approved) and Tuesday (waiting) are not listed again.
        assertEquals(
            listOf(
                "dishes@2026-10-07" to KidChoreStatus.Todo,
                "dishes@2026-10-08" to KidChoreStatus.Todo,
                "room@2026-10-10" to KidChoreStatus.Todo,
            ),
            chores.map { "${it.occurrence.task.id}@${it.occurrence.date}" to it.status },
        )
        assertEquals(emptyList(), KidChores.forChild("simon", listOf(done, room), thursday))
    }

    @Test
    fun aChildsTicksWaitForAParentAndOnlyApprovedStarsBecomeMoney() {
        val tasks = listOf(
            dishes.copy(
                completions = mapOf(
                    "2026-10-05" to Completion("ella", at(monday)),
                    "2026-10-06" to Completion("ella", at(LocalDate(2026, 10, 6))),
                    "2026-10-07" to Completion("ella", at(LocalDate(2026, 10, 7)), approved = false),
                    // Last week's turn does not count for this week.
                    "2026-10-04" to Completion("ella", at(LocalDate(2026, 10, 4))),
                ),
            ),
            room.copy(done = true, doneAt = at(thursday), doneBy = "ella", approved = false),
        )
        val pending = KidChores.pending(tasks, setOf("ella", "simon"), zone)
        assertEquals(listOf("dishes:2026-10-07", "room:once"), pending.map { "${it.task.id}:${it.key}" })
        val week = KidChores.week("ella", tasks, thursday.isoWeek, Allowance("ella", weekly = 20, perStar = 5), zone)
        assertEquals(2, week.approved)
        assertEquals(4, week.waiting)
        assertEquals(30, week.amount, "20 kr + 2 approved stars × 5 kr")
        assertEquals(null, KidChores.week("ella", tasks, thursday.isoWeek, null, zone).amount, "no veckopeng set up")
    }

    @Test
    fun aRotatingChoreGivesStarsToWhoeverHadTheTurn() {
        val trash = FamilyTask(
            "trash",
            "Bära ut soporna",
            kind = TaskKind.Chore,
            rrule = "FREQ=WEEKLY;BYDAY=TH",
            stars = 2,
            rotation = ChoreRotation(listOf("ella", "simon"), 1, monday.isoWeek),
            completions = mapOf("2026-10-08" to Completion("sara", at(thursday))),
        )
        assertEquals(2, KidChores.week("ella", listOf(trash), thursday.isoWeek, null, zone).approved, "a parent ticked it for Ella")
        assertEquals(0, KidChores.week("simon", listOf(trash), thursday.isoWeek, null, zone).approved)
    }
}
