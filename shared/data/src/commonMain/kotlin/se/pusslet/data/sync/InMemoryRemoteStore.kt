package se.pusslet.data.sync

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * A fake Firestore: one shared server for several sync engines ("devices") in tests.
 * Server time is a monotonic counter; [online] = false makes writes suspend and listeners stall, like a real outage.
 */
class InMemoryRemoteStore(private val startTime: Long = 1_000_000L) : RemoteStore {
    private data class Doc(val path: String, val data: JsonObject, val updatedAt: Long)

    private val mutex = Mutex()
    private val docs = HashMap<String, Doc>()
    private val changes = MutableSharedFlow<Doc>(extraBufferCapacity = 1024)
    private var clock = startTime

    val online = MutableStateFlow(true)

    /** Optional rule check: return false to reject a write with [RemotePermissionException]. */
    var writeRule: (familyId: String, docPath: String, uid: String) -> Boolean = { _, _, _ -> true }

    fun snapshot(familyId: String, docPath: String): JsonObject? = docs[fullPath(familyId, docPath)]?.data

    override fun listen(familyId: String, path: String, sinceServerTime: Long?, filter: RemoteFilter?): Flow<List<RemoteDoc>> = channelFlow {
        online.first { it }
        val prefix = fullPath(familyId, path)
        // Subscribe before taking the snapshot so nothing written in between is missed.
        launch(start = CoroutineStart.UNDISPATCHED) {
            changes.filter { matches(it, prefix, path.isEmpty()) && passes(it, filter) }.collect { doc ->
                online.first { it }
                send(listOf(toRemote(doc)))
            }
        }
        val initial = mutex.withLock {
            docs.values.filter { matches(it, prefix, path.isEmpty()) && (sinceServerTime == null || it.updatedAt > sinceServerTime) && passes(it, filter) }
        }
        send(initial.map { toRemote(it) })
    }

    override suspend fun write(familyId: String, docPath: String, fields: Map<String, JsonElement?>, uid: String) {
        online.first { it }
        if (!writeRule(familyId, docPath, uid)) throw RemotePermissionException("denied: $docPath")
        val doc = mutex.withLock {
            val key = fullPath(familyId, docPath)
            val time = ++clock
            val stamped = fields.mapValues { (_, v) -> if (v is JsonPrimitive && v.isString && v.content == MetaFields.SERVER_TIMESTAMP) JsonPrimitive(time) else v } +
                mapOf(MetaFields.UPDATED_AT to JsonPrimitive(time), MetaFields.UPDATED_BY to JsonPrimitive(uid))
            val base = docs[key]?.data ?: JsonObject(emptyMap())
            val next = Doc(key, JsonPaths.overlay(base, stamped), time)
            docs[key] = next
            next
        }
        changes.emit(doc)
    }

    /** Simulates an admin/function hard delete (e.g. family deletion). */
    suspend fun hardDelete(familyId: String, docPath: String) {
        val key = fullPath(familyId, docPath)
        val removed = mutex.withLock { docs.remove(key) } ?: return
        changes.emit(removed.copy(data = JsonObject(mapOf(REMOVED to JsonPrimitive(true)))))
    }

    private fun toRemote(doc: Doc): RemoteDoc {
        val removed = (doc.data[REMOVED] as? JsonPrimitive)?.booleanOrNull == true
        return RemoteDoc(doc.path.substringAfterLast('/'), doc.data, doc.updatedAt, removed)
    }

    private fun passes(doc: Doc, filter: RemoteFilter?): Boolean =
        filter == null || (doc.data[filter.field] as? JsonPrimitive)?.booleanOrNull == filter.equalsTo || doc.data.containsKey(REMOVED)

    private fun matches(doc: Doc, prefix: String, familyRoot: Boolean): Boolean =
        if (familyRoot) doc.path == prefix else doc.path.startsWith("$prefix/") && doc.path.removePrefix("$prefix/").none { it == '/' }

    private fun fullPath(familyId: String, path: String) = if (path.isEmpty()) "families/$familyId" else "families/$familyId/$path"

    private companion object {
        const val REMOVED = "__removed__"
    }
}
