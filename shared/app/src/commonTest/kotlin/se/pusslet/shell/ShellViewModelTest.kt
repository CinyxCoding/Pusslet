package se.pusslet.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import se.pusslet.core.model.Role
import se.pusslet.data.db.PreferenceDao
import se.pusslet.data.db.PreferenceEntity
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which shell the app opens (UX plan Steg 3, ADR-029): the helper view for caregivers, unless they chose the full app. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellViewModelTest {
    private class MemoryPreferences : PreferenceDao {
        private val values = MutableStateFlow<Map<String, String>>(emptyMap())

        override fun observe(key: String): Flow<String?> = values.map { it[key] }

        override suspend fun get(key: String): String? = values.value[key]

        override suspend fun put(entity: PreferenceEntity) {
            values.value = values.value + (entity.key to entity.value)
        }

        override suspend fun remove(key: String) {
            values.value = values.value - key
        }

        override suspend fun clear() {
            values.value = emptyMap()
        }
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun shell(role: Role): Pair<ShellViewModel, PreferencesRepository> {
        val prefs = PreferencesRepository(MemoryPreferences())
        prefs.set(Prefs.OnboardingDone, true)
        val sessions = FixedSessionProvider(Session(familyId = "f", memberId = "m-bengt", role = role))
        return ShellViewModel(flowOf(true), prefs, sessions) to prefs
    }

    @Test
    fun aCaregiverGetsTheHelperView() = runTest {
        val (vm, _) = shell(Role.Caregiver)

        assertTrue(vm.state.value.onboarded)
        assertEquals(ShellMode.Helper, vm.state.value.mode)
        assertFalse(vm.state.value.caregiverInFullApp)
    }

    @Test
    fun aParentNeverGetsTheHelperView() = runTest {
        val (vm, prefs) = shell(Role.Parent)
        assertEquals(ShellMode.Main, vm.state.value.mode)

        // A preference left on the phone changes nothing for a parent.
        prefs.set(Prefs.HelperFullView, false)
        assertEquals(ShellMode.Main, vm.state.value.mode)
        assertFalse(vm.state.value.caregiverInFullApp)
    }

    @Test
    fun visaHelaAppenAndEnkelVySwitchAtOnce() = runTest {
        val (vm, prefs) = shell(Role.Caregiver)

        vm.setHelperFullView(true)
        assertEquals(ShellMode.Main, vm.state.value.mode)
        assertTrue(vm.state.value.caregiverInFullApp, "Mer then offers Enkel vy")
        assertTrue(prefs.get(Prefs.HelperFullView), "Kept after a restart")

        vm.setHelperFullView(false)
        assertEquals(ShellMode.Helper, vm.state.value.mode)
    }
}
