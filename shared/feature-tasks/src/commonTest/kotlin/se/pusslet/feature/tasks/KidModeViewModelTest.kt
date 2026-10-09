package se.pusslet.feature.tasks

import se.pusslet.core.model.Allowance
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.TaskKind
import se.pusslet.core.tasks.KidChoreStatus
import se.pusslet.core.time.isoWeek
import se.pusslet.data.usecase.KidChoreActions
import se.pusslet.feature.tasks.kid.AllowanceViewModel
import se.pusslet.feature.tasks.kid.KidModeViewModel
import se.pusslet.testing.TestGraph
import se.pusslet.testing.TestGraph.Companion.ELLA
import se.pusslet.testing.collectInBackground
import se.pusslet.testing.viewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KidModeViewModelTest {
    private suspend fun TestGraph.chore() = tasks.save(
        FamilyTask("duka", "Duka av", kind = TaskKind.Chore, assigneeId = ELLA, due = today, rrule = "FREQ=DAILY", stars = 2),
    )

    private fun kid(g: TestGraph) = KidModeViewModel(
        g.tasks,
        g.family,
        g.calendar,
        g.allowances,
        KidChoreActions(g.tasks, g.clock),
        g.clock,
        g.zone,
    )

    private fun tasks(g: TestGraph) = TasksViewModel(g.tasks, g.family, g.sessions, g.ids, g.clock, g.zone)

    @Test
    fun aChildTicksOffAndAParentApproves() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.chore()
        g.allowances.save(Allowance(ELLA, weekly = 20, perStar = 5))
        val kid = kid(g)
        kid.load(ELLA)
        collectInBackground(kid.state)
        assertEquals("Ella", kid.state.value.child?.name)
        val chore = kid.state.value.chores.single()
        assertEquals(KidChoreStatus.Todo, chore.status)

        kid.toggle(chore)
        assertEquals(KidChoreStatus.Waiting, kid.state.value.chores.single().status)
        assertEquals(2, kid.state.value.stars?.waiting)
        assertEquals(20, kid.state.value.stars?.amount, "waiting stars are not money yet")

        val parent = tasks(g)
        collectInBackground(parent.state)
        val pending = parent.state.value.approvals.single()
        assertEquals(ELLA, pending.childId)
        parent.approve(pending)
        assertEquals(KidChoreStatus.Approved, kid.state.value.chores.single().status)
        assertEquals(30, kid.state.value.stars?.amount, "20 kr + 2 stars × 5 kr")
        assertTrue(parent.state.value.approvals.isEmpty())
    }

    @Test
    fun notDoneYetPutsItBackAndAChildCanUndoBeforeApproval() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.chore()
        val kid = kid(g)
        kid.load(ELLA)
        collectInBackground(kid.state)
        kid.toggle(kid.state.value.chores.single())
        kid.toggle(kid.state.value.chores.single())
        assertEquals(KidChoreStatus.Todo, kid.state.value.chores.single().status, "undo before a parent looked")

        kid.toggle(kid.state.value.chores.single())
        val parent = tasks(g)
        collectInBackground(parent.state)
        parent.reject(parent.state.value.approvals.single())
        assertEquals(KidChoreStatus.Todo, kid.state.value.chores.single().status)
    }

    @Test
    fun pocketMoneyIsSetPerChildAndWeeksAreMarkedPaid() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        val vm = AllowanceViewModel(g.tasks, g.family, g.allowances, g.clock, g.zone)
        collectInBackground(vm.state)
        assertEquals(listOf("Ella", "Simon"), vm.state.value.children.map { it.child.name }.sorted())
        vm.setAmounts(ELLA, weekly = 25, perStar = null)
        vm.setAmounts(ELLA, weekly = null, perStar = 3)
        vm.setPaid(ELLA, g.today.isoWeek, true)
        val ella = vm.state.value.children.single { it.child.id == ELLA }
        assertEquals(25, ella.settings.weekly)
        assertEquals(3, ella.settings.perStar)
        assertTrue(g.today.isoWeek.key in ella.settings.paidWeeks)
        assertEquals(4, ella.weeks.size, "this week and three back")
        assertEquals(25, ella.weeks.first().amount)
    }
}
