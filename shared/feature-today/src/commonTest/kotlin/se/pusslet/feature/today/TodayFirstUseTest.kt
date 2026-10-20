package se.pusslet.feature.today

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
import se.pusslet.core.model.Child
import se.pusslet.core.model.Family
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.core.model.SchoolRef
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.today.SetupItem
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.sync.Collections
import se.pusslet.data.usecase.HintIds
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/** First use of Idag (UX plan 4.4, 4.7, 4.8): "Kom igång", the hint about + and "Börja på riktigt". */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayFirstUseTest {
    private val ella = Child("c-ella", "Ella", birthDate = LocalDate(2017, 3, 14))

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(phone: TestPhone, auth: AuthRepository = LocalOnlyAuthRepository()): TodayViewModel {
        val vm = phone.todayViewModel(auth)
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { vm.state.collect {} }
        return vm
    }

    private fun TestScope.viewModel(world: TestWorld, auth: AuthRepository = LocalOnlyAuthRepository()) =
        viewModel(TestPhone(world, backgroundScope), auth)

    /** Sara made the family yesterday, with Ella (9, no school yet) and Johan (no account yet). */
    private suspend fun TestWorld.newFamily(): TestWorld {
        docs.put(Collections.Family, "f", Family("f", "Familjen Berg", createdAt = clock.now() - 1.days), Family.serializer())
        family.saveMember(Member("m-sara", "Sara", Role.Parent, uid = "u"))
        family.saveMember(Member("m-johan", "Johan", Role.Parent))
        family.saveChild(ella)
        return this
    }

    @Test
    fun aNewFamilyGetsStartedAndTheStepsTickThemselvesOff() = runTest {
        val world = TestWorld().newFamily()
        val vm = viewModel(world, InvitesAvailable)

        val setup = assertNotNull(vm.state.value.setup)
        assertEquals(listOf("school-c-ella", "invite-m-johan", "activity"), setup.next.map { it.key })
        assertEquals(0 to 5, setup.done to setup.total)
        assertTrue(setup.hasMore, "Visa alla")

        world.family.saveChild(ella.copy(school = SchoolRef("Vasaskolan")))
        world.shopping.saveList(ShoppingList("l-week", "Veckohandling"))
        world.shopping.saveItem(ShoppingItem(id = "i-1", listId = "l-week", name = "Mjölk"))

        val after = assertNotNull(vm.state.value.setup)
        assertEquals(2 to 5, after.done to after.total, "2 av 5 klart")
        assertEquals(listOf("invite-m-johan", "activity", "dinners"), after.next.map { it.key })
    }

    @Test
    fun eachStepOpensItsPlace() = runTest {
        val world = TestWorld().newFamily()
        val setup = assertNotNull(viewModel(world, InvitesAvailable).state.value.setup)
        val opened = mutableListOf<String>()
        val actions = TodayActions(
            onOpenChild = { opened += "child:$it" },
            onInvite = { opened += "invite:$it" },
            onQuickAdd = { opened += "quick add" },
            onPlanDinner = { opened += "meals:$it" },
            onOpenShopping = { opened += "shopping" },
        )

        setup.items.forEach { actions.openSetup(it, LocalDate(2026, 10, 13)) }

        assertEquals(listOf("child:c-ella", "invite:m-johan", "quick add", "meals:2026-10-13", "shopping"), opened)
    }

    @Test
    fun hideStaysHiddenOnThisPhone() = runTest {
        val world = TestWorld().newFamily()
        val vm = viewModel(world)
        assertNotNull(vm.state.value.setup)

        vm.hideSetup()

        assertNull(vm.state.value.setup)
        assertEquals(setOf("f"), world.prefs.get(Prefs.SetupHidden))
        // After a restart the stored choice still holds.
        assertNull(viewModel(world).state.value.setup)
    }

    @Test
    fun neverForCaregiversOrTheExampleFamily() = runTest {
        assertNull(viewModel(TestWorld(Role.Caregiver, memberId = "m-johan").newFamily()).state.value.setup)
        assertNull(viewModel(TestWorld(demo = true).newFamily()).state.value.setup)
    }

    @Test
    fun theHintAboutPlusWaitsForGetStartedAndShowsOnce() = runTest {
        val world = TestWorld().newFamily()
        val vm = viewModel(world)
        assertFalse(vm.state.value.showAddHint, "Kom igång already leads to + (Lägg till en aktivitet)")

        vm.hideSetup()
        assertTrue(vm.state.value.showAddHint)

        vm.dismissAddHint()
        assertFalse(vm.state.value.showAddHint)
        assertEquals(setOf(HintIds.TODAY_ADD), world.prefs.get(Prefs.SeenHints))
        assertFalse(viewModel(world).state.value.showAddHint, "it does not come back after a restart")
    }

    @Test
    fun aCaregiverGetsNoHint() = runTest {
        assertFalse(viewModel(TestWorld(Role.Caregiver)).state.value.showAddHint)
        assertTrue(viewModel(TestWorld(Role.Parent)).state.value.showAddHint, "a family that is not new gets the hint at once")
    }

    @Test
    fun startForRealIsOnlyOfferedToTheExampleFamily() = runTest {
        assertFalse(viewModel(TestWorld()).state.value.demo, "a real family never sees Börja på riktigt")
        assertTrue(viewModel(TestWorld(demo = true)).state.value.demo)
    }

    @Test
    fun confirmingStartForRealLeavesTheExampleFamilyInProduction() = runTest {
        val world = TestWorld(demo = true)
        world.family.saveChild(ella)
        world.prefs.set(Prefs.OnboardingDone, true)
        val phone = TestPhone(world, backgroundScope, ProductionBuild).store()
        val vm = viewModel(phone)

        vm.leaveDemo()

        assertFalse(phone.sessions.session.value.hasFamily, "the example family is gone from this phone")
        assertFalse(world.prefs.get(Prefs.OnboardingDone), "the shell shows the welcome screen")
        assertTrue(world.local.listFamily("f").isEmpty())
        assertFalse(vm.state.value.leaveDemoFailed)
        assertFalse(vm.state.value.leavingDemo)
    }

    @Test
    fun aRealFamilyIsNeverRemovedFromIdag() = runTest {
        // Even in a demo build, where "Visa första start igen" is allowed, Idag only ever leaves the example family.
        val world = TestWorld()
        world.family.saveChild(ella)
        world.prefs.set(Prefs.OnboardingDone, true)
        val phone = TestPhone(world, backgroundScope, DemoBuild).store()
        val vm = viewModel(phone)

        vm.leaveDemo()

        assertTrue(phone.sessions.session.value.hasFamily)
        assertTrue(world.prefs.get(Prefs.OnboardingDone))
        assertEquals(listOf("c-ella"), world.family.children.first().map { it.id })
    }

    @Test
    fun aFailedStartForRealSaysSoQuietly() = runTest {
        val world = TestWorld(demo = true)
        world.prefs.set(Prefs.OnboardingDone, true)
        // The phone's stored session is not the example family, so the coordinator refuses (it would be a real family).
        val phone = TestPhone(world, backgroundScope, ProductionBuild).store(world.sessions.session.value.copy(demo = false))
        val vm = viewModel(phone)

        vm.leaveDemo()

        assertTrue(vm.state.value.leaveDemoFailed)
        assertTrue(phone.sessions.session.value.hasFamily)
        assertTrue(world.prefs.get(Prefs.OnboardingDone))
    }

    @Test
    fun checklistStepsUseTheChildAndMemberIds() = runTest {
        val world = TestWorld().newFamily()
        val items = assertNotNull(viewModel(world, InvitesAvailable).state.value.setup).items
        assertEquals("c-ella", assertIs<SetupItem.School>(items.first()).child.id)
        assertEquals("Johan", items.filterIsInstance<SetupItem.Invite>().single().member.displayName)
    }
}
