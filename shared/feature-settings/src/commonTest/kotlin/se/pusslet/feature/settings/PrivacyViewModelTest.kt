package se.pusslet.feature.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.AppCoordinator
import se.pusslet.data.BackendConfig
import se.pusslet.data.BackendMode
import se.pusslet.data.db.PreferenceDao
import se.pusslet.data.db.PreferenceEntity
import se.pusslet.data.db.PurchaseLogDao
import se.pusslet.data.db.PurchaseLogEntity
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.AuthUser
import se.pusslet.data.remote.DeletionRefusal
import se.pusslet.data.remote.DeletionRefusedException
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.seed.DemoSeed
import se.pusslet.data.session.SessionRepository
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemoryRemoteStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import se.pusslet.data.sync.SyncEngine
import se.pusslet.data.usecase.AccountDeletionPlan
import se.pusslet.data.usecase.DataPrivacy
import se.pusslet.data.usecase.DeletionOutcome
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

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

private object NoPurchases : PurchaseLogDao {
    override suspend fun insert(entity: PurchaseLogEntity) = Unit

    override suspend fun since(familyId: String, since: Long) = emptyList<PurchaseLogEntity>()

    override suspend fun deleteFamily(familyId: String) = Unit
}

private class Account(uid: String?) : AuthRepository by LocalOnlyAuthRepository() {
    private val user = MutableStateFlow(uid?.let { AuthUser(it, null, null, isAnonymous = false) })
    override val available = true
    override val currentUser: StateFlow<AuthUser?> = user

    override suspend fun signOut() {
        user.value = null
    }
}

private class Server(var refusal: DeletionRefusal? = null) : FamilyRemote by LocalOnlyFamilyRemote {
    override val available = true
    var calls = 0

    override suspend fun deleteFamily(familyId: String) {
        calls++
        refusal?.let { throw DeletionRefusedException(it) }
    }

