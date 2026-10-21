package se.pusslet.feature.tasks.editor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.ChoreRotation
import se.pusslet.core.model.Completion
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.TaskCategory
import se.pusslet.core.model.TaskKind
import se.pusslet.core.tasks.TaskRepeat
import se.pusslet.core.tasks.TaskScheduler
import se.pusslet.core.time.IsoWeek
import se.pusslet.feature.tasks.TestWorld
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

/** The task editor on Tuesday 20 October 2026 (week 43). */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskEditorViewModelTest {
    private val friday = LocalDate(2026, 10, 23)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun TestWorld.only(): FamilyTask = tasks.tasks.first().single()

    @Test
    fun aNewTaskSavesWhoWhenRepeatChecklistAndNote() = runTest {
        val world = TestWorld()
        world.lind()
        val vm = world.editor()
        vm.load(null)
        // Parents first, then caregivers (as FamilyRepository.people).
        assertEquals(listOf("Johan", "Sara", "Bengt"), vm.state.value.adults.map { it.name })
        assertFalse(vm.state.value.hasChanges)

        vm.setTitle("Anmäl Ella till sportlovsläger")
        vm.setAssignee(JOHAN)
        vm.setDue(friday)
        vm.setDueTime(LocalTime(18, 0))
        vm.setRepeat(TaskRepeat.Biweekly)
        vm.addChecklistItem("Hitta lägret")
        vm.addChecklistItem(" Betala ")
        vm.addChecklistItem("   ")
        vm.moveChecklistItem(1, -1)
        vm.toggleChecklistItem(assertNotNull(vm.state.value.draft).checklist.last().id)
        vm.edit { it.copy(note = " Sista dag 1 nov ", sharedWithCaregivers = true) }
        assertTrue(vm.state.value.hasChanges)
        vm.save()

        assertTrue(vm.state.value.done)
        val task = world.only()
        assertEquals("Anmäl Ella till sportlovsläger", task.title)
        assertEquals(TaskKind.Task, task.kind)
        assertEquals(TaskCategory.General, task.category)
        assertEquals(JOHAN, task.assigneeId)
        assertEquals(friday, task.due)
        assertEquals(LocalTime(18, 0), task.dueTime)
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=FR", task.rrule)
        assertEquals(listOf("Betala" to false, "Hitta lägret" to true), task.checklist.map { it.text to it.done })
        assertEquals("Sista dag 1 nov", task.note)
        assertTrue(task.sharedWithCaregivers)
        assertEquals(SARA, task.createdBy)
        assertNull(task.rotation)
    }

    @Test
    fun aTitleIsNeeded() = runTest {
        val world = TestWorld()
        world.lind()
        val vm = world.editor()
        vm.load(null)
        vm.save()
        assertEquals(setOf(TaskEditorError.TitleRequired), vm.state.value.errors)
        assertFalse(vm.state.value.done)
        assertEquals(emptyList(), world.tasks.tasks.first())
        vm.setTitle("Dammsuga")
        assertEquals(emptySet(), vm.state.value.errors)
    }

    @Test
    fun aRotatingChoreStartsThisWeekInTheChosenOrder() = runTest {
        val world = TestWorld()
        world.lind()
        val vm = world.editor()
        vm.load(null)
        vm.setTitle("Dammsuga")
        vm.setKind(TaskKind.Chore)
        // The parents are offered, in order; switching on keeps them.
        assertEquals(listOf(JOHAN, SARA), vm.state.value.draft?.rotationMembers)
        vm.setRotationOn(true)
        vm.moveRotationMember(0, 1)
        vm.toggleRotationMember(BENGT)
        vm.setEveryWeeks(2)
        vm.setDue(LocalDate(2026, 10, 24))
        vm.setRepeat(TaskRepeat.Weekly)
        vm.save()

        val task = world.only()
        assertEquals(TaskKind.Chore, task.kind)
        assertEquals(TaskCategory.Household, task.category)
        assertNull(task.assigneeId)
        assertEquals(ChoreRotation(listOf(SARA, JOHAN, BENGT), 2, IsoWeek(2026, 43)), task.rotation)
        assertEquals("FREQ=WEEKLY;BYDAY=SA", task.rrule)
        // "Vecka 43: Sara, vecka 44: Sara, vecka 45: Johan …"
        assertEquals(listOf(SARA, SARA, JOHAN, JOHAN, BENGT), TaskScheduler.turns(task, IsoWeek(2026, 43), 5).map { it.second })
    }

    @Test
    fun swapWeekChangesThisWeekAndNextAndIsSaved() = runTest {
        val world = TestWorld()
        world.lind()
        val chore = FamilyTask(
            "t-dammsug",
            "Dammsuga",
            kind = TaskKind.Chore,
            rrule = "FREQ=WEEKLY;BYDAY=SA",
            due = LocalDate(2026, 10, 24),
            rotation = ChoreRotation(listOf(SARA, JOHAN), 1, IsoWeek(2026, 43)),
            completions = mapOf("2026-10-17" to Completion(JOHAN, null)),
        )
        world.tasks.save(chore)
        val vm = world.editor()
        vm.load(chore.id)
        assertFalse(vm.state.value.hasChanges)
        val preview = { TaskScheduler.turns(vm.taskOf(assertNotNull(vm.state.value.draft)), IsoWeek(2026, 43), 2).map { it.second } }
        assertEquals(listOf(SARA, JOHAN), preview())

        vm.swapWeek()
        assertEquals(listOf(JOHAN, SARA), preview())
        assertTrue(vm.state.value.hasChanges)
        vm.save()

        val saved = assertNotNull(world.tasks.get(chore.id))
        assertEquals(mapOf("2026-W43" to JOHAN, "2026-W44" to SARA), saved.turnOverrides)
        assertEquals(JOHAN, TaskScheduler.assigneeFor(saved, LocalDate(2026, 10, 24)))
        // Everything else stays: the rotation, its start and what was already done.
        assertEquals(chore.rotation, saved.rotation)
        assertEquals(chore.completions, saved.completions)
    }

    @Test
    fun changingTheRotationStartsItThisWeekAndDropsSwaps() = runTest {
        val world = TestWorld()
        world.lind()
        val chore = FamilyTask(
            "t-dammsug",
            "Dammsuga",
            kind = TaskKind.Chore,
            rrule = "FREQ=WEEKLY;BYDAY=SA",
            due = LocalDate(2026, 9, 5),
            rotation = ChoreRotation(listOf(SARA, JOHAN), 1, IsoWeek(2026, 36)),
            turnOverrides = mapOf("2026-W43" to JOHAN, "2026-W44" to SARA),
        )
        world.tasks.save(chore)
        val vm = world.editor()
        vm.load(chore.id)
        vm.moveRotationMember(1, -1)
        vm.save()

        val saved = assertNotNull(world.tasks.get(chore.id))
        assertEquals(ChoreRotation(listOf(JOHAN, SARA), 1, IsoWeek(2026, 43)), saved.rotation)
        assertEquals(emptyMap(), saved.turnOverrides)
    }

    @Test
    fun anOldRecurringChoreWithoutADateShowsItsCurrentDayAndKeepsItsRule() = runTest {
        val world = TestWorld()
        world.lind()
        // As in the example family: no due date, the series from the rotation's first Monday.
        val bins = FamilyTask(
            "t-sopor",
            "Ta ut soporna",
            kind = TaskKind.Chore,
            rrule = "FREQ=WEEKLY;BYDAY=MO,TH",
            rotation = ChoreRotation(listOf(JOHAN, SARA), 1, IsoWeek(2026, 43)),
        )
        val vacuum = FamilyTask(
            "t-dammsug",
            "Dammsuga",
            kind = TaskKind.Chore,
            rrule = "FREQ=WEEKLY;BYDAY=SA",
            rotation = ChoreRotation(listOf(SARA, JOHAN), 1, IsoWeek(2026, 43)),
        )
        world.tasks.save(bins)
        world.tasks.save(vacuum)

        val vm = world.editor()
        vm.load(vacuum.id)
        val draft = assertNotNull(vm.state.value.draft)
        assertEquals(LocalDate(2026, 10, 24), draft.due)
        assertEquals(TaskRepeat.Weekly, draft.repeat)
        assertTrue(draft.rotationOn)

        val other = world.editor()
        other.load(bins.id)
        // Monday's collection was not done: that is the current one. "Mån och tors" is kept as it is.
        assertEquals(LocalDate(2026, 10, 19), other.state.value.draft?.due)
        assertEquals(TaskRepeat.Custom, other.state.value.draft?.repeat)
        other.setTitle("Ta ut sopor och återvinning")
        other.save()
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TH", world.tasks.get(bins.id)?.rrule)
    }

    @Test
    fun withoutADateNothingRepeats() = runTest {
        val world = TestWorld()
        world.lind()
        val vm = world.editor()
        vm.load(null)
        vm.setTitle("Läsa 20 minuter")
        // Choosing a repeat starts it today.
        vm.setRepeat(TaskRepeat.Daily)
        assertEquals(LocalDate(2026, 10, 20), vm.state.value.draft?.due)
        vm.setDueTime(LocalTime(19, 0))
        vm.setDue(null)
        val draft = assertNotNull(vm.state.value.draft)
        assertEquals(TaskRepeat.None, draft.repeat)
        assertNull(draft.dueTime)
        vm.save()
        val task = world.only()
        assertNull(task.rrule)
        assertNull(task.due)
        assertNull(task.dueTime)
    }

    @Test
    fun aRotationOnlyListsPeopleStillInTheFamily() = runTest {
        val world = TestWorld()
        world.lind()
        val chore = FamilyTask(
            "t",
            "Byta lakan",
            kind = TaskKind.Chore,
            rotation = ChoreRotation(listOf("m-old", SARA), 1, IsoWeek(2026, 43)),
        )
        world.tasks.save(chore)
        val vm = world.editor()
        vm.load(chore.id)
        assertEquals(listOf(SARA), vm.state.value.draft?.rotationMembers)
    }

    @Test
    fun aChoreWithoutTurnsHasItsOwnAssignee() = runTest {
        val world = TestWorld()
        world.lind()
        val vm = world.editor()
        vm.load(null)
        vm.setTitle("Rensa förrådet")
        vm.setKind(TaskKind.Chore)
        vm.setAssignee(SARA)
        vm.save()
        val task = world.only()
        assertEquals(SARA, task.assigneeId)
        assertNull(task.rotation)
        assertEquals(emptyMap(), task.turnOverrides)
    }

    @Test
    fun turningAChoreBackIntoATaskDropsTheRotation() = runTest {
        val world = TestWorld()
        world.lind()
        val chore = FamilyTask(
            "t",
            "Dammsuga",
            kind = TaskKind.Chore,
            category = TaskCategory.Household,
            rotation = ChoreRotation(listOf(SARA, JOHAN), 1, IsoWeek(2026, 43)),
            turnOverrides = mapOf("2026-W43" to JOHAN),
        )
        world.tasks.save(chore)
        val vm = world.editor()
        vm.load(chore.id)
        vm.setKind(TaskKind.Task)
        vm.setAssignee(JOHAN)
        vm.save()
        val task = assertNotNull(world.tasks.get(chore.id))
        assertEquals(TaskKind.Task, task.kind)
        assertEquals(TaskCategory.General, task.category)
        assertNull(task.rotation)
        assertEquals(emptyMap(), task.turnOverrides)
        assertEquals(JOHAN, task.assigneeId)
    }
}
