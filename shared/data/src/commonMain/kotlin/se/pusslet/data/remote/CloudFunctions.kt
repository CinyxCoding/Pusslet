package se.pusslet.data.remote

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.functions
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** Server-side operations that cannot be done by syncing documents (they cross families or need admin rights). */
interface CloudFunctions {
    val available: Boolean

    /** Deletes every calendar feed link of the family. Returns how many were revoked. */
    suspend fun revokeCalendarFeeds(familyId: String): Int

    /** Deletes the family and everything in it, for everyone (parents only). */
    suspend fun deleteFamily(familyId: String)

    /** Deletes the caller's account and membership; the family stays for the others. */
    suspend fun deleteAccount()
}

object LocalOnlyCloudFunctions : CloudFunctions {
    override val available = false

    private fun unavailable(): Nothing = throw BackendUnavailableException("No backend configured")

    override suspend fun revokeCalendarFeeds(familyId: String): Int = unavailable()

    override suspend fun deleteFamily(familyId: String) = unavailable()

    override suspend fun deleteAccount() = unavailable()
}

/** Callable functions in the EU region through the GitLive SDK; the demo flavor talks to the emulator. */
class GitLiveCloudFunctions(region: String = REGION, emulatorHost: String? = null) : CloudFunctions {
    override val available = true
    private val functions = Firebase.functions(region).also { f -> emulatorHost?.let { f.useEmulator(it, EMULATOR_PORT) } }

    @Serializable
    private data class FamilyRequest(val familyId: String)

    @Serializable
    private data class RevokeResult(val revoked: Int = 0)

    override suspend fun revokeCalendarFeeds(familyId: String): Int = call {
        functions.httpsCallable("revokeCalendarFeeds").invoke(FamilyRequest(familyId)).data<RevokeResult>().revoked
    }

    override suspend fun deleteFamily(familyId: String) = call {
        functions.httpsCallable("deleteFamily").invoke(FamilyRequest(familyId))
        Unit
    }

    override suspend fun deleteAccount() = call {
        functions.httpsCallable("deleteAccount").invoke(emptyMap<String, String>())
        Unit
    }

    /** Network and permission problems become one exception type the UI can explain. */
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BackendUnavailableException("Cloud function failed: ${e::class.simpleName}")
    }

    companion object {
        const val REGION = "europe-north1"
        const val EMULATOR_PORT = 5001
    }
}
