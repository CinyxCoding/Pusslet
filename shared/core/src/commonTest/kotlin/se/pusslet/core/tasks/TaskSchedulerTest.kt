package se.pusslet.core.tasks

import kotlinx.datetime.LocalDate
import se.pusslet.core.model.ChoreRotation
import se.pusslet.core.model.Completion
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Role
import se.pusslet.core.model.TaskKind
import se.pusslet.core.time.IsoWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** "Today" is Tuesday 20 October 2026 (week 43) unless a test says otherwise. 2026 has 53 ISO weeks. */
class TaskSchedulerTest {
    private val tuesday = LocalDate(2026, 10, 20)
    private val saturdayBefore = LocalDate(2026, 10, 17)
    private val saturday = LocalDate(2026, 10, 24)

    private fun chore(
        rrule: String? = "FREQ=WEEKLY;BYDAY=SA",
        members: List<String> = listOf(SARA, JOHAN),
        every: Int = 1,
        anchor: IsoWeek = IsoWeek(2026, 43),
        due: LocalDate? = null,
    ) = FamilyTask(
        "t-dammsug",
        "Dammsuga",
        kind = TaskKind.Chore,
        rrule = rrule,
        due = due,
        rotation = ChoreRotation(members, every, anchor),
    )

    @Test
    fun rotationAlternatesAcrossWeek53AndTheNewYear() {
        val c = chore(anchor = IsoWeek(2026, 52))
        assertEquals(SARA, TaskScheduler.assigneeFor(c, LocalDate(2026, 12, 21)))
        // Week 53 runs from Monday 28 December 2026 to Sunday 3 January 2027.
        assertEquals(JOHAN, TaskScheduler.assigneeFor(c, LocalDate(2026, 12, 28)))
        assertEquals(JOHAN, TaskScheduler.assigneeFor(c, LocalDate(2026, 12, 31)))
        assertEquals(JOHAN, TaskScheduler.assigneeFor(c, LocalDate(2027, 1, 3)))
        assertEquals(SARA, TaskScheduler.assigneeFor(c, LocalDate(2027, 1, 4)))
        // Before the anchor the rotation runs backwards the same way.
        assertEquals(JOHAN, TaskScheduler.assigneeFor(c, LocalDate(2026, 12, 14)))
    }

    @Test
    fun everyOtherWeekKeepsTwoWeeksPerPersonOverTheNewYear() {
        val c = chore(every = 2, anchor = IsoWeek(2026, 52))
        val turns = TaskScheduler.turns(c, IsoWeek(2026, 52), 4).map { it.first.key to it.second }
        assertEquals(listOf("2026-W52" to SARA, "2026-W53" to SARA, "2027-W01" to JOHAN, "2027-W02" to JOHAN), turns)
    }

    @Test
    fun withoutRotationTheAssigneeStands() {
        val task = FamilyTask("t", "Betala fritidsavgiften", assigneeId = JOHAN)
        assertEquals(JOHAN, TaskScheduler.assigneeFor(task, tuesday))
        assertEquals(JOHAN, TaskScheduler.assigneeFor(chore(members = emptyList()).copy(assigneeId = JOHAN), tuesday))
    }

    @Test
    fun swapWeeksChangesTheTwoTurnsAndSwappingBackClearsIt() {
        val c = chore()
        val swapped = TaskScheduler.swapWeeks(c, IsoWeek(2026, 43))
        assertEquals(mapOf("2026-W43" to JOHAN, "2026-W44" to SARA), swapped.turnOverrides)
        assertEquals(JOHAN, TaskScheduler.assigneeFor(swapped, tuesday))
        assertEquals(SARA, TaskScheduler.assigneeFor(swapped, LocalDate(2026, 10, 27)))
        // Week 45 is untouched.
        assertEquals(SARA, TaskScheduler.assigneeFor(swapped, LocalDate(2026, 11, 3)))
        val back = TaskScheduler.swapWeeks(swapped, IsoWeek(2026, 43))
        assertEquals(emptyMap(), back.turnOverrides)
    }

    @Test
    fun swapWeeksOverTheNewYearUsesBothWeekYears() {
        val c = chore(anchor = IsoWeek(2026, 53))
        val swapped = TaskScheduler.swapWeeks(c, IsoWeek(2026, 53))
        assertEquals(mapOf("2026-W53" to JOHAN, "2027-W01" to SARA), swapped.turnOverrides)
        assertEquals(JOHAN, TaskScheduler.assigneeFor(swapped, LocalDate(2027, 1, 2)))
    }

    @Test
    fun swapWeeksDoesNothingWhenItIsTheSamePersonBothWeeks() {
        val alone = chore(members = listOf(SARA))
        assertSame(alone, TaskScheduler.swapWeeks(alone, IsoWeek(2026, 43)))
        val biweekly = chore(every = 2)
        assertSame(biweekly, TaskScheduler.swapWeeks(biweekly, IsoWeek(2026, 43)))
        val plain = FamilyTask("t", "Handla", assigneeId = SARA)
        assertSame(plain, TaskScheduler.swapWeeks(plain, IsoWeek(2026, 43)))
    }

