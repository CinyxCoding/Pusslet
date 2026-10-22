package se.pusslet.data.sync

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.pusslet.data.db.DocumentEntity
import se.pusslet.data.db.OutboxEntity
import se.pusslet.data.db.PussletDatabase
import se.pusslet.data.db.SyncStateEntity

/** Local persistence the sync engine and document store need. Room in the app, in-memory in tests and previews. */
interface SyncLocalStore {
    fun observeCollection(familyId: String, collection: String): Flow<List<DocumentEntity>>

    fun observeDoc(familyId: String, collection: String, docId: String): Flow<DocumentEntity?>

    suspend fun get(familyId: String, collection: String, docId: String): DocumentEntity?

    suspend fun list(familyId: String, collection: String): List<DocumentEntity>

    suspend fun listFamily(familyId: String): List<DocumentEntity>

    suspend fun upsert(entity: DocumentEntity)

    suspend fun hardDelete(familyId: String, collection: String, docId: String)

    /** Removes deleted documents whose deletion is older than [before] (epoch ms) and that have nothing to send. */
    suspend fun purgeDeleted(before: Long): Int

    suspend fun clearPending(familyId: String, collection: String, docId: String)

    suspend fun appendOutbox(entity: OutboxEntity): Long

    suspend fun nextOutbox(familyId: String, limit: Int): List<OutboxEntity>

    suspend fun outboxFor(familyId: String, collection: String, docId: String): List<OutboxEntity>

    suspend fun replaceOutboxFields(id: Long, fieldsJson: String)

    suspend fun deleteOutbox(id: Long)

    suspend fun markOutboxFailed(id: Long, error: String)

    fun observeOutboxCount(familyId: String): Flow<Int>

    suspend fun watermark(familyId: String, path: String): Long?

    suspend fun setWatermark(familyId: String, path: String, serverTime: Long)

    suspend fun deleteFamily(familyId: String)

    suspend fun <T> transaction(block: suspend () -> T): T
}

class RoomSyncLocalStore(private val db: PussletDatabase) : SyncLocalStore {
    private val docs = db.documentDao()
    private val outbox = db.outboxDao()
    private val state = db.syncStateDao()
    private val purchases = db.purchaseLogDao()

    override fun observeCollection(familyId: String, collection: String) = docs.observeCollection(familyId, collection)

    override fun observeDoc(familyId: String, collection: String, docId: String) = docs.observe(familyId, collection, docId)

    override suspend fun get(familyId: String, collection: String, docId: String) = docs.get(familyId, collection, docId)

    override suspend fun list(familyId: String, collection: String) = docs.list(familyId, collection)

    override suspend fun listFamily(familyId: String) = docs.listFamily(familyId)

    override suspend fun upsert(entity: DocumentEntity) = docs.upsert(entity)

    override suspend fun hardDelete(familyId: String, collection: String, docId: String) = docs.hardDelete(familyId, collection, docId)

    override suspend fun purgeDeleted(before: Long) = docs.purgeDeleted(before)

    override suspend fun clearPending(familyId: String, collection: String, docId: String) = docs.clearPending(familyId, collection, docId)

    override suspend fun appendOutbox(entity: OutboxEntity) = outbox.insert(entity)

    override suspend fun nextOutbox(familyId: String, limit: Int) = outbox.next(familyId, limit)

    override suspend fun outboxFor(familyId: String, collection: String, docId: String) = outbox.forDoc(familyId, collection, docId)

    override suspend fun replaceOutboxFields(id: Long, fieldsJson: String) = outbox.replaceFields(id, fieldsJson)

    override suspend fun deleteOutbox(id: Long) = outbox.delete(id)

    override suspend fun markOutboxFailed(id: Long, error: String) = outbox.markFailed(id, error)

    override fun observeOutboxCount(familyId: String) = outbox.observeCount(familyId)

    override suspend fun watermark(familyId: String, path: String) = state.watermark(familyId, path)

    override suspend fun setWatermark(familyId: String, path: String, serverTime: Long) =
        state.put(SyncStateEntity(familyId, path, serverTime))

