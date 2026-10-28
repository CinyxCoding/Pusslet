package se.pusslet.feature.family

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
import se.pusslet.core.model.Child
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.Household
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.core.premium.Entitlement
import se.pusslet.data.premium.AllUnlocked
import se.pusslet.data.premium.FixedEntitlements
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.session.Session
import se.pusslet.feature.family.otherhome.OtherHomeError
import se.pusslet.feature.family.otherhome.OtherHomeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Andra hemmet" (ADR-056) without a server: what the view offers and checks. */
@OptIn(ExperimentalCoroutinesApi::class)
class OtherHomeViewModelTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.collect(vm: OtherHomeViewModel) {
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { vm.state.collect {} }
    }

    private suspend fun world(): TestWorld {
        val w = TestWorld(session = Session(familyId = "f", memberId = "m-sara", uid = "u-sara", role = Role.Parent))
        w.family.saveMember(Member("m-sara", "Sara", Role.Parent, uid = "u-sara"))
        w.family.saveChild(Child(ELLA, "Ella"))
        w.family.saveChild(Child(SIMON, "Simon"))
        return w
    }

    @Test
    fun suggestsTheChildrenWithACustodySchedule() = runTest {
        val w = world()
        w.family.saveCustody(
            CustodySchedule(
                "c1",
                listOf(ELLA),
                listOf(Household("a", "Hos oss"), Household("b", "Hos pappa")),
                anchorDate = LocalDate(2026, 10, 12),
            ),
        )
        val vm = OtherHomeViewModel(w.otherHome, w.family, LocalOnlyAuthRepository(), AllUnlocked, w.clock)
        collect(vm)
        val s = vm.state.value
        assertFalse(s.available, "no server here: sharing explains why it is off")
        assertEquals(listOf("Ella", "Simon"), s.children.map { it.firstName })
        assertEquals(setOf(ELLA), s.selected)
        vm.toggleChild(SIMON)
        vm.toggleChild(ELLA)
        assertEquals(setOf(SIMON), vm.state.value.selected)
        assertTrue(vm.state.value.shareExisting)
        assertFalse(vm.state.value.shareDiets, "allergies are never shared by default")
    }

    @Test
    fun startingNeedsPremiumWhenItIsSwitchedOnButCodesAreChecked() = runTest {
        val w = world()
        val vm = OtherHomeViewModel(w.otherHome, w.family, LocalOnlyAuthRepository(), FixedEntitlements(Entitlement.Free), w.clock)
        collect(vm)
        vm.toggleChild(ELLA)
        assertFalse(vm.state.value.canStart)
        vm.start()
        assertEquals(null, vm.state.value.invite)
        assertFalse(vm.state.value.busy)
        vm.setCode("nope")
        vm.lookup()
        assertEquals(OtherHomeError.NotFound, vm.state.value.error)
    }

    private companion object {
        const val ELLA = "c-ella-0000000000000000"
        const val SIMON = "c-simon-000000000000000"
    }
}
