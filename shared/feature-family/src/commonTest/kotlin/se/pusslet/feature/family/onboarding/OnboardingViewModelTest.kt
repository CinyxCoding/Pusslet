package se.pusslet.feature.family.onboarding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.Session
import se.pusslet.feature.family.TestWorld
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(world: TestWorld) =
        OnboardingViewModel(world.family, world.prefs, world.seed, world.sessions, world.ids, world.clock, world.zone)

    /** Welcome → Family ("Sara") → Children. */
    private fun OnboardingViewModel.toChildren() {
        next("Familjen Sara")
        setMyName("Sara")
        next("Familjen Sara")
    }

    /** Types [names] into the children step: the first one into the row the step starts with. */
    private fun OnboardingViewModel.typeChildren(vararg names: String) {
        names.forEachIndexed { i, name ->
            if (i > 0) addChild()
            val id = state.value.children.last().id
            updateChild(id) { it.copy(name = name) }
        }
    }

    /** Welcome → Family ("Sara") → Children ([names]) → Place (skipped) → Done. */
    private fun OnboardingViewModel.runThrough(vararg names: String) {
        toChildren()
        typeChildren(*names)
        next("Familjen Sara")
        next("Familjen Sara")
    }

    @Test
    fun firstChildIsNotTerracotta() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)

        vm.runThrough("Ella", "Simon")

        assertEquals(OnboardingStep.Done, vm.state.value.step)
        val colors = world.family.children.first().associate { it.firstName to it.color }
        assertEquals(MemberColor.Ocean, colors["Ella"])
        assertEquals(MemberColor.Plum, colors["Simon"])
        assertNotEquals(MemberColor.Terracotta, colors["Ella"])
    }

    @Test
    fun replayingFirstStartShowsTheWelcomeScreenAgain() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)
        vm.runThrough("Ella")
        vm.showMyDay {}
        assertTrue(world.prefs.get(Prefs.OnboardingDone))

        // "Visa första start igen" in Settings resets the flag while this view model is still alive.
        world.prefs.set(Prefs.OnboardingDone, false)

        assertEquals(OnboardingUiState(), vm.state.value)
    }

    @Test
    fun aRunInProgressIsKept() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)
        vm.next("Familjen Sara")
        vm.setMyName("Sara")

        world.prefs.set(Prefs.OnboardingDone, false)

        assertEquals(OnboardingStep.Family, vm.state.value.step)
        assertEquals("Sara", vm.state.value.myName)
    }

    @Test
    fun onlyTheNameIsRequired() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)
        vm.next("Familjen Sara")
        assertFalse(vm.state.value.canContinue)

        vm.setMyName("Sara")
        vm.next("Familjen Sara")
        vm.next("Familjen Sara")
        vm.next("Familjen Sara")

        assertEquals(OnboardingStep.Done, vm.state.value.step)
        assertEquals("Familjen Sara", world.family.family.first()?.name)
        assertEquals(listOf("Sara"), world.family.members.first().map { it.displayName })
        // The empty child row the step starts with is not saved.
        assertEquals(emptyList(), world.family.children.first())
    }

    // 4.1 Fler vuxna

    @Test
    fun otherAdultsAreSavedAsMembersWithoutAnAccount() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)
        vm.next("Familjen Sara")
        vm.setMyName("Sara")
        vm.setAdultName(" Johan ")
        vm.addAdult()
        vm.setAdultName("   ")
        vm.addAdult()
        vm.setAdultName("johan")
        vm.addAdult()
        vm.setAdultName("Moster Lena")
        vm.addAdult()
        assertEquals(listOf("Johan", "Moster Lena"), vm.state.value.adults.map { it.name })
        assertEquals("", vm.state.value.adultName)

        vm.removeAdult(vm.state.value.adults.last().id)
        // A name typed but not added yet is added by going on.
        vm.setAdultName("Morfar Bengt")
        vm.next("Familjen Sara")
        assertEquals(listOf("Johan", "Morfar Bengt"), vm.state.value.adults.map { it.name })

        vm.typeChildren("Ella")
        vm.next("Familjen Sara")
        vm.next("Familjen Sara")

        val members = world.family.members.first().associateBy { it.displayName }
        assertEquals(setOf("Sara", "Johan", "Morfar Bengt"), members.keys)
        assertEquals(Role.Parent, members.getValue("Sara").role)
        for (name in listOf("Johan", "Morfar Bengt")) {
            val m = members.getValue(name)
            assertEquals(Role.Caregiver, m.role, "$name gets the least access until a parent invites them")
            assertNull(m.uid, "$name has no account")
            assertNull(m.joinedAt)
            assertNotEquals(MemberColor.Terracotta, m.color)
        }
        val colors = (world.family.people.first().map { it.color })
        assertEquals(colors.size, colors.toSet().size, "everyone has an own colour")
        assertEquals(MemberColor.Ocean, world.family.children.first().single().color)
    }

    /** "Klart när": an adult added in onboarding can be chosen as the one who drops off or picks up. */
    @Test
    fun otherAdultsCanBeChosenToDropOffAndPickUp() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)
        vm.next("Familjen Sara")
        vm.setMyName("Sara")
        vm.setAdultName("Morfar Bengt")
        vm.addAdult()
        vm.next("Familjen Sara")
        vm.typeChildren("Ella")
        vm.next("Familjen Sara")
        vm.next("Familjen Sara")

        // The event editor lists FamilyRepository.people without children as "Vem lämnar?" / "Vem hämtar?"
        // (EventEditorState.adults); see also EventEditorPeopleTest in feature-calendar.
        val adults = world.family.people.first().filterNot { it.isChild }
        assertEquals(listOf("Sara", "Morfar Bengt"), adults.map { it.name })
        assertEquals(listOf(Role.Parent, Role.Caregiver), adults.map { it.role })
    }

    // 4.2 Barn och ort utan friktion

    @Test
    fun theChildrenStepStartsWithOneEmptyRow() = runTest {
        val vm = viewModel(TestWorld())
        vm.toChildren()

        val rows = vm.state.value.children
        assertEquals(1, rows.size)
        assertEquals(ChildDraft(rows.single().id), rows.single())

        // Going back and forth does not add another row, and a removed row comes back when the step is opened again.
        vm.back()
        vm.next("Familjen Sara")
        assertEquals(rows, vm.state.value.children)
        vm.removeChild(rows.single().id)
        assertEquals(emptyList(), vm.state.value.children)
        vm.next("Familjen Sara")
        vm.back()
        assertEquals(1, vm.state.value.children.size)
    }

    @Test
    fun theBirthYearIsOptional() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)
        vm.toChildren()
        vm.typeChildren("Ella", "Simon", "Vera", "Alva")
        val (ella, simon, vera, alva) = vm.state.value.children.map { it.id }
        vm.updateChild(simon) { it.copy(birthYear = "2019") }
        vm.updateChild(vera) { it.copy(birthYear = "1890") }
        vm.updateChild(alva) { it.copy(birthYear = "2031") }
        vm.next("Familjen Sara")
        vm.next("Familjen Sara")

        val born = world.family.children.first().associate { it.id to it.birthDate }
        assertEquals(4, born.size, "every child is saved, with or without a year")
        assertNull(born.getValue(ella))
        assertEquals(LocalDate(2019, 7, 1), born.getValue(simon))
        assertNull(born.getValue(vera), "a year that cannot be right is left out")
        assertNull(born.getValue(alva), "a year in the future is left out")
    }

    @Test
    fun anEmptySearchFieldShowsNoPlaces() = runTest {
        val vm = viewModel(TestWorld())
        vm.toChildren()
        vm.next("Familjen Sara")
        assertEquals(OnboardingStep.Place, vm.state.value.step)

        assertEquals(emptyList(), vm.state.value.placeSuggestions)

        vm.setPlaceQuery("upp")
        assertEquals("Uppsala", vm.state.value.placeSuggestions.first().name)

        vm.setPlaceQuery("  ")
        assertEquals(emptyList(), vm.state.value.placeSuggestions)
    }

    @Test
    fun choosingAPlaceShowsItInTheSearchField() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)
        vm.toChildren()
        vm.next("Familjen Sara")
        vm.setPlaceQuery("upp")
        val uppsala = vm.state.value.placeSuggestions.first()

        vm.choosePlace(uppsala)
        assertEquals("Uppsala", vm.state.value.placeQuery)
        assertEquals(listOf(uppsala), vm.state.value.placeSuggestions)

        // Typing something else starts over: nothing stays chosen that the screen does not show.
        vm.setPlaceQuery("Upps")
        assertNull(vm.state.value.place)
        vm.setPlaceQuery("uppsala")
        assertNull(vm.state.value.place)

        vm.useLocation(57.70, 11.95)
        assertEquals("Göteborg", vm.state.value.place?.name)
        assertEquals("Göteborg", vm.state.value.placeQuery)
        assertEquals(57.70, vm.state.value.place?.latitude)

        vm.next("Familjen Sara")
        assertEquals("Göteborg", world.family.settings.first().weatherPlace?.name)
    }

    // 4.3 Klart

    @Test
    fun aParentIsToldAboutThePlusAndIsDoneAfterShowMyDay() = runTest {
        val world = TestWorld()
        val vm = viewModel(world)
        vm.runThrough("Ella")

        assertEquals(OnboardingStep.Done, vm.state.value.step)
        assertEquals(Role.Parent, vm.state.value.role)
        assertEquals(DoneMessage.AddWithPlus, vm.state.value.doneMessage)
        // The done screen stays until "Visa min dag": the shell switches as soon as onboarding is done.
        assertFalse(world.prefs.get(Prefs.OnboardingDone))

        var finished = false
        vm.showMyDay { finished = true }

        assertTrue(finished)
        assertTrue(world.prefs.get(Prefs.OnboardingDone))
    }

    @Test
    fun aCaregiverIsNotToldAboutThePlus() = runTest {
        // Joined through an invite, but the app closed before onboarding was marked done.
        val world = TestWorld(session = Session(familyId = "f", memberId = "m-bengt", uid = "u", role = Role.Caregiver))
        val vm = viewModel(world)

        assertEquals(OnboardingStep.Done, vm.state.value.step)
        assertEquals(DoneMessage.SeeHandoffs, vm.state.value.doneMessage)

        vm.showMyDay {}
        assertTrue(world.prefs.get(Prefs.OnboardingDone))
    }

    @Test
    fun aFamilyAlreadyOnThisDeviceOpensOnTheDoneScreen() = runTest {
        // The app was closed on the done screen: the welcome screen would create a second family.
        val world = TestWorld(session = Session(familyId = "f", memberId = "m-sara", role = Role.Parent))

        val vm = viewModel(world)

        assertEquals(OnboardingStep.Done, vm.state.value.step)
        assertEquals(DoneMessage.AddWithPlus, vm.state.value.doneMessage)
    }

    @Test
    fun aFirstStartOpensOnTheWelcomeScreen() = runTest {
        val vm = viewModel(TestWorld())

        assertEquals(OnboardingUiState(), vm.state.value)
    }
}