    /** Everything this phone stores for the family, including the purchase history that is never synced (ADR-019). */
    override suspend fun deleteFamily(familyId: String) {
        transaction {
            docs.deleteFamily(familyId)
            outbox.deleteFamily(familyId)
            state.deleteFamily(familyId)
            purchases.deleteFamily(familyId)
        }
    }

    override suspend fun <T> transaction(block: suspend () -> T): T = db.useWriterConnection { it.immediateTransaction { block() } }
}

/** Thread-safe in-memory store: unit tests, screenshot tests and previews. */
class InMemorySyncLocalStore : SyncLocalStore {
    private data class Key(val familyId: String, val collection: String, val docId: String)

    private val docs = MutableStateFlow<Map<Key, DocumentEntity>>(emptyMap())
    private val outbox = MutableStateFlow<List<OutboxEntity>>(emptyList())
    private val watermarks = HashMap<Pair<String, String>, Long>()
    private var nextOutboxId = 1L
    private val mutex = Mutex()

    override fun observeCollection(familyId: String, collection: String): Flow<List<DocumentEntity>> = docs.map { all ->
        all.values.filter { it.familyId == familyId && it.collection == collection && it.deletedAt == null }
    }

    override fun observeDoc(familyId: String, collection: String, docId: String): Flow<DocumentEntity?> =
        docs.map { it[Key(familyId, collection, docId)] }

    override suspend fun get(familyId: String, collection: String, docId: String) = docs.value[Key(familyId, collection, docId)]

    override suspend fun list(familyId: String, collection: String) =
        docs.value.values.filter { it.familyId == familyId && it.collection == collection && it.deletedAt == null }

    override suspend fun listFamily(familyId: String) = docs.value.values.filter { it.familyId == familyId }

    override suspend fun upsert(entity: DocumentEntity) {
        docs.value = docs.value + (Key(entity.familyId, entity.collection, entity.docId) to entity)
    }

    override suspend fun hardDelete(familyId: String, collection: String, docId: String) {
        docs.value = docs.value - Key(familyId, collection, docId)
    }

    override suspend fun purgeDeleted(before: Long): Int {
        val gone = docs.value.filterValues { it.deletedAt != null && it.deletedAt < before && !it.pending }.keys
        docs.value = docs.value - gone
        return gone.size
    }

    override suspend fun clearPending(familyId: String, collection: String, docId: String) {
        val k = Key(familyId, collection, docId)
        docs.value[k]?.let { docs.value = docs.value + (k to it.copy(pending = false)) }
    }

    override suspend fun appendOutbox(entity: OutboxEntity): Long {
        val id = nextOutboxId++
        outbox.value = outbox.value + entity.copy(id = id)
        return id
    }

    override suspend fun nextOutbox(familyId: String, limit: Int) = outbox.value.filter { it.familyId == familyId }.take(limit)

    override suspend fun outboxFor(familyId: String, collection: String, docId: String) =
        outbox.value.filter { it.familyId == familyId && it.collection == collection && it.docId == docId }

    override suspend fun replaceOutboxFields(id: Long, fieldsJson: String) {
        outbox.value = outbox.value.map { if (it.id == id) it.copy(fieldsJson = fieldsJson) else it }
    }

    override suspend fun deleteOutbox(id: Long) {
        outbox.value = outbox.value.filterNot { it.id == id }
    }

    override suspend fun markOutboxFailed(id: Long, error: String) {
        outbox.value = outbox.value.map { if (it.id == id) it.copy(attempts = it.attempts + 1, lastError = error) else it }
    }

    override fun observeOutboxCount(familyId: String): Flow<Int> = outbox.map { list -> list.count { it.familyId == familyId } }

    override suspend fun watermark(familyId: String, path: String) = watermarks[familyId to path]

    override suspend fun setWatermark(familyId: String, path: String, serverTime: Long) {
        watermarks[familyId to path] = serverTime
    }

    override suspend fun deleteFamily(familyId: String) {
        docs.value = docs.value.filterKeys { it.familyId != familyId }
        outbox.value = outbox.value.filterNot { it.familyId == familyId }
        watermarks.keys.removeAll { it.first == familyId }
    }

    override suspend fun <T> transaction(block: suspend () -> T): T = mutex.withLock { block() }
}
