package se.pusslet.feature.family

import se.pusslet.core.entitlements.AllUnlockedEntitlements
import se.pusslet.core.entitlements.EntitlementSet
import se.pusslet.core.entitlements.FixedEntitlements
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.Household
import se.pusslet.feature.family.otherhome.OtherHomeError
import se.pusslet.feature.family.otherhome.OtherHomeViewModel
import se.pusslet.testing.TestGraph
import se.pusslet.testing.TestGraph.Companion.ELLA
import se.pusslet.testing.TestGraph.Companion.SIMON
import se.pusslet.testing.collectInBackground
import se.pusslet.testing.viewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OtherHomeViewModelTest {
    @Test
    fun suggestsTheChildrenWithACustodySchedule() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.family.saveCustody(
            CustodySchedule("c1", listOf(ELLA), listOf(Household("a", "Hos oss"), Household("b", "Hos pappa")), anchorDate = g.today),
        )
        val vm = OtherHomeViewModel(g.otherHome, g.family, g.auth, AllUnlockedEntitlements, g.clock)
        collectInBackground(vm.state)
        val s = vm.state.value
        assertFalse(s.available, "the test graph has no server: sharing explains why it is off")
        assertEquals(listOf("Ella", "Simon"), s.children.map { it.firstName })
        assertEquals(setOf(ELLA), s.selected)
        vm.toggleChild(SIMON)
        vm.toggleChild(ELLA)
        assertEquals(setOf(SIMON), vm.state.value.selected)
        assertTrue(vm.state.value.shareExisting)
        assertFalse(vm.state.value.shareDiets, "allergies are never shared by default")
    }

    @Test
    fun startingNeedsPremiumWhenItIsSwitchedOnButCodesAreChecked() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        val vm = OtherHomeViewModel(g.otherHome, g.family, g.auth, FixedEntitlements(EntitlementSet.Free), g.clock)
        collectInBackground(vm.state)
        vm.toggleChild(ELLA)
        assertFalse(vm.state.value.canStart)
        vm.start()
        assertEquals(null, vm.state.value.invite)
        assertFalse(vm.state.value.busy)
        vm.setCode("nope")
        vm.lookup()
        assertEquals(OtherHomeError.NotFound, vm.state.value.error)
    }
}
