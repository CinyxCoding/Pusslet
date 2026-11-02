package se.pusslet.feature.family.restore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import se.pusslet.core.model.Role
import se.pusslet.data.remote.AccountFamily
import se.pusslet.data.remote.AuthError
import se.pusslet.data.remote.AuthException
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.AuthUser
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.Session
import se.pusslet.data.usecase.AppGuide
import se.pusslet.feature.family.TestWorld
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Jag har redan ett konto" on a new phone (ADR-063): sign in, find the account's family and bring it back. */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val berg = AccountFamily("f-berg", "Familjen Berg", "m-anna", Role.Parent)
    private val anna = AuthUser("uid-anna", "anna@example.se", null, isAnonymous = false)

    /** The families the account belongs to on the server, or null while the server cannot be reached. */
    private class Server(var families: List<AccountFamily>?) : FamilyRemote by LocalOnlyFamilyRemote {
        override val available = true

        override suspend fun accountFamilies(uid: String): List<AccountFamily> = families ?: throw IllegalStateException("No network")
    }

    /** Anna's account: "rätt" is her password. */
    private class Account(signedIn: AuthUser? = null) : AuthRepository by LocalOnlyAuthRepository() {
        private val user = MutableStateFlow(signedIn)
        override val available = true
        override val currentUser: StateFlow<AuthUser?> = user

        override suspend fun signInWithEmail(email: String, password: String): AuthUser {
            if (password != "rätt") throw AuthException(AuthError.InvalidCredentials)
            return AuthUser("uid-anna", email, null, isAnonymous = false).also { user.value = it }
        }

        override suspend fun signOut() {
            user.value = null
        }
    }

    /** A new phone: no family yet, the server and the account given. */
    private class Phone(val server: Server, val account: Account = Account()) {
        val world = TestWorld()
        val family = FamilyRepository(world.docs, world.sessions, server, account, world.ids, world.clock)
        val vm = RestoreViewModel(account, server, family, world.prefs)
    }

    @Test
    fun signingInFindsTheFamilyAndBringsItBack() = runTest {
        val phone = Phone(Server(listOf(berg)))
        phone.vm.start()
        assertEquals(RestoreStep.SignIn, phone.vm.state.value.step)

        phone.vm.signIn("anna@example.se", "rätt")

        assertEquals(RestoreStep.Found, phone.vm.state.value.step)
        assertEquals(listOf(berg), phone.vm.state.value.families)
        assertEquals("anna@example.se", phone.vm.state.value.account)

        phone.vm.choose(berg)

        val session = Session(familyId = "f-berg", memberId = "m-anna", uid = "uid-anna", role = Role.Parent)
        assertEquals(session, phone.world.sessions.session.value)
        assertTrue(phone.world.prefs.get(Prefs.OnboardingDone), "The shell opens the app")
        // Whoever comes back has used the app before: the guide does not open by itself (ADR-062).
        assertFalse(AppGuide(phone.world.prefs).due.first())
        assertFalse(phone.vm.state.value.busy)
    }

    @Test
    fun aWrongPasswordStaysOnTheForm() = runTest {
        val phone = Phone(Server(listOf(berg)))
        phone.vm.start()

        phone.vm.signIn("anna@example.se", "fel")

        assertEquals(RestoreStep.SignIn, phone.vm.state.value.step)
        assertEquals(AuthError.InvalidCredentials, phone.vm.state.value.error)
        assertFalse(phone.vm.state.value.busy)
    }

    /** After the family was removed from this phone the account may still be signed in: no form then. */
    @Test
    fun anAccountStillSignedInIsLookedUpRightAway() = runTest {
        val phone = Phone(Server(listOf(berg)), Account(anna))

        phone.vm.start()

        assertEquals(RestoreStep.Found, phone.vm.state.value.step)
    }

    /** The demo build signs in anonymously with the emulator; such an account has no family to bring back. */
    @Test
    fun anAnonymousAccountSignsInFirst() = runTest {
        val phone = Phone(Server(listOf(berg)), Account(AuthUser("uid-anon", null, null, isAnonymous = true)))

        phone.vm.start()

        assertEquals(RestoreStep.SignIn, phone.vm.state.value.step)
    }

    @Test
    fun anAccountWithoutAFamilyCanSwitchToAnother() = runTest {
        val phone = Phone(Server(emptyList()))
        phone.vm.start()
        phone.vm.signIn("anna@example.se", "rätt")
        assertEquals(RestoreStep.NoneFound, phone.vm.state.value.step)

        phone.vm.otherAccount()

        assertEquals(RestoreStep.SignIn, phone.vm.state.value.step)
        assertNull(phone.account.currentUser.value)
        assertFalse(phone.world.sessions.session.value.hasFamily)
    }

    @Test
    fun noAnswerFromTheServerCanBeTriedAgain() = runTest {
        val phone = Phone(Server(null))
        phone.vm.start()
        phone.vm.signIn("anna@example.se", "rätt")
        assertEquals(RestoreStep.Offline, phone.vm.state.value.step)

        phone.server.families = listOf(berg)
        phone.vm.retry()

        assertEquals(RestoreStep.Found, phone.vm.state.value.step)
        assertEquals(listOf(berg), phone.vm.state.value.families)
    }
}
