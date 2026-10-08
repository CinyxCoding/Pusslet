package se.pusslet.data.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonElement
import se.pusslet.data.remote.BackendUnavailableException

/** Used in local-only mode. The sync engine is never started then; this only guards against misuse. */
object OfflineRemoteStore : RemoteStore {
    override fun listen(familyId: String, path: String, sinceServerTime: Long?, filter: RemoteFilter?): Flow<List<RemoteDoc>> = emptyFlow()

    override suspend fun write(familyId: String, docPath: String, fields: Map<String, JsonElement?>, uid: String) {
        throw BackendUnavailableException("Local-only mode")
    }
}
