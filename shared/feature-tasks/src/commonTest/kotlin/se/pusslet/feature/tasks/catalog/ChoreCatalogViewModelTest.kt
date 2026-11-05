package se.pusslet.feature.tasks.catalog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Member
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.core.model.TaskKind
import se.pusslet.core.tasks.CatalogChore
import se.pusslet.core.tasks.ChoreOwner
import se.pusslet.feature.tasks.TasksViewModel
import se.pusslet.feature.tasks.TestWorld
import se.pusslet.feature.tasks.TestWorld.Companion.BENGT
import se.pusslet.feature.tasks.TestWorld.Companion.JOHAN
import se.pusslet.feature.tasks.TestWorld.Companion.SARA
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Vanliga sysslor" (ADR-065) on Tuesday 20 October 2026, and the "Ångra" on the list it returns to. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChoreCatalogViewModelTest {
    private val titles = CatalogChore.entries.associateWith { it.name } + (CatalogChore.Vacuum to "Dammsuga")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.observe(vm: ChoreCatalogViewModel): ChoreCatalogViewModel {
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { vm.state.collect {} }
        return vm
    }

    private fun TestScope.observeList(vm: TasksViewModel): TasksViewModel {
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { vm.state.collect {} }
        return vm
    }

    private suspend fun TestWorld.saved(): List<FamilyTask> = tasks.tasks.first()

    @Test
    fun twoParentsTakeTurnsFromTheStart() = runTest {
        val world = TestWorld()
        world.lind()
        val state = observe(world.catalog()).state.value

        assertTrue(state.canEdit)
        assertEquals(setOf(SARA, JOHAN), state.parents.map { it.id }.toSet(), "Not morfar Bengt or Ella")
        assertEquals(ChoreOwner.TakeTurns(state.parents.map { it.id }), state.owner)
    }

    @Test
    fun oneParentHasThemFromTheStart() = runTest {
        val world = TestWorld()
        world.family.saveMember(Member(SARA, "Sara", Role.Parent, MemberColor.Sage))

        assertEquals(ChoreOwner.Person(SARA), observe(world.catalog()).state.value.owner)
    }

    @Test
    fun whatIsPickedBecomesChoresAndTheListOffersToUndoThem() = runTest {
        val world = TestWorld()
        world.lind()
        val vm = observe(world.catalog())
        val list = observeList(world.list())

        vm.toggle(CatalogChore.CleanOven)
        vm.toggle(CatalogChore.Vacuum)
        vm.toggle(CatalogChore.Dust)
        vm.toggle(CatalogChore.Dust)
        assertEquals(2, vm.state.value.toAdd(titles).size)
        vm.add(titles)

        assertTrue(vm.state.value.done, "Closes the screen")
        // In the order of the catalogue, whatever order they were ticked in.
        val added = list.state.value.added
        assertEquals(listOf("Dammsuga", "CleanOven"), added.map { it.title })
        assertEquals(setOf("Dammsuga", "CleanOven"), world.saved().map { it.title }.toSet())
        assertTrue(world.saved().all { it.kind == TaskKind.Chore && it.createdBy == SARA })
        assertEquals(LocalDate(2026, 10, 24), added.first().due)

        list.removeAdded(added)

        assertEquals(emptyList(), world.saved(), "Ångra takes them all away")
        assertEquals(emptyList(), list.state.value.added)
    }

    @Test
    fun theReceiptIsShownOnce() = runTest {
        val world = TestWorld()
        world.lind()
        val vm = observe(world.catalog())
        val list = observeList(world.list())
        vm.toggle(CatalogChore.Vacuum)
        vm.add(titles)

        list.addedShown(list.state.value.added)

        assertEquals(emptyList(), list.state.value.added)
        assertEquals(listOf("Dammsuga"), world.saved().map { it.title }, "No Ångra any more, and the chore stays")
    }

    @Test
    fun theChosenOwnerGetsThem() = runTest {
        val world = TestWorld()
        world.lind()
        val vm = observe(world.catalog())
        vm.setOwner(ChoreOwner.Person(JOHAN))
        vm.toggle(CatalogChore.Vacuum)

        vm.add(titles)

        val task = world.saved().single()
        assertEquals(JOHAN, task.assigneeId)
        assertNull(task.rotation)
    }

    @Test
    fun takingTurnsIsBetweenTheParents() = runTest {
        val world = TestWorld()
        world.lind()
        val vm = observe(world.catalog())
        vm.toggle(CatalogChore.Vacuum)

        vm.add(titles)

        assertEquals(setOf(SARA, JOHAN), world.saved().single().rotation?.memberIds?.toSet())
    }

    /** "dammsuga" typed by hand is the same chore: it is not offered, and not added a second time. */
    @Test
    fun whatTheFamilyAlreadyHasIsNotAddedAgain() = runTest {
        val world = TestWorld()
        world.lind()
        world.tasks.save(FamilyTask("t-egen", " dammsuga "))
        val vm = observe(world.catalog())
        assertTrue(vm.state.value.exists("Dammsuga"))
        assertFalse(vm.state.value.exists("CleanOven"))

        vm.toggle(CatalogChore.Vacuum)
        assertEquals(emptyList(), vm.state.value.toAdd(titles))
        vm.add(titles)
        assertFalse(vm.state.value.done, "Nothing to add")

        vm.toggle(CatalogChore.CleanOven)
        vm.add(titles)

        assertEquals(setOf(" dammsuga ", "CleanOven"), world.saved().map { it.title }.toSet())
    }

    @Test
    fun aSecondTapAddsNothingMore() = runTest {
        val world = TestWorld()
        world.lind()
        val vm = observe(world.catalog())
        vm.toggle(CatalogChore.Vacuum)

        vm.add(titles)
        vm.add(titles)

        assertEquals(1, world.saved().size)
    }

    @Test
    fun nothingPickedAddsNothing() = runTest {
        val world = TestWorld()
        world.lind()
        val vm = observe(world.catalog())

        vm.add(titles)

        assertFalse(vm.state.value.done)
        assertEquals(emptyList(), world.saved())
    }

    /** A caregiver never gets here, and the rules would refuse it: the screen writes nothing, and the list offers nothing. */
    @Test
    fun aCaregiverAddsNothing() = runTest {
        val world = TestWorld(role = Role.Caregiver, memberId = BENGT)
        world.lind()
        val vm = observe(world.catalog())
        val list = observeList(world.list())
        assertFalse(vm.state.value.canEdit)

        vm.toggle(CatalogChore.Vacuum)
        vm.add(titles)

        assertEquals(emptyList(), world.saved())
        assertEquals(emptyList(), list.state.value.added)
    }
}
