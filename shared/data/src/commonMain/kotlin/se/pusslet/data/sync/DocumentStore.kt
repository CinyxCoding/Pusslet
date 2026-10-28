package se.pusslet.data.sync

import co.touchlab.kermit.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
import se.pusslet.data.session.Session
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
 *
 * A family may share spaces with another home (ADR-030). For [SpaceSharing.Merged] collections the spaces' documents
 * appear next to the family's own, writes go back to wherever the document lives, and a new document about a shared
 * child is placed in that child's space.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentStore(
    private val local: SyncLocalStore,
    private val sessions: SessionProvider,
    private val clock: Clock = Clock.System,
    private val json: Json = DomainJson,
) {
    private val log = Logger.withTag("DocumentStore")
    private val placement = SpacePlacement(local, json)

    /** Signalled after every local write so the sync engine pushes promptly. Conflated: no signal is ever lost. */
    val changeSignal = Channel<Unit>(Channel.CONFLATED)

    private data class Roots(val home: String, val spaces: List<String>) {
        /** Where documents of [spec] are read from, the home first. */
        fun of(spec: CollectionSpec): List<String> = if (spec.spaces == SpaceSharing.Merged) listOf(home) + spaces else listOf(home)
    }

    private fun rootsOf(s: Session): Roots? = s.familyId?.let { Roots(it, s.spaceIds.filter { id -> id != it }) }

    private val rootsFlow: Flow<Roots?> = sessions.session.map(::rootsOf).distinctUntilChanged()

    private fun roots(): Roots? = rootsOf(sessions.session.value)

    fun <T> observeAll(spec: CollectionSpec, serializer: KSerializer<T>): Flow<List<T>> = rootsFlow.flatMapLatest { r ->
        if (r == null) flowOf(emptyList()) else observeRows(r.of(spec), spec).map { rows -> rows.mapNotNull { decode(it, serializer) } }
    }

    /** Documents of [spec] in the shared spaces only (the space documents, the other home's adults). */
    fun <T> observeSpaces(spec: CollectionSpec, serializer: KSerializer<T>): Flow<List<T>> = rootsFlow.flatMapLatest { r ->
        if (r == null || r.spaces.isEmpty()) {
            flowOf(emptyList())
        } else {
            observeRows(r.spaces, spec).map { rows -> rows.mapNotNull { decode(it, serializer) } }
        }
    }

    /** The documents of [spec] in one root: the family or a shared space. */
    fun <T> observeIn(root: String, spec: CollectionSpec, serializer: KSerializer<T>): Flow<List<T>> =
        local.observeCollection(root, spec.key).map { rows -> rows.mapNotNull { decode(it, serializer) } }

    suspend fun <T> getAllIn(root: String, spec: CollectionSpec, serializer: KSerializer<T>): List<T> =
        local.list(root, spec.key).mapNotNull { decode(it, serializer) }

    /** One row per document id; a space's copy wins over the home's (the home's is a moved document's tombstone). */
    private fun observeRows(roots: List<String>, spec: CollectionSpec): Flow<List<DocumentEntity>> = if (roots.size == 1) {
        local.observeCollection(roots.single(), spec.key)
    } else {
        combine(
            roots.map {
                local.observeCollection(it, spec.key)
            },
        ) { perRoot -> perRoot.toList().flatten().associateBy { it.docId }.values.toList() }
    }

    fun <T> observe(spec: CollectionSpec, id: String, serializer: KSerializer<T>): Flow<T?> = rootsFlow.flatMapLatest { r ->
        if (r == null) {
            flowOf(null)
        } else {
            val roots = r.of(spec)
            val rows = if (roots.size == 1) {
                local.observeDoc(roots.single(), spec.key, id).map { listOf(it) }
            } else {
                combine(roots.map { local.observeDoc(it, spec.key, id) }) { it.toList() }
            }
            rows.map { all -> all.lastOrNull { it != null && it.deletedAt == null }?.let { decode(it, serializer) } }
        }
    }

    suspend fun <T> getAll(spec: CollectionSpec, serializer: KSerializer<T>): List<T> {
        val r = roots() ?: return emptyList()
        return r.of(spec).flatMap { local.list(it, spec.key) }.associateBy { it.docId }.values.mapNotNull { decode(it, serializer) }
    }

    suspend fun <T> get(spec: CollectionSpec, id: String, serializer: KSerializer<T>): T? {
        val root = rootOf(spec, id) ?: return null
        return local.get(root, spec.key, id)?.takeIf { it.deletedAt == null }?.let { decode(it, serializer) }
    }

    /** A document of a space (or the family) itself, e.g. the space document or the other home's adults. */
    suspend fun <T> getIn(root: String, spec: CollectionSpec, id: String, serializer: KSerializer<T>): T? =
        local.get(root, spec.key, id)?.takeIf { it.deletedAt == null }?.let { decode(it, serializer) }

    /** Where the live document [id] is: a shared space, else the family (also for documents that do not exist yet). */
    suspend fun rootOf(spec: CollectionSpec, id: String): String? {
        val r = roots() ?: return null
        return r.of(spec).drop(1).lastOrNull { isLive(it, spec, id) } ?: r.home
    }

    private suspend fun isLive(root: String, spec: CollectionSpec, id: String): Boolean =
        local.get(root, spec.key, id)?.deletedAt == null &&
            local.get(root, spec.key, id) != null

    /** True when the document lives in a space shared with the other home. */
    suspend fun isShared(spec: CollectionSpec, id: String): Boolean {
        val r = roots() ?: return false
        return rootOf(spec, id) != r.home
    }

    /**
     * Creates or replaces a document. Only changed fields are queued for upload. An existing document stays where it is;
     * a new one about a shared child goes to that child's space unless [familyId] says where.
     */
    suspend fun <T> put(spec: CollectionSpec, id: String, value: T, serializer: KSerializer<T>, familyId: String? = null) {
        val fid = familyId ?: placeFor(spec, id, value) ?: error("No family selected")
        val newJson = json.encodeToJsonElement(serializer, value).jsonObject
        local.transaction { writeJsonLocked(fid, spec, id, newJson) }
        changeSignal.trySend(Unit)
    }

    private suspend fun placeFor(spec: CollectionSpec, id: String, value: Any?): String? {
        val r = roots() ?: return null
        val live = r.of(spec).lastOrNull { isLive(it, spec, id) }
        if (live != null || r.spaces.isEmpty()) return live ?: r.home
        return placement.spaceFor(r.home, r.spaces, spec, value) ?: r.home
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
        val fid = rootOf(spec, id) ?: return
        local.transaction { deleteLocked(fid, spec, id) }
        changeSignal.trySend(Unit)
    }

    /**
     * Moves a document between the family and a shared space (or back): written whole in [toRoot], soft-deleted where it
     * was. The id stays, so everything that points at it keeps working.
     */
    suspend fun move(spec: CollectionSpec, id: String, toRoot: String) {
        val from = rootOf(spec, id) ?: return
        if (from == toRoot) return
        local.transaction {
            val row = local.get(from, spec.key, id)?.takeIf { it.deletedAt == null } ?: return@transaction
            val body = runCatching { json.parseToJsonElement(row.json).jsonObject }.getOrNull() ?: return@transaction
            writeJsonLocked(toRoot, spec, id, body)
            deleteLocked(from, spec, id)
        }
        changeSignal.trySend(Unit)
    }

    /**
     * Copies every live document of the [SpaceSharing.Merged] collections in [space] into the family (leaving a shared
     * space keeps a private copy of the children's things). Returns how many documents were copied.
     */
    suspend fun copySpaceToHome(space: String): Int {
        val home = roots()?.home ?: return 0
        var copied = 0
        local.transaction {
            for (spec in Collections.inSpaces.filter { it.spaces == SpaceSharing.Merged }) {
                for (row in local.list(space, spec.key)) {
                    val body = runCatching { json.parseToJsonElement(row.json).jsonObject }.getOrNull() ?: continue
                    writeJsonLocked(home, spec, row.docId, body)
                    copied++
                }
            }
        }
        changeSignal.trySend(Unit)
        return copied
    }

    /**
     * Points every document of the family at [newId] instead of [oldId] (a child of ours that is the same child as the
     * other home's): values, map keys and document ids that contain the id. Ids are random UUIDs, so a plain text
     * replacement cannot hit anything else. The old document of [spec] is deleted.
     */
    suspend fun replaceId(spec: CollectionSpec, oldId: String, newId: String) {
        require(oldId.length >= MIN_ID_LENGTH) { "Refusing to replace a short id" }
        val home = roots()?.home ?: return
        local.transaction {
            val affected = local.listFamily(home).filter { row ->
                row.deletedAt == null && !(row.collection == spec.key && row.docId == oldId) && (oldId in row.json || oldId in row.docId)
            }
            for (row in affected) {
                val rowSpec = Collections.byKey(row.collection)
                val body = runCatching { json.parseToJsonElement(row.json.replace(oldId, newId)).jsonObject }.getOrNull()
                if (rowSpec == null || body == null) continue
                val docId = row.docId.replace(oldId, newId)
                writeJsonLocked(home, rowSpec, docId, body)
                if (docId != row.docId) deleteLocked(home, rowSpec, row.docId)
            }
            if (local.get(home, spec.key, oldId) != null) deleteLocked(home, spec, oldId)
        }
        changeSignal.trySend(Unit)
    }

    private suspend fun deleteLocked(fid: String, spec: CollectionSpec, id: String) {
        val now = clock.now().toEpochMilliseconds()
        val existing = local.get(fid, spec.key, id) ?: return
        local.upsert(existing.copy(deletedAt = now, pending = !spec.localOnly, localUpdatedAt = now))
        enqueue(fid, spec, id, mapOf(MetaFields.DELETED_AT to JsonPrimitive(MetaFields.SERVER_TIMESTAMP)), now)
    }

    private suspend fun writeJsonLocked(fid: String, spec: CollectionSpec, id: String, newJson: JsonObject) {
        val now = clock.now().toEpochMilliseconds()
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
        if (diff.isEmpty() && existing != null) return
        local.upsert(
            DocumentEntity(
                familyId = fid,
                collection = spec.key,
                docId = id,
                json = newJson.toString(),
                updatedAt = existing?.updatedAt,
                updatedBy = existing?.updatedBy,
                deletedAt = null,
                pending = !spec.localOnly,
                localUpdatedAt = now,
            ),
        )
        enqueue(fid, spec, id, diff, now)
    }

    /** Appends to the outbox, folding into the newest unsent entry for the same document when possible. */
    private suspend fun enqueue(fid: String, spec: CollectionSpec, id: String, fields: Map<String, JsonElement?>, now: Long) {
        if (fields.isEmpty() || spec.localOnly) return
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

        private const val MIN_ID_LENGTH = 16
    }
}