    @Test
    fun overridesOnlyApplyToRotatingChores() {
        val task = FamilyTask("t", "Handla", assigneeId = SARA, turnOverrides = mapOf("2026-W43" to JOHAN))
        assertEquals(SARA, TaskScheduler.assigneeFor(task, tuesday))
    }

    @Test
    fun aOneOffChoreIsDoneByWhoeverHasTheTurnInItsWeek() {
        val once = chore(rrule = null, due = LocalDate(2026, 10, 29))
        assertEquals(JOHAN, TaskScheduler.occurrences(once, tuesday, tuesday.plusDaysOf(14), tuesday).single().assigneeId)
        // Undated: this week's turn.
        val undated = chore(rrule = null)
        assertEquals(SARA, TaskScheduler.occurrences(undated, tuesday, tuesday, today = tuesday).single().assigneeId)
        assertEquals(SARA, TaskScheduler.current(undated, tuesday)?.assigneeId)
        assertEquals(JOHAN, TaskScheduler.current(undated, LocalDate(2026, 10, 27))?.assigneeId)
        // "Byt vecka" moves it too.
        val swapped = TaskScheduler.swapWeeks(undated, IsoWeek(2026, 43))
        assertEquals(JOHAN, TaskScheduler.current(swapped, tuesday)?.assigneeId)
    }

    @Test
    fun aStartTheRuleDoesNotMatchIsNoOccurrence() {
        // Rotation from Monday of week 43, every Saturday: Monday 19 October is not a Saturday.
        val c = chore()
        assertEquals(listOf(saturday), TaskScheduler.occurrences(c, LocalDate(2026, 10, 19), LocalDate(2026, 10, 25)).map { it.date })
        // With a due date the series starts there, as the editor sets it.
        val dated = chore(due = LocalDate(2026, 10, 24))
        assertEquals(listOf(saturday), TaskScheduler.occurrences(dated, LocalDate(2026, 10, 19), LocalDate(2026, 10, 25)).map { it.date })
        // "Mån och tors" starts on its Monday, which is a real occurrence.
        val bins = chore(rrule = "FREQ=WEEKLY;BYDAY=MO,TH")
        assertEquals(
            listOf(LocalDate(2026, 10, 19), LocalDate(2026, 10, 22)),
            TaskScheduler.occurrences(bins, LocalDate(2026, 10, 19), LocalDate(2026, 10, 25)).map { it.date },
        )
    }

    @Test
    fun parentsSeeTasksNobodyHasCaregiversOnlySharedOnes() {
        val camp = FamilyTask("t-lager", "Anmäl Ella till sportlovsläger", due = LocalDate(2026, 10, 22))
        val reading = FamilyTask("t-lasning", "Läsa 20 minuter", rrule = "FREQ=DAILY", sharedWithCaregivers = true)
        val johans = FamilyTask("t-fritids", "Betala fritidsavgiften", assigneeId = JOHAN, due = tuesday)
        val mine = FamilyTask("t-vaccin", "Boka vaccination för Simon", assigneeId = SARA)
        val tasks = listOf(camp, reading, johans, mine)
        val horizon = tuesday.plusDaysOf(3)

        // Idag: only what is mine.
        assertEquals(listOf("t-vaccin"), TaskScheduler.openFor(SARA, tasks, tuesday, horizon).map { it.task.id })
        assertEquals(
            setOf("t-lager", "t-lasning", "t-vaccin"),
            TaskScheduler.openFor(SARA, tasks, tuesday, horizon, Role.Parent).map { it.task.id }.toSet(),
        )
        assertEquals(
            listOf("t-lasning"),
            TaskScheduler.openFor(BENGT, tasks, tuesday, horizon, Role.Caregiver).map { it.task.id }.distinct(),
        )
    }

    @Test
    fun aMissedOccurrenceIsOverdueUntilDone() {
        val c = chore(anchor = IsoWeek(2026, 42))
        // Last Saturday was Sara's turn (week 42) and nobody ticked it.
        val open = TaskScheduler.openFor(SARA, listOf(c), tuesday, tuesday.plusDaysOf(3))
        assertEquals(listOf(saturdayBefore), open.map { it.date })
        assertTrue(TaskScheduler.isOverdue(open.single(), tuesday))
        // Johan does not get it: the turn was Sara's.
        assertEquals(emptyList(), TaskScheduler.openFor(JOHAN, listOf(c), tuesday, tuesday.plusDaysOf(3)))

        val done = c.copy(completions = mapOf(saturdayBefore.toString() to Completion(SARA, null)))
        assertEquals(emptyList(), TaskScheduler.openFor(SARA, listOf(done), tuesday, tuesday.plusDaysOf(3)))
    }

