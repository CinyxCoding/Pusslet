package se.pusslet.data.sync

import co.touchlab.kermit.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import se.pusslet.data.db.DocumentEntity
import se.pusslet.data.db.OutboxEntity
import se.pusslet.data.session.SessionProvider
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** JSON used for domain documents: tolerant reads, explicit defaults (queried fields must exist), no nulls. */
val DomainJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

/**
 * The repositories' gateway to local data. Reads come from Room; writes go to Room immediately (the UI updates
 * instantly, offline or not) and queue a field-level change in the outbox for the sync engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentStore(
    private val local: SyncLocalStore,
    private val sessions: SessionProvider,
    private val clock: Clock = Clock.System,
    private val json: Json = DomainJson,
) {
    private val log = Logger.withTag("DocumentStore")

    /** Signalled after every local write so the sync engine pushes promptly. Conflated: no signal is ever lost. */
    val changeSignal = Channel<Unit>(Channel.CONFLATED)

    private val familyIdFlow: Flow<String?> = sessions.session.map { it.familyId }.distinctUntilChanged()

    fun <T> observeAll(spec: CollectionSpec, serializer: KSerializer<T>): Flow<List<T>> = familyIdFlow.flatMapLatest { fid ->
        if (fid ==
            null
        ) {
            flowOf(emptyList())
        } else {
            local.observeCollection(fid, spec.key).map { rows -> rows.mapNotNull { decode(it, serializer) } }
        }
    }

    fun <T> observe(spec: CollectionSpec, id: String, serializer: KSerializer<T>): Flow<T?> = familyIdFlow.flatMapLatest { fid ->
        if (fid == null) {
            flowOf(null)
        } else {
            local.observeDoc(fid, spec.key, id).map { row -> row?.takeIf { it.deletedAt == null }?.let { decode(it, serializer) } }
        }
    }

    suspend fun <T> getAll(spec: CollectionSpec, serializer: KSerializer<T>): List<T> {
        val fid = sessions.session.value.familyId ?: return emptyList()
        return local.list(fid, spec.key).mapNotNull { decode(it, serializer) }
    }

    suspend fun <T> get(spec: CollectionSpec, id: String, serializer: KSerializer<T>): T? {
        val fid = sessions.session.value.familyId ?: return null
        return local.get(fid, spec.key, id)?.takeIf { it.deletedAt == null }?.let { decode(it, serializer) }
    }

    /** Creates or replaces a document. Only changed fields are queued for upload. */
    suspend fun <T> put(spec: CollectionSpec, id: String, value: T, serializer: KSerializer<T>, familyId: String? = null) {
        val fid = familyId ?: sessions.session.value.familyId ?: error("No family selected")
        val newJson = json.encodeToJsonElement(serializer, value).jsonObject
        writeJson(fid, spec, id, newJson)
    }

    suspend fun <T> update(spec: CollectionSpec, id: String, serializer: KSerializer<T>, transform: (T) -> T): T? {
        val current = get(spec, id, serializer) ?: return null
        val next = transform(current)
        put(spec, id, next, serializer)
        return next
    }

    /**
     * Removes what was deleted more than [PURGE_AFTER] ago from this phone (ADR-046), as the server does with its copy.
     * Documents with changes still waiting to be sent stay until they are sent.
     */
    suspend fun purgeDeleted(): Int = local.purgeDeleted((clock.now() - PURGE_AFTER).toEpochMilliseconds())

    /** Soft delete: the document is hidden everywhere and the deletion syncs to other devices. */
    suspend fun delete(spec: CollectionSpec, id: String) {
        val fid = sessions.session.value.familyId ?: return
        val now = clock.now().toEpochMilliseconds()
        local.transaction {
            val existing = local.get(fid, spec.key, id) ?: return@transaction
            local.upsert(existing.copy(deletedAt = now, pending = true, localUpdatedAt = now))
            enqueue(fid, spec, id, mapOf(MetaFields.DELETED_AT to JsonPrimitive(MetaFields.SERVER_TIMESTAMP)), now)
        }
        changeSignal.trySend(Unit)
    }

    private suspend fun writeJson(fid: String, spec: CollectionSpec, id: String, newJson: JsonObject) {
        val now = clock.now().toEpochMilliseconds()
        local.transaction {
            val existing = local.get(fid, spec.key, id)
            val oldJson = existing?.let { runCatching { json.parseToJsonElement(it.json).jsonObject }.getOrNull() }
            val revived = existing?.deletedAt != null
            val diff = JsonPaths.diff(if (revived) null else oldJson, newJson, spec.mapFields).toMutableMap()
            // A new document clears any deletion the server still has under its id: this phone may have purged the
            // tombstone (fixed ids such as a week's meal plan come back), and the purge's marker must not keep it deleted.
            if (revived || existing == null) {
                diff[MetaFields.DELETED_AT] = null
                diff[MetaFields.PURGED_AT] = null
            }
            if (diff.isEmpty() && existing != null) return@transaction
            local.upsert(
                DocumentEntity(
                    familyId = fid,
                    collection = spec.key,
                    docId = id,
                    json = newJson.toString(),
                    updatedAt = existing?.updatedAt,
                    updatedBy = existing?.updatedBy,
                    deletedAt = null,
                    pending = true,
                    localUpdatedAt = now,
                ),
            )
            enqueue(fid, spec, id, diff, now)
        }
        changeSignal.trySend(Unit)
    }

    /** Appends to the outbox, folding into the newest unsent entry for the same document when possible. */
    private suspend fun enqueue(fid: String, spec: CollectionSpec, id: String, fields: Map<String, JsonElement?>, now: Long) {
        if (fields.isEmpty()) return
        val last = local.outboxFor(fid, spec.key, id).lastOrNull()
        if (last != null && last.attempts == 0) {
            val combined = JsonPaths.combine(JsonPaths.decode(json.parseToJsonElement(last.fieldsJson).jsonObject), fields)
            local.replaceOutboxFields(last.id, JsonPaths.encode(combined).toString())
        } else {
            local.appendOutbox(
                OutboxEntity(
                    familyId = fid,
                    collection = spec.key,
                    docId = id,
                    fieldsJson = JsonPaths.encode(fields).toString(),
                    createdAt = now,
                ),
            )
        }
    }

    private fun <T> decode(row: DocumentEntity, serializer: KSerializer<T>): T? = try {
        json.decodeFromString(serializer, row.json)
    } catch (e: IllegalArgumentException) {
        // Never crash the UI on one bad document (e.g. written by a newer app version); log without content.
        log.w { "Skipping undecodable ${row.collection} document: ${e::class.simpleName}" }
        null
    }

    companion object {
        /** As `STRIP_AFTER_DAYS` in functions/src/purge.ts: phone and server forget a deletion after the same time. */
        val PURGE_AFTER: Duration = 30.days
    }
}
