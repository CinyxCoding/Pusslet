package se.pusslet.core.tasks

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.ChoreRotation
import se.pusslet.core.model.Completion
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Role
import se.pusslet.core.model.TaskKind
import se.pusslet.core.time.IsoWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The task list's sections on Tuesday 20 October 2026 (week 43), in Stockholm. */
class TaskBoardTest {
    private val zone = TimeZone.of("Europe/Stockholm")
    private val tuesday = LocalDate(2026, 10, 20)
    private val people = setOf(SARA, JOHAN, BENGT)

    private val vacuum = FamilyTask(
        "t-dammsug",
        "Dammsuga",
        kind = TaskKind.Chore,
        rrule = "FREQ=WEEKLY;BYDAY=SA",
        rotation = ChoreRotation(listOf(SARA, JOHAN), 1, IsoWeek(2026, 43)),
    )
    private val camp = FamilyTask("t-lager", "Anmäl Ella till sportlovsläger", due = LocalDate(2026, 10, 23))
    private val fees = FamilyTask("t-fritids", "Betala fritidsavgiften", assigneeId = JOHAN, due = LocalDate(2026, 10, 26))
    private val vaccine = FamilyTask("t-vaccin", "Boka vaccination för Simon", assigneeId = SARA)
    private val late = FamilyTask("t-blankett", "Lämna blanketten", assigneeId = SARA, due = LocalDate(2026, 10, 16))
    private val reading =
        FamilyTask("t-lasning", "Läsa 20 minuter", rrule = "FREQ=DAILY", due = LocalDate(2026, 9, 1), sharedWithCaregivers = true)
    private val all = listOf(vacuum, camp, fees, vaccine, late, reading)

    private fun board(tasks: List<FamilyTask> = all, me: String? = SARA, role: Role = Role.Parent) =
        TaskBoardPlanner.build(tasks, me, role, people, tuesday, zone)

    @Test
    fun aParentSeesMineNobodysAndTheFamilys() {
        val b = board()
        // Overdue first, then by day, undated last. Dammsuga is Sara's this week (Saturday).
        assertEquals(listOf("t-blankett", "t-dammsug", "t-vaccin"), b.mine.map { it.task.id })
        assertEquals(listOf("t-lasning", "t-lager"), b.unassigned.map { it.task.id })
        assertEquals(listOf("t-fritids"), b.others.map { it.task.id })
        assertTrue(TaskScheduler.isOverdue(b.mine.first(), tuesday))
    }

    @Test
    fun aCaregiverOnlySeesWhatIsShared() {
        val b = board(me = BENGT, role = Role.Caregiver)
        assertEquals(emptyList(), b.mine)
        assertEquals(listOf("t-lasning"), b.unassigned.map { it.task.id })
        assertEquals(emptyList(), b.others)
    }

    @Test
    fun theTurnDecidesTheSection() {
        // Next Tuesday, with Sara's Saturday done: the next one is Johan's.
        val doneSaturday = vacuum.copy(completions = mapOf("2026-10-24" to Completion(SARA, null)))
        val nextWeek = TaskBoardPlanner.build(listOf(doneSaturday), SARA, Role.Parent, people, LocalDate(2026, 10, 27), zone)
        assertEquals(listOf("t-dammsug"), nextWeek.others.map { it.task.id })
        assertEquals(LocalDate(2026, 10, 31), nextWeek.others.single().date)
        // Not done: it stays Sara's, overdue.
        val missed = TaskBoardPlanner.build(listOf(vacuum), SARA, Role.Parent, people, LocalDate(2026, 10, 27), zone)
        assertEquals(listOf(LocalDate(2026, 10, 24)), missed.mine.map { it.date })
        val swapped = TaskScheduler.swapWeeks(vacuum, IsoWeek(2026, 43))
        assertEquals(listOf("t-dammsug"), board(listOf(swapped)).others.map { it.task.id })
    }

    @Test
    fun aTaskOfSomeoneWhoLeftIsNobodys() {
        val left = FamilyTask("t-gammal", "Laga cykeln", assigneeId = "m-old")
        assertEquals(listOf("t-gammal"), board(listOf(left)).unassigned.map { it.task.id })
        val rotation = vacuum.copy(rotation = ChoreRotation(listOf("m-old", JOHAN), 1, IsoWeek(2026, 43)))
        assertEquals(listOf("t-dammsug"), board(listOf(rotation)).unassigned.map { it.task.id })
    }

    @Test
    fun theSameDayIsOrderedByTimeThenTitle() {
        val a = FamilyTask("a", "Ringa skolan", assigneeId = SARA, due = tuesday, dueTime = LocalTime(15, 0))
        val b = FamilyTask("b", "Boka tid", assigneeId = SARA, due = tuesday, dueTime = LocalTime(9, 0))
        val c = FamilyTask("c", "Avboka", assigneeId = SARA, due = tuesday)
        assertEquals(listOf("c", "b", "a"), board(listOf(a, b, c)).mine.map { it.task.id })
    }

    @Test
    fun doneShowsTheLastWeekNewestFirst() {
        val ticked = Instant.parse("2026-10-19T17:00:00Z")
        val vacuumed = vacuum.copy(
            completions = mapOf(
                "2026-10-17" to Completion(JOHAN, Instant.parse("2026-10-17T09:00:00Z")),
                // Too old for "Klart".
                "2026-10-10" to Completion(SARA, Instant.parse("2026-10-10T09:00:00Z")),
            ),
        )
        val paid = fees.copy(done = true, doneBy = JOHAN, doneAt = ticked)
        val oldDone = camp.copy(done = true, doneBy = SARA, doneAt = Instant.parse("2026-10-01T10:00:00Z"))
        val b = board(listOf(vacuumed, paid, oldDone))
        assertEquals(listOf("t-fritids@once", "t-dammsug@2026-10-17"), b.done.map { it.key })
        assertEquals(listOf(JOHAN, JOHAN), b.done.map { it.doneBy })
        // Done one-offs are not open any more.
        assertEquals(emptyList(), b.others)
    }

    @Test
    fun anEmptyListIsEmpty() {
        assertTrue(board(emptyList()).isEmpty)
    }

    private companion object {
        const val SARA = "m-sara"
        const val JOHAN = "m-johan"
        const val BENGT = "m-bengt"
    }
}
