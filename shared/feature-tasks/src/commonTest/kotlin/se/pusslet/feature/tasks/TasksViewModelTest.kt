package se.pusslet.feature.tasks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import se.pusslet.core.model.ChoreRotation
import se.pusslet.core.model.Completion
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Role
import se.pusslet.core.model.TaskKind
import se.pusslet.core.tasks.TaskOccurrence
import se.pusslet.core.time.IsoWeek
import se.pusslet.feature.tasks.TestWorld.Companion.BENGT
import se.pusslet.feature.tasks.TestWorld.Companion.JOHAN
import se.pusslet.feature.tasks.TestWorld.Companion.SARA
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Mer › Uppgifter on Tuesday 20 October 2026 (week 43). */
@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {
    private val saturday = LocalDate(2026, 10, 24)

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
    private val reading =
        FamilyTask("t-lasning", "Läsa 20 minuter", rrule = "FREQ=DAILY", due = LocalDate(2026, 9, 1), sharedWithCaregivers = true)
    private val parcel =
        FamilyTask("t-paket", "Hämta paket", assigneeId = BENGT, due = LocalDate(2026, 10, 22), sharedWithCaregivers = true)
    private val paid = FamilyTask(
        "t-hyra",
        "Betala hyran",
        assigneeId = SARA,
        done = true,
        doneBy = SARA,
        doneAt = Instant.parse("2026-10-19T17:00:00Z"),
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.observe(vm: TasksViewModel): TasksViewModel {
        val eager = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        eager.launch { vm.state.collect {} }
        return vm
    }

    private suspend fun TestWorld.save(vararg all: FamilyTask) = all.forEach { tasks.save(it) }

    @Test
    fun aParentSeesMineNobodysTheFamilysAndWhatIsDone() = runTest {
        val world = TestWorld()
        world.lind()
        world.save(vacuum, camp, fees, vaccine, reading, parcel, paid)
        val state = observe(world.list()).state.value

        assertTrue(state.canEdit)
        assertEquals(listOf("Dammsuga", "Boka vaccination för Simon"), state.mine.map { it.task.title })
        // Dammsuga is Sara's this week: "Dammsuga – din tur den här veckan".
        val turn = state.mine.first()
        assertEquals(0, turn.turnWeekOffset)
        assertTrue(turn.mine)
        assertEquals(saturday, turn.occurrence.date)
        assertEquals(listOf("Läsa 20 minuter", "Anmäl Ella till sportlovsläger"), state.unassigned.map { it.task.title })
        assertEquals(listOf("Hämta paket", "Betala fritidsavgiften"), state.family.map { it.task.title })
        assertEquals("Johan", state.family.last().assignee?.name)
        assertEquals(listOf("Betala hyran"), state.done.map { it.done.task.title })
        assertEquals("Sara", state.done.single().by?.name)
        assertEquals(LocalDate(2026, 10, 19), state.done.single().day)
    }

    @Test
    fun aCaregiverOnlySeesSharedTasksAndCannotEdit() = runTest {
        val world = TestWorld(role = Role.Caregiver, memberId = BENGT)
        world.lind()
        world.save(vacuum, camp, fees, vaccine, reading, parcel, paid)
        val state = observe(world.list()).state.value

        assertFalse(state.canEdit)
        assertEquals(listOf("Hämta paket"), state.mine.map { it.task.title })
        // No "Ingen ansvarig" for him: the shared task nobody has is under "Familjen".
        assertEquals(emptyList(), state.unassigned)
        assertEquals(listOf("Läsa 20 minuter"), state.family.map { it.task.title })
        assertEquals(emptyList(), state.done)
    }

    @Test
    fun aMissedChoreIsOverdueForWhoseTurnItWas() = runTest {
        // Week 42 was Sara's; last Saturday is not ticked.
        val world = TestWorld()
        world.lind()
        world.save(vacuum.copy(rotation = ChoreRotation(listOf(SARA, JOHAN), 1, IsoWeek(2026, 42))))
        val row = observe(world.list()).state.value.mine.single()
        assertTrue(row.overdue)
        assertEquals(LocalDate(2026, 10, 17), row.occurrence.date)
        assertEquals(-1, row.turnWeekOffset)
    }

    @Test
    fun tickingMarksOneOccurrenceAndUndoPutsItBack() = runTest {
        val world = TestWorld()
        world.lind()
        world.save(vacuum, fees)
        val vm = observe(world.list())
        val row = vm.state.value.mine.single()

        val undo = assertNotNull(vm.setDone(row.occurrence, true))
        val ticked = assertNotNull(world.tasks.get(vacuum.id))
        assertEquals(mapOf("2026-10-24" to Completion(SARA, world.clock.now())), ticked.completions)
        // Done: under "Klart", and the next Saturday (Johan's) is the open one under "Familjen".
        assertEquals(listOf("t-dammsug@2026-10-24"), vm.state.value.done.map { it.done.key })
        assertEquals(LocalDate(2026, 10, 31), vm.state.value.family.first { it.task.id == vacuum.id }.occurrence.date)

        vm.undo(undo)
        assertEquals(emptyMap(), assertNotNull(world.tasks.get(vacuum.id)).completions)
        assertEquals(listOf(vacuum.id), vm.state.value.mine.map { it.task.id })

        // A one-off as a whole, by whoever ticks it.
        val johans = vm.state.value.family.single { it.task.id == fees.id }
        val undoFees = assertNotNull(vm.setDone(johans.occurrence, true))
        val done = assertNotNull(world.tasks.get(fees.id))
        assertTrue(done.done)
        assertEquals(SARA, done.doneBy)
        vm.undo(undoFees)
        assertEquals(fees, world.tasks.get(fees.id))
    }

    @Test
    fun reopeningFromDoneCanBeUndone() = runTest {
        val world = TestWorld()
        world.lind()
        world.save(paid)
        val vm = observe(world.list())
        val row = vm.state.value.done.single()

        val undo = assertNotNull(vm.reopen(row))
        assertFalse(assertNotNull(world.tasks.get(paid.id)).done)
        assertEquals(listOf(paid.id), vm.state.value.mine.map { it.task.id })
        vm.undo(undo)
        assertEquals(paid, world.tasks.get(paid.id))
    }

    @Test
    fun aCaregiverTicksSharedTasksOnly() = runTest {
        val world = TestWorld(role = Role.Caregiver, memberId = BENGT)
        world.lind()
        world.save(parcel, vaccine)
        val vm = observe(world.list())

        assertNotNull(vm.setDone(vm.state.value.mine.single().occurrence, true))
        assertEquals(BENGT, assertNotNull(world.tasks.get(parcel.id)).doneBy)
        // Not shared (only old data after a role change could have it here): nothing is written.
        val private = TaskOccurrence(vaccine, null, SARA, done = false)
        assertNull(vm.setDone(private, true))
        assertEquals(vaccine, world.tasks.get(vaccine.id))
    }

    @Test
    fun aDeletedTaskCanBeBroughtBack() = runTest {
        val world = TestWorld()
        world.lind()
        world.save(camp)
        val list = observe(world.list())
        val editor = world.editor()
        editor.load(camp.id)
        editor.delete()

        assertNull(world.tasks.get(camp.id))
        assertEquals(camp, list.state.value.deleted)
        assertEquals(emptyList(), list.state.value.unassigned)

        list.restoreDeleted(camp)
        assertEquals(camp, world.tasks.get(camp.id))
        assertNull(list.state.value.deleted)
        assertEquals(listOf(camp.id), list.state.value.unassigned.map { it.task.id })
    }

    @Test
    fun aTaskOfSomeoneWhoLeftIsNobodys() = runTest {
        val world = TestWorld()
        world.lind()
        world.save(FamilyTask("t-gammal", "Laga cykeln", assigneeId = "m-old"))
        val state = observe(world.list()).state.value
        assertEquals(listOf("t-gammal"), state.unassigned.map { it.task.id })
        assertNull(state.unassigned.single().assignee)
    }
}