    @Test
    fun theLookBackIsBounded() {
        // Every other Sunday from 11 October: the 11th is more than seven days before Tuesday the 20th.
        val sheets = chore(rrule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=SU", due = LocalDate(2026, 10, 11), members = listOf(SARA))
        assertEquals(emptyList(), TaskScheduler.open(sheets, tuesday, tuesday))
        assertEquals(
            listOf(LocalDate(2026, 10, 11)),
            TaskScheduler.open(sheets, LocalDate(2026, 10, 18), LocalDate(2026, 10, 18)).map {
                it.date
            },
        )
    }

    @Test
    fun aNewerOccurrenceReplacesAMissedOne() {
        val reading = FamilyTask("t", "Läsa 20 minuter", assigneeId = SARA, rrule = "FREQ=DAILY", due = LocalDate(2026, 10, 1))
        val open = TaskScheduler.open(reading, tuesday, tuesday)
        assertEquals(listOf(tuesday), open.map { it.date })
        assertFalse(TaskScheduler.isOverdue(open.single(), tuesday))
        // Today's is done: yesterday's missed one does not come back.
        val doneToday = reading.copy(completions = mapOf(tuesday.toString() to Completion(SARA, null)))
        assertEquals(emptyList(), TaskScheduler.open(doneToday, tuesday, tuesday))
    }

    @Test
    fun openIncludesWhatComesUpToTheHorizon() {
        val bins = chore(rrule = "FREQ=WEEKLY;BYDAY=MO,TH", members = listOf(JOHAN))
        assertEquals(
            listOf(LocalDate(2026, 10, 19), LocalDate(2026, 10, 22)),
            TaskScheduler.open(bins, tuesday, tuesday.plusDaysOf(3)).map { it.date },
        )
    }

    @Test
    fun weeklyChoresKeepTheirDayOverDaylightSavingTime() {
        // Summer time ends on Sunday 25 October 2026 (and starts on Sunday 28 March 2027).
        val c = chore(rrule = "FREQ=WEEKLY;BYDAY=SU", anchor = IsoWeek(2026, 42))
        val autumn = TaskScheduler.occurrences(c, LocalDate(2026, 10, 12), LocalDate(2026, 11, 2))
        assertEquals(listOf(LocalDate(2026, 10, 18), LocalDate(2026, 10, 25), LocalDate(2026, 11, 1)), autumn.map { it.date })
        assertEquals(listOf(SARA, JOHAN, SARA), autumn.map { it.assigneeId })
        val spring = TaskScheduler.occurrences(c, LocalDate(2027, 3, 22), LocalDate(2027, 4, 4))
        assertEquals(listOf(LocalDate(2027, 3, 28), LocalDate(2027, 4, 4)), spring.map { it.date })
        val ticked = c.copy(completions = mapOf("2026-10-25" to Completion(JOHAN, null)))
        assertTrue(TaskScheduler.occurrences(ticked, LocalDate(2026, 10, 25), LocalDate(2026, 10, 25)).single().done)
    }

    @Test
    fun currentIsTheOpenOneOrTheNext() {
        val c = chore(anchor = IsoWeek(2026, 42), due = LocalDate(2026, 10, 17))
        assertEquals(saturdayBefore, TaskScheduler.current(c, tuesday)?.date)
        val done = c.copy(completions = mapOf(saturdayBefore.toString() to Completion(SARA, null)))
        val next = TaskScheduler.current(done, tuesday)
        assertEquals(saturday, next?.date)
        // Week 43 is Johan's.
        assertEquals(JOHAN, next?.assigneeId)
        // A monthly one is found even when it is weeks away.
        val bills = FamilyTask("t", "Betala räkningar", rrule = "FREQ=MONTHLY;BYMONTHDAY=27", due = LocalDate(2026, 9, 27))
        assertEquals(LocalDate(2026, 10, 27), TaskScheduler.current(bills, tuesday)?.date)
        // A one-off that is done has nothing left; a series that ended neither.
        assertNull(TaskScheduler.current(FamilyTask("t", "Klart", done = true), tuesday))
        val ended = bills.copy(rrule = "FREQ=MONTHLY;BYMONTHDAY=27;COUNT=1")
        assertNull(TaskScheduler.current(ended, tuesday))
    }

    @Test
    fun aRuleThatCannotBeReadIsAOneOff() {
        val odd = FamilyTask("t", "Konstig", assigneeId = SARA, rrule = "NOT A RULE", due = tuesday)
        assertFalse(TaskScheduler.isRecurring(odd))
        assertEquals(listOf(tuesday), TaskScheduler.openFor(SARA, listOf(odd), tuesday, tuesday).map { it.date })
    }

    private fun LocalDate.plusDaysOf(n: Int) = LocalDate.fromEpochDays(toEpochDays() + n)

    private companion object {
        const val SARA = "m-sara"
        const val JOHAN = "m-johan"
        const val BENGT = "m-bengt"
    }
}
