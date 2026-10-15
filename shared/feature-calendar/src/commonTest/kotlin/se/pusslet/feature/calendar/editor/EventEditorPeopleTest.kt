package se.pusslet.feature.calendar.editor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import se.pusslet.core.model.Child
import se.pusslet.core.model.Member
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.feature.calendar.TestWorld
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Who can be chosen for "Vem lämnar?" and "Vem hämtar?" in the event editor. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventEditorPeopleTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun adultsWithoutAnAccountCanDropOffAndPickUp() = runTest {
        val world = TestWorld()
        world.family.saveMember(Member("m-sara", "Sara", Role.Parent, MemberColor.Sage, uid = "u"))
        // Added by name in the onboarding (UX plan 4.1): no account, the least role.
        world.family.saveMember(Member("m-bengt", "Morfar Bengt", Role.Caregiver, MemberColor.Teal))
        world.family.saveChild(Child("c-ella", "Ella"))
        val vm = EventEditorViewModel(world.calendar, world.family, world.sessions, world.clock, world.zone)

        vm.load(eventId = null, date = null, occurrence = null)

        assertEquals(listOf("Sara", "Morfar Bengt"), vm.state.value.adults.map { it.name })
    }
}
