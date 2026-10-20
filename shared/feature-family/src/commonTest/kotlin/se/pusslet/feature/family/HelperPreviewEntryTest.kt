package se.pusslet.feature.family

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import se.pusslet.core.model.Child
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.data.BackendConfig
import se.pusslet.data.BackendMode
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.session.Session
import se.pusslet.feature.family.invite.InviteViewModel
import se.pusslet.feature.family.member.MemberEditorViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Så ser Bengt appen" (UX plan Steg 3): offered only to parents, and only for another adult with the caregiver role – never
 * on your own row, a parent's or a child's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HelperPreviewEntryTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun world(me: String = SARA, role: Role = Role.Parent) =
        TestWorld(session = Session(familyId = "f", memberId = me, uid = "u-$me", role = role))

    /** Sara and Johan (parents), morfar Bengt (not joined yet) and Ella. */
    private suspend fun TestWorld.lind() {
        family.saveMember(Member(SARA, "Sara", Role.Parent, uid = "u-$SARA"))
        family.saveMember(Member(JOHAN, "Johan", Role.Parent))
        family.saveMember(Member(BENGT, "Bengt", Role.Caregiver))
        family.saveChild(Child("c-ella", "Ella", birthDate = LocalDate(2017, 3, 14)))
    }

    private fun kotlinx.coroutines.test.TestScope.familyState(world: TestWorld): FamilyUiState {
        val vm = FamilyViewModel(world.family, world.sessions, world.clock, world.zone)
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { vm.state.collect {} }
        return vm.state.value
    }

    @Test
    fun familyOffersThePreviewOnlyOnACaregiversRowToParents() = runTest {
        val parent = world()
        parent.lind()
        val state = familyState(parent)
        assertEquals(listOf(BENGT), state.members.filter(state::canPreviewHelper).map { it.id })

        val caregiver = world(BENGT, Role.Caregiver)
        caregiver.lind()
        val his = familyState(caregiver)
        assertEquals(3, his.members.size)
        assertTrue(his.members.none(his::canPreviewHelper), "Caregivers never see it, not even on their own row")
    }

    @Test
    fun theMemberEditorOffersItForAnotherCaregiverOnly() = runTest {
        val parent = world()
        parent.lind()

        fun canPreview(id: String?, w: TestWorld = parent) = MemberEditorViewModel(w.family, w.sessions).run {
            load(id)
            state.value.canPreviewHelper
        }

        assertTrue(canPreview(BENGT))
        assertFalse(canPreview(JOHAN), "Parents get the full app")
        assertFalse(canPreview(SARA), "Not on your own profile")
        assertFalse(canPreview(null), "A new adult is not saved yet")

        val caregiver = world(BENGT, Role.Caregiver)
        caregiver.lind()
        assertFalse(canPreview(BENGT, caregiver))
        assertFalse(canPreview(JOHAN, caregiver))
    }

    @Test
    fun theInviteOffersItOnceTheCaregiverRoleIsChosenForSomeoneKnown() = runTest {
        val parent = world()
        parent.lind()
        val config = BackendConfig(BackendMode.LocalOnly, seedDemoData = false, versionName = "test")

        val vm = InviteViewModel(parent.family, LocalOnlyAuthRepository(), config, parent.sessions)
        vm.load(BENGT)
        assertTrue(vm.state.value.canPreviewHelper)
        vm.setRole(Role.Parent)
        assertFalse(vm.state.value.canPreviewHelper)

        val newPerson = InviteViewModel(parent.family, LocalOnlyAuthRepository(), config, parent.sessions)
        newPerson.load(null)
        newPerson.setRole(Role.Caregiver)
        assertFalse(newPerson.state.value.canPreviewHelper, "Nobody to preview yet")

        val caregiver = world(BENGT, Role.Caregiver)
        caregiver.lind()
        val his = InviteViewModel(caregiver.family, LocalOnlyAuthRepository(), config, caregiver.sessions)
        his.load(BENGT)
        assertFalse(his.state.value.canPreviewHelper)
    }

    private companion object {
        const val SARA = "m-sara"
        const val JOHAN = "m-johan"
        const val BENGT = "m-bengt"
    }
}
