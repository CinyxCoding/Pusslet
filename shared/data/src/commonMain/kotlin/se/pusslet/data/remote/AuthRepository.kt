package se.pusslet.data.remote

import co.touchlab.kermit.Logger
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseException
import dev.gitlive.firebase.auth.FirebaseAuth
import dev.gitlive.firebase.auth.FirebaseAuthException
import dev.gitlive.firebase.auth.FirebaseUser
import dev.gitlive.firebase.auth.GoogleAuthProvider
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class AuthUser(val uid: String, val email: String?, val displayName: String?, val isAnonymous: Boolean)

enum class AuthError { InvalidCredentials, EmailInUse, WeakPassword, Network, Unavailable, Unknown }

class AuthException(val error: AuthError, cause: Throwable? = null) : Exception(error.name, cause)

interface AuthRepository {
    val available: Boolean
    val currentUser: StateFlow<AuthUser?>

    suspend fun signInAnonymously(): AuthUser

    suspend fun signInWithEmail(email: String, password: String): AuthUser

    suspend fun createAccount(email: String, password: String): AuthUser

    suspend fun signInWithGoogle(idToken: String): AuthUser

    suspend fun signOut()
}

/** Firebase Auth through the GitLive multiplatform SDK (shared by Android and iOS). */
class GitLiveAuthRepository(scope: CoroutineScope, private val auth: FirebaseAuth = Firebase.auth) : AuthRepository {
    private val log = Logger.withTag("Auth")
    override val available = true

    override val currentUser: StateFlow<AuthUser?> = auth.authStateChanged
        .map { it?.toAuthUser() }
        .stateIn(scope, SharingStarted.Eagerly, auth.currentUser?.toAuthUser())

    override suspend fun signInAnonymously(): AuthUser = guard { auth.signInAnonymously().user }

    override suspend fun signInWithEmail(email: String, password: String): AuthUser =
        guard { auth.signInWithEmailAndPassword(email.trim(), password).user }

    override suspend fun createAccount(email: String, password: String): AuthUser = guard {
        val current = auth.currentUser
        // Keep data created in the demo/anonymous session by upgrading the anonymous account.
        if (current != null && current.isAnonymous) {
            current.linkWithCredential(dev.gitlive.firebase.auth.EmailAuthProvider.credential(email.trim(), password)).user
        } else {
            auth.createUserWithEmailAndPassword(email.trim(), password).user
        }
    }

    override suspend fun signInWithGoogle(idToken: String): AuthUser = guard {
        auth.signInWithCredential(GoogleAuthProvider.credential(idToken, null)).user
    }

    override suspend fun signOut() = auth.signOut()

    private suspend fun guard(block: suspend () -> FirebaseUser?): AuthUser = try {
        block()?.toAuthUser() ?: throw AuthException(AuthError.Unknown)
    } catch (e: FirebaseAuthException) {
        val message = e.message.orEmpty()
        log.w { "Auth failed: ${e::class.simpleName}" }
        throw AuthException(
            when {
                "EMAIL_EXISTS" in message || "already in use" in message -> AuthError.EmailInUse
                "WEAK_PASSWORD" in message || "at least 6" in message -> AuthError.WeakPassword
                else -> AuthError.InvalidCredentials
            },
            e,
        )
    } catch (e: FirebaseException) {
        log.w { "Auth failed: ${e::class.simpleName}" }
        throw AuthException(AuthError.Network, e)
    }

    private fun FirebaseUser.toAuthUser() = AuthUser(uid, email, displayName, isAnonymous)
}

/** No backend configured: the app runs fully local and sign-in is unavailable. */
class LocalOnlyAuthRepository : AuthRepository {
    override val available = false
    private val state = MutableStateFlow<AuthUser?>(null)
    override val currentUser: StateFlow<AuthUser?> = state.asStateFlow()

    override suspend fun signInAnonymously(): AuthUser = throw AuthException(AuthError.Unavailable)

    override suspend fun signInWithEmail(email: String, password: String): AuthUser = throw AuthException(AuthError.Unavailable)

    override suspend fun createAccount(email: String, password: String): AuthUser = throw AuthException(AuthError.Unavailable)

    override suspend fun signInWithGoogle(idToken: String): AuthUser = throw AuthException(AuthError.Unavailable)

    override suspend fun signOut() = Unit
}
