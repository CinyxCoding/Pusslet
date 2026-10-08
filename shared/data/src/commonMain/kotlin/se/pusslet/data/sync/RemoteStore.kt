package se.pusslet.data.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A document as seen on the server. [data] includes meta fields; [updatedAt] is server time in ms. */
data class RemoteDoc(
    val docId: String,
    val data: JsonObject,
    val updatedAt: Long?,
    /** The document no longer exists on the server (hard delete, e.g. family deletion). */
    val removed: Boolean = false,
)

data class RemoteFilter(val field: String, val equalsTo: Boolean)

/** The server refused (security rules). Retrying will not help. */
class RemotePermissionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The backend as the sync engine sees it: schemaless documents under `families/{familyId}`.
 * Implementations: Firestore (androidMain), a stub for iOS, and [InMemoryRemoteStore] for tests and the demo.
 */
interface RemoteStore {
    /**
     * Streams batches of changed documents in [path] (a collection relative to the family, or "" for the family document).
     * The first emission contains everything changed since [sinceServerTime] (or all documents when null).
     */
    fun listen(familyId: String, path: String, sinceServerTime: Long?, filter: RemoteFilter?): Flow<List<RemoteDoc>>

    /**
     * Writes only [fields] (dotted paths; null value = delete field) and stamps updatedAt (server time) and updatedBy.
     * Suspends until the server has acknowledged the write.
     */
    suspend fun write(familyId: String, docPath: String, fields: Map<String, JsonElement?>, uid: String)
}
