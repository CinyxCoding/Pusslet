package se.pusslet.core.tasks

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.TaskCategory
import se.pusslet.core.model.TaskKind
import se.pusslet.core.time.plusDays
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** "Vanliga sysslor" (ADR-065) on Tuesday 20 October 2026 (week 43), unless a test says otherwise. */
class ChoreCatalogTest {
    private val tuesday = LocalDate(2026, 10, 20)
    private val saturday = LocalDate(2026, 10, 24)
    private val at = Instant.parse("2026-10-20T06:00:00Z")
    private val sara = "m-sara"
    private val johan = "m-johan"

    private fun tasks(vararg chores: CatalogChore, owner: ChoreOwner = ChoreOwner.Nobody, today: LocalDate = tuesday): List<FamilyTask> =
        ChoreCatalog.tasks(chores.map { PickedChore("t-${it.name}", it.name, it) }, owner, today, sara, at)

    @Test
    fun aPickedChoreIsAnOrdinaryChore() {
        val task = tasks(CatalogChore.Vacuum).single()

        assertEquals("t-Vacuum", task.id)
        assertEquals("Vacuum", task.title)
        assertEquals(TaskKind.Chore, task.kind)
        assertEquals(TaskCategory.Household, task.category)
        assertEquals(saturday, task.due)
        assertEquals("FREQ=WEEKLY;BYDAY=SA", task.rrule)
        assertEquals(sara, task.createdBy)
        assertEquals(at, task.createdAt)
    }

    @Test
    fun theChildrensChoresAreInTheChildrensCategory() {
        assertEquals(TaskCategory.Kids, tasks(CatalogChore.EmptyBackpacks).single().category)
    }

    /** Every day of a week around a Saturday: the first day is never in the past, and it is the Saturday at the latest. */
    @Test
    fun theFirstDayIsTheComingSaturday() {
        assertEquals(saturday, ChoreCatalog.comingSaturday(LocalDate(2026, 10, 19)))
        assertEquals(saturday, ChoreCatalog.comingSaturday(LocalDate(2026, 10, 23)))
        assertEquals(saturday, ChoreCatalog.comingSaturday(saturday))
        assertEquals(LocalDate(2026, 10, 31), ChoreCatalog.comingSaturday(LocalDate(2026, 10, 25)))
    }

    /** Whatever is picked, the list does not start out with anything late, and each chore has a next time. */
    @Test
    fun nothingStartsOutLate() {
        for (offset in 0..6) {
            val today = LocalDate(2026, 10, 19 + offset)
            tasks(*CatalogChore.entries.toTypedArray(), today = today).forEach { task ->
                val now = assertNotNull(TaskScheduler.current(task, today), task.title)
                assertEquals(task.due, now.date, task.title)
                assertFalse(TaskScheduler.isOverdue(now, today), task.title)
                assertEquals(DayOfWeek.SATURDAY, task.due?.dayOfWeek, task.title)
            }
        }
    }

    @Test
    fun choresWithTheSameRepeatShareOutTheSaturdays() {
        val monthly = tasks(
            CatalogChore.CleanFridge,
            CatalogChore.CleanOven,
            CatalogChore.CleanMicrowave,
            CatalogChore.CleanDishwasher,
            CatalogChore.DescaleCoffeeMaker,
        )
        // 31 October is not a day every month has, so that one moves on a week.
        assertEquals(
            listOf(saturday, LocalDate(2026, 11, 7), LocalDate(2026, 11, 7), LocalDate(2026, 11, 14), saturday),
            monthly.map { it.due },
        )

        val everyOtherWeek = tasks(CatalogChore.MopFloors, CatalogChore.Dust, CatalogChore.ChangeSheets)
        assertEquals(listOf(saturday, LocalDate(2026, 10, 31), saturday), everyOtherWeek.map { it.due })

        val weekly = tasks(CatalogChore.Vacuum, CatalogChore.CleanToilet)
        assertEquals(listOf(saturday, saturday), weekly.map { it.due })
    }

    /** A rule for the 29th, 30th or 31st would skip months. */
    @Test
    fun aMonthlyChoreKeepsADayEveryMonthHas() {
        val endOfOctober = LocalDate(2026, 10, 27)
        tasks(
            CatalogChore.CleanFridge,
            CatalogChore.CleanOven,
            CatalogChore.CleanMicrowave,
            CatalogChore.CleanDishwasher,
            today = endOfOctober,
        ).forEach { task ->
            val due = assertNotNull(task.due)
            assertTrue(due.day <= 28, "${task.title} on $due")
            assertEquals(DayOfWeek.SATURDAY, due.dayOfWeek)
        }
    }

    @Test
    fun oneOwnerGetsThemAll() {
        val picked = tasks(CatalogChore.Vacuum, CatalogChore.CleanOven, owner = ChoreOwner.Person(johan))

        assertEquals(listOf(johan, johan), picked.map { it.assigneeId })
        assertTrue(picked.all { it.rotation == null })
    }

    @Test
    fun nobodyMeansNoOneYet() {
        val task = tasks(CatalogChore.Vacuum).single()

        assertNull(task.assigneeId)
        assertNull(task.rotation)
    }

    @Test
    fun takingTurnsDoesNotGiveOnePersonEverythingTheSameWeek() {
        val weekly = tasks(
            CatalogChore.Vacuum,
            CatalogChore.CleanToilet,
            CatalogChore.TakeOutTrash,
            CatalogChore.DoLaundry,
            owner = ChoreOwner.TakeTurns(listOf(sara, johan)),
        )

        for (days in listOf(0, 7, 14)) {
            val who = weekly.map { TaskScheduler.assigneeFor(it, saturday.plusDays(days)) }
            assertEquals(2, who.count { it == sara }, "$days days on: $who")
            assertEquals(2, who.count { it == johan }, "$days days on: $who")
        }
        assertTrue(weekly.all { it.assigneeId == null })
    }

    /** The turn follows the chore's own repeat, or a chore done every other week would stay with one person. */
    @Test
    fun everyOtherWeekTheTurnChangesEachTime() {
        val task = tasks(CatalogChore.MopFloors, owner = ChoreOwner.TakeTurns(listOf(sara, johan))).single()

        val occurrences = TaskScheduler.occurrences(task, saturday, LocalDate(2026, 12, 31), today = tuesday)
        val who = occurrences.take(4).map { it.assigneeId }

        assertEquals(listOf(sara, johan, sara, johan), who)
    }

    @Test
    fun monthByMonthTheTurnIsShared() {
        val task = tasks(CatalogChore.CleanOven, owner = ChoreOwner.TakeTurns(listOf(sara, johan))).single()

        val occurrences = TaskScheduler.occurrences(task, saturday, LocalDate(2027, 10, 23), today = tuesday)
        val who = occurrences.map { it.assigneeId }

        assertEquals(12, who.size, "A year of months: $who")
        assertEquals(listOf(sara, johan, sara, johan), who.take(4))
        assertTrue(abs(who.count { it == sara } - who.count { it == johan }) <= 2, "$who")
    }

    @Test
    fun noPeopleToTakeTurnsIsNobody() {
        val task = tasks(CatalogChore.Vacuum, owner = ChoreOwner.TakeTurns(emptyList())).single()

        assertNull(task.rotation)
        assertNull(task.assigneeId)
    }

    @Test
    fun aTitleIsStoredWithoutStraySpaces() {
        val task = ChoreCatalog.tasks(listOf(PickedChore("t", " Dammsuga ", CatalogChore.Vacuum)), ChoreOwner.Nobody, tuesday, sara, at)

        assertEquals("Dammsuga", task.single().title)
    }
}
