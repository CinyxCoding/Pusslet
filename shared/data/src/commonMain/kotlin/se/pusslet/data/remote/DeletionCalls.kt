package se.pusslet.data.remote

import co.touchlab.kermit.Logger
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseException
import dev.gitlive.firebase.functions.FirebaseFunctions
import dev.gitlive.firebase.functions.FirebaseFunctionsException
import dev.gitlive.firebase.functions.HttpsCallableReference
import dev.gitlive.firebase.functions.code
import dev.gitlive.firebase.functions.details
import dev.gitlive.firebase.functions.functions
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes

@Serializable
internal data class DeleteFamilyRequest(val familyId: String)

@Serializable
internal data class DeleteAccountRequest(val familyId: String? = null)

/**
 * Calls the GDPR callables in functions/src/privacy.ts (`deleteFamily`, `deleteAccount`) through GitLive, as planned in
 * ADR-006. Shared by every platform with Firebase; [FirestoreFamilyRemote] delegates to it on Android.
 *
 * [emulatorHost] points the calls at the Local Emulator Suite (demo flavor, `BackendConfig.emulatorHost`); null calls
 * the real project. The Firebase app must be initialised before the first call.
 */
class DeletionCalls(
    private val emulatorHost: String? = null,
    private val functions: () -> FirebaseFunctions = { Firebase.functions(REGION) },
) {
    private val log = Logger.withTag("Deletion")
    private val instance: FirebaseFunctions by lazy {
        functions().also { f -> emulatorHost?.let { f.useEmulator(it, EMULATOR_PORT) } }
    }

    suspend fun deleteFamily(familyId: String) = call("deleteFamily") {
        it.invoke(DeleteFamilyRequest.serializer(), DeleteFamilyRequest(familyId))
    }

    suspend fun deleteAccount(familyId: String?) = call("deleteAccount") {
        it.invoke(DeleteAccountRequest.serializer(), DeleteAccountRequest(familyId))
    }

    private suspend fun call(name: String, block: suspend (HttpsCallableReference) -> Unit) {
        try {
            block(instance.httpsCallable(name, TIMEOUT))
        } catch (e: FirebaseFunctionsException) {
            val refusal = refusalFor(e.code.name, e.message, e.details)
            log.w { "$name refused: $refusal" }
            throw DeletionRefusedException(refusal, e)
        } catch (e: FirebaseException) {
            log.w { "$name failed: ${e::class.simpleName}" }
            throw DeletionRefusedException(DeletionRefusal.Network, e)
        }
    }

    companion object {
        /** Same region as every function (functions/src/setup.ts). */
        const val REGION = "europe-north1"

        /** firebase.json › emulators.functions.port */
        const val EMULATOR_PORT = 5001

        /** Reason codes the functions send as the error message and in `details.reason` (functions/src/privacy.ts › Reasons). */
        const val REASON_LAST_PARENT = "last-parent"

        /** Not sent today: the Admin SDK deletes without a fresh sign-in. Reserved for a stricter backend. */
        const val REASON_RECENT_LOGIN = "requires-recent-login"

        /** Deleting a big family can take a while; the function itself may run for 5 minutes. */
        private val TIMEOUT = 2.minutes

        /** Maps a callable error ([code] is the gRPC status name) to what the Integritet screen says. */
        internal fun refusalFor(code: String, message: String?, details: Any?): DeletionRefusal {
            val reason = (details as? Map<*, *>)?.get("reason") as? String ?: message
            return when (code) {
                "UNAUTHENTICATED" -> DeletionRefusal.NotSignedIn
                "PERMISSION_DENIED" -> DeletionRefusal.NotAllowed
                "FAILED_PRECONDITION" -> when (reason) {
                    REASON_LAST_PARENT -> DeletionRefusal.LastParent
                    REASON_RECENT_LOGIN -> DeletionRefusal.RecentLoginRequired
                    else -> DeletionRefusal.Unknown
                }
                // The Android SDK reports a lost connection as INTERNAL, like an unexpected server error.
                "UNAVAILABLE", "DEADLINE_EXCEEDED", "INTERNAL" -> DeletionRefusal.Network
                else -> DeletionRefusal.Unknown
            }
        }
    }
}