    override suspend fun deleteAccount(familyId: String?) {
        calls++
        refusal?.let { throw DeletionRefusedException(it) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-10-20T08:00:00Z")
    }
    private val zone = TimeZone.of("Europe/Stockholm")

    /** One phone with Familjen Lind; Sara (this phone) is a parent unless [role] says otherwise. */
    private inner class Phone(scope: CoroutineScope, val remote: FamilyRemote, val auth: AuthRepository) {
        val prefDao = MemoryPreferences()
        val local = InMemorySyncLocalStore()
        val sessions = SessionRepository(prefDao)
        val prefs = PreferencesRepository(prefDao)
        val docs = DocumentStore(local, sessions, clock)
        val family = FamilyRepository(docs, sessions, remote, auth, SequentialIdGenerator(), clock)
        val coordinator = AppCoordinator(
            scope = scope,
            config = BackendConfig(BackendMode.LocalOnly, seedDemoData = false, versionName = "test"),
            sessions = sessions,
            auth = auth,
            familyRemote = remote,
            docs = docs,
            engine = SyncEngine(local, InMemoryRemoteStore(), docs),
            seed = DemoSeed(family, docs, clock),
            prefs = prefs,
            zone = zone,
            clock = clock,
        )
        val privacy = DataPrivacy(local, sessions, coordinator, family, remote, auth, NoPurchases, prefDao, zone, clock)

        suspend fun withFamily(role: Role = Role.Parent, others: List<Member> = emptyList()): Phone {
            family.createFamily("Familjen Lind", "Sara")
            others.forEach { family.saveMember(it) }
            sessions.update { it.copy(role = role) }
            return this
        }
    }

    private fun TestScope.viewModel(phone: Phone): PrivacyViewModel {
        val vm = PrivacyViewModel(phone.privacy)
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { vm.state.collect {} }
        return vm
    }

    private val bengt = Member("m-bengt", "Bengt", Role.Caregiver, uid = "uid-bengt")
    private val johan = Member("m-johan", "Johan", Role.Parent, uid = "uid-johan")

    @Test
    fun withoutBackendThisPhoneIsAllThereIs() = runTest {
        val vm = viewModel(Phone(backgroundScope, LocalOnlyFamilyRemote, LocalOnlyAuthRepository()).withFamily())
        val options = vm.state.value.options
        assertFalse(options.server)
        assertFalse(options.canDeleteFamily || options.canDeleteAccount || options.needsSignIn)
    }

    @Test
    fun signedOutPhonesAreAskedToSignInFirst() = runTest {
        val options = viewModel(Phone(backgroundScope, Server(), Account(null)).withFamily()).state.value.options
        assertTrue(options.needsSignIn)
        assertFalse(options.canDeleteFamily || options.canDeleteAccount)
    }

    @Test
    fun parentsCanDeleteForEveryoneCaregiversOnlyTheirAccount() = runTest {
        val parent = viewModel(Phone(backgroundScope, Server(), Account("uid-sara")).withFamily(others = listOf(johan))).state.value
        assertTrue(parent.options.canDeleteFamily && parent.options.canDeleteAccount)
        assertEquals("Familjen Lind", parent.options.familyName)
        assertEquals(AccountDeletionPlan.LeaveFamily, parent.options.accountPlan)

        val caregiver = viewModel(Phone(backgroundScope, Server(), Account("uid-sara")).withFamily(Role.Caregiver)).state.value
        assertFalse(caregiver.options.canDeleteFamily)
        assertTrue(caregiver.options.canDeleteAccount)
    }

    @Test
    fun theOnlyParentIsToldToDeleteOrHandOverFirst() = runTest {
        val state = viewModel(Phone(backgroundScope, Server(), Account("uid-sara")).withFamily(others = listOf(bengt))).state.value
        assertEquals(AccountDeletionPlan.LastParent, state.options.accountPlan)
    }

    @Test
    fun exportShowsAReceiptOrThatNothingWasSaved() = runTest {
        val vm = viewModel(Phone(backgroundScope, LocalOnlyFamilyRemote, LocalOnlyAuthRepository()).withFamily())
        var saved: Pair<String, String>? = null

        vm.export { name, content, done ->
            saved = name to content
            done(true)
        }

        assertEquals("pusslet-export-2026-10-20.json", saved?.first)
        assertTrue(saved?.second.orEmpty().contains("Familjen Lind"))
        assertEquals(ExportReceipt.Saved("pusslet-export-2026-10-20.json"), vm.state.value.receipt)
        assertFalse(vm.state.value.busy)

        vm.export { _, _, done -> done(false) }
        assertEquals(ExportReceipt.NotSaved, vm.state.value.receipt)
    }

    @Test
    fun aSuccessfulDeletionLeavesTheScreen() = runTest {
        val phone = Phone(backgroundScope, Server(), Account("uid-sara")).withFamily()
        val vm = viewModel(phone)
        var left = false

        vm.deleteFamily { left = true }

        assertTrue(left)
        assertFalse(phone.sessions.session.value.hasFamily)
        assertNull(phone.auth.currentUser.value)
    }

    @Test
    fun aRefusalIsShownUnderItsButton() = runTest {
        val phone = Phone(backgroundScope, Server(DeletionRefusal.LastParent), Account("uid-sara")).withFamily(others = listOf(bengt))
        val vm = viewModel(phone)
        var left = false

        vm.deleteAccount { left = true }

        assertFalse(left)
        assertEquals(PrivacyProblem(PrivacyWork.DeleteAccount, DeletionOutcome.LastParent), vm.state.value.problem)
        assertTrue(phone.sessions.session.value.hasFamily)
        assertFalse(vm.state.value.busy)

        (phone.remote as Server).refusal = DeletionRefusal.Network
        vm.deleteFamily {}
        assertEquals(PrivacyProblem(PrivacyWork.DeleteFamily, DeletionOutcome.Offline), vm.state.value.problem)
    }

    @Test
    fun forgettingWorksWithoutBackend() = runTest {
        val phone = Phone(backgroundScope, LocalOnlyFamilyRemote, LocalOnlyAuthRepository()).withFamily()
        var left = false

        viewModel(phone).forget { left = true }

        assertTrue(left)
        assertFalse(phone.sessions.session.value.hasFamily)
    }

    @Test
    fun theFamilyNameMustBeTypedToDeleteForEveryone() {
        assertTrue(confirmsFamilyName("familjen  lind ", "Familjen Lind"))
        assertFalse(confirmsFamilyName("Familjen", "Familjen Lind"))
        assertFalse(confirmsFamilyName("", ""))
    }
}
