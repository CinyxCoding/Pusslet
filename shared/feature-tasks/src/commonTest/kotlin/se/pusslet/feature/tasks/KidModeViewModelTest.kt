package se.pusslet.feature.tasks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.todayIn
import se.pusslet.core.model.Allowance
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.TaskKind
import se.pusslet.core.tasks.KidChoreStatus
import se.pusslet.core.time.isoWeek
import se.pusslet.data.usecase.AllowanceRepository
import se.pusslet.data.usecase.KidChoreActions
import se.pusslet.feature.tasks.TestWorld.Companion.ELLA
import se.pusslet.feature.tasks.kid.AllowanceViewModel
import se.pusslet.feature.tasks.kid.KidModeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Barnläge, stars and veckopeng (ADR-055). "Today" comes from [TestWorld]. */
@OptIn(ExperimentalCoroutinesApi::class)
class KidModeViewModelTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.collect(flow: StateFlow<*>) {
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { flow.collect {} }
    }

    private class Kids(val w: TestWorld) {
        val today: LocalDate = w.clock.todayIn(w.zone)
        val allowances = AllowanceRepository(w.docs, w.clock)
        val actions = KidChoreActions(w.tasks, w.clock)

        fun kid() = KidModeViewModel(w.tasks, w.family, w.calendar, allowances, actions, w.clock, w.zone)

        fun parent() = AllowanceViewModel(w.tasks, w.family, allowances, actions, w.clock, w.zone)

        suspend fun chore() = w.tasks.save(
            FamilyTask("duka", "Duka av", kind = TaskKind.Chore, assigneeId = ELLA, due = today, rrule = "FREQ=DAILY", stars = 2),
        )
    }

    @Test
    fun aChildTicksOffAndAParentApproves() = runTest {
        val k = Kids(TestWorld())
        k.w.lind()
        k.chore()
        k.allowances.save(Allowance(ELLA, weekly = 20, perStar = 5))
        val kid = k.kid()
        kid.load(ELLA)
        collect(kid.state)
        assertEquals("Ella", kid.state.value.child?.name)
        val chore = kid.state.value.chores.single()
        assertEquals(KidChoreStatus.Todo, chore.status)

        kid.toggle(chore)
        assertEquals(KidChoreStatus.Waiting, kid.state.value.chores.single().status)
        assertEquals(2, kid.state.value.stars?.waiting)
        assertEquals(20, kid.state.value.stars?.amount, "waiting stars are not money yet")

        val parent = k.parent()
        collect(parent.state)
        val pending = parent.state.value.approvals.single()
        assertEquals(ELLA, pending.childId)
        parent.approve(pending)
        assertEquals(KidChoreStatus.Approved, kid.state.value.chores.single().status)
        assertEquals(30, kid.state.value.stars?.amount, "20 kr + 2 stars × 5 kr")
        assertTrue(parent.state.value.approvals.isEmpty())
    }

    @Test
    fun notDoneYetPutsItBackAndAChildCanUndoBeforeApproval() = runTest {
        val k = Kids(TestWorld())
        k.w.lind()
        k.chore()
        val kid = k.kid()
        kid.load(ELLA)
        collect(kid.state)
        kid.toggle(kid.state.value.chores.single())
        kid.toggle(kid.state.value.chores.single())
        assertEquals(KidChoreStatus.Todo, kid.state.value.chores.single().status, "undo before a parent looked")

        kid.toggle(kid.state.value.chores.single())
        val parent = k.parent()
        collect(parent.state)
        parent.reject(parent.state.value.approvals.single())
        assertEquals(KidChoreStatus.Todo, kid.state.value.chores.single().status)
    }

    @Test
    fun pocketMoneyIsSetPerChildAndWeeksAreMarkedPaid() = runTest {
        val k = Kids(TestWorld())
        k.w.lind()
        val vm = k.parent()
        collect(vm.state)
        assertEquals(listOf("Ella"), vm.state.value.children.map { it.child.name })
        vm.setAmounts(ELLA, weekly = 25, perStar = null)
        vm.setAmounts(ELLA, weekly = null, perStar = 3)
        vm.setPaid(ELLA, k.today.isoWeek, true)
        val ella = vm.state.value.children.single()
        assertEquals(25, ella.settings.weekly)
        assertEquals(3, ella.settings.perStar)
        assertTrue(k.today.isoWeek.key in ella.settings.paidWeeks)
        assertEquals(4, ella.weeks.size, "this week and three back")
        assertEquals(25, ella.weeks.first().amount)
    }
}
