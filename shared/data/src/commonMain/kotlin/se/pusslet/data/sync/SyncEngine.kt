package se.pusslet.data.sync

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import se.pusslet.core.model.Role
import se.pusslet.data.db.DocumentEntity
import kotlin.math.min
import kotlin.time.Clock

enum class SyncState { Disabled, Connecting, Synced, Pushing, Offline, Error }

data class SyncStatus(
    val state: SyncState = SyncState.Disabled,
    val pendingWrites: Int = 0,
    val lastSyncedAt: Long? = null,
    /** Changes the server refused (security rules); kept locally, never retried. */
    val rejectedWrites: Int = 0,
    /** Non-personal description of the last problem, for the status view. */
    val lastError: String? = null,
)

/**
 * Mirrors the local document store to the backend.
 *
 * - **Incoming:** one listener per collection (and per parent for nested collections such as shopping items),
 *   incremental from a per-path watermark. Remote values replace local ones except fields with pending local edits.
 * - **Outgoing:** the outbox is pushed oldest-first; each entry contains only changed fields, so concurrent edits of
 *   different fields both survive and the same field resolves last-write-wins by server time.
 * - **Deletes** are soft (`deletedAt`), so they sync like any other change.
 */
class SyncEngine(
    private val local: SyncLocalStore,
    private val remote: RemoteStore,
    private val documents: DocumentStore,
    private val specs: List<CollectionSpec> = Collections.all,
    private val clock: Clock = Clock.System,
    private val json: Json = DomainJson,
) {
    private val log = Logger.withTag("Sync")
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private var job: Job? = null
    private val pushMutex = Mutex()
    private var current: Target? = null

    private data class Target(val familyId: String, val uid: String, val role: Role)

    /** Starts (or restarts) syncing the given family. Idempotent for the same target. */
    fun start(scope: CoroutineScope, familyId: String, uid: String, role: Role) {
        val target = Target(familyId, uid, role)
        if (target == current && job?.isActive == true) return
        stop()
        current = target
        _status.value = SyncStatus(SyncState.Connecting)
        job = scope.launch {
            launch { local.observeOutboxCount(familyId).collectLatest { n -> _status.update { it.copy(pendingWrites = n) } } }
            for (spec in specs.filter { it.visibleTo(role) }) {
                when (val loc = spec.location) {
                    RemoteLocation.FamilyRoot -> launch { listenLoop(target, spec, "", parentId = null) }
                    is RemoteLocation.Flat -> launch { listenLoop(target, spec, loc.path, parentId = null) }
                    is RemoteLocation.Nested -> launch {
                        local.observeCollection(familyId, loc.parentCollection)
                            .map { rows -> rows.map { it.docId }.sorted() }
                            .distinctUntilChanged()
                            .collectLatest { parentIds ->
                                coroutineScope {
                                    parentIds.forEach { pid ->
                                        launch { listenLoop(target, spec, "${loc.parentRemotePath}/$pid/${loc.subPath}", pid) }
                                    }
                                }
                            }
                    }
                }
            }
            launch { pushLoop(target) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        current = null
        _status.value = SyncStatus(SyncState.Disabled)
    }

    // region incoming

    private suspend fun listenLoop(target: Target, spec: CollectionSpec, path: String, parentId: String?) {
        var backoff = INITIAL_BACKOFF_MS
        val filter = if (target.role != Role.Parent &&
            spec.caregiverFilterField != null
        ) {
            RemoteFilter(spec.caregiverFilterField, true)
        } else {
            null
        }
        while (true) {
            try {
                val since = local.watermark(target.familyId, path)?.let { it - WATERMARK_OVERLAP_MS }
                remote.listen(target.familyId, path, since, filter).collect { batch ->
                    applyRemote(target.familyId, spec, path, batch, parentId)
                    backoff = INITIAL_BACKOFF_MS
                    _status.update { s ->
                        s.copy(
                            state = if (s.pendingWrites >
                                0
                            ) {
                                SyncState.Pushing
                            } else {
                                SyncState.Synced
                            },
                            lastSyncedAt = clock.now().toEpochMilliseconds(),
                            lastError = null,
                        )
                    }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemotePermissionException) {
                log.w(e) { "Listener for ${spec.key} denied by rules" }
                _status.update { it.copy(lastError = "permission:${spec.key}") }
                return
            } catch (e: Exception) {
                log.w { "Listener for ${spec.key} failed (${e::class.simpleName}), retrying in ${backoff}ms" }
                _status.update { it.copy(state = SyncState.Offline, lastError = e::class.simpleName) }
                delay(backoff)
                backoff = min(backoff * 2, MAX_BACKOFF_MS)
            }
        }
    }

    internal suspend fun applyRemote(familyId: String, spec: CollectionSpec, path: String, docs: List<RemoteDoc>, parentId: String?) {
        if (docs.isEmpty()) return
        var maxTime: Long? = null
        local.transaction {
            for (doc in docs) {
                val docId = if (spec.location == RemoteLocation.FamilyRoot) familyId else doc.docId
                val pending = local.outboxFor(familyId, spec.key, docId)
                if (doc.removed) {
                    if (pending.isEmpty()) local.hardDelete(familyId, spec.key, docId)
                    continue
                }
                doc.updatedAt?.let { t -> maxTime = maxOf(maxTime ?: t, t) }
                val existing = local.get(familyId, spec.key, docId)
                // Never go back in time (listeners may deliver an older snapshot after a newer change).
                if (existing?.updatedAt != null && doc.updatedAt != null && doc.updatedAt < existing.updatedAt) continue
                var data = JsonPaths.withoutMeta(doc.data)
                // Nested documents carry their parent id locally so queries ("items in list X") stay simple.
                val nested = spec.location as? RemoteLocation.Nested
                if (nested != null && parentId != null) data = JsonObject(data + (nested.parentField to JsonPrimitive(parentId)))
                val pendingFields = pending.fold(emptyMap<String, kotlinx.serialization.json.JsonElement?>()) { acc, e ->
                    JsonPaths.combine(acc, JsonPaths.decode(json.parseToJsonElement(e.fieldsJson).jsonObject))
                }
                val merged = if (pendingFields.isEmpty()) {
                    data
                } else {
                    JsonPaths.overlay(
                        data,
                        pendingFields.filterKeys {
                            it !in MetaFields.all
                        },
                    )
                }
                val remoteDeleted = JsonPaths.millis(doc.data[MetaFields.DELETED_AT])
                val localDeletePending = pendingFields.containsKey(MetaFields.DELETED_AT)
                local.upsert(
                    DocumentEntity(
                        familyId = familyId,
                        collection = spec.key,
                        docId = docId,
                        json = merged.toString(),
                        updatedAt = doc.updatedAt,
                        updatedBy = (doc.data[MetaFields.UPDATED_BY] as? JsonPrimitive)?.contentOrNull,
                        deletedAt = when {
                            localDeletePending -> pendingFields[MetaFields.DELETED_AT]?.let {
                                existing?.deletedAt
                                    ?: clock.now().toEpochMilliseconds()
                            }
                            else -> remoteDeleted
                        },
                        pending = pending.isNotEmpty(),
                        localUpdatedAt = existing?.localUpdatedAt ?: 0,
                    ),
                )
            }
            maxTime?.let { t ->
                val previous = local.watermark(familyId, path) ?: 0
                if (t > previous) local.setWatermark(familyId, path, t)
            }
        }
    }

    // endregion

    // region outgoing

    private suspend fun pushLoop(target: Target) {
        var backoff = INITIAL_BACKOFF_MS
        while (true) {
            val ok = pushPending(target)
            if (ok) {
                backoff = INITIAL_BACKOFF_MS
                // Wait for the next local change (or retry periodically to pick up anything missed).
                withTimeoutOrNull(PERIODIC_PUSH_MS) { documents.changeSignal.receive() }
            } else {
                delay(backoff)
                backoff = min(backoff * 2, MAX_BACKOFF_MS)
            }
        }
    }

    /** Pushes every queued change. Returns false when a retryable error stopped the run. */
    private suspend fun pushPending(target: Target): Boolean = pushMutex.withLock {
        var batch = local.nextOutbox(target.familyId, PUSH_BATCH)
        while (batch.isNotEmpty()) {
            _status.update { it.copy(state = SyncState.Pushing) }
            for (entry in batch) {
                val spec = specs.firstOrNull { it.key == entry.collection }
                val docPath = spec?.let { remoteDocPath(target.familyId, it, entry.docId) }
                if (spec == null || docPath == null) {
                    log.w { "Dropping outbox entry for unknown/unroutable collection ${entry.collection}" }
                    local.deleteOutbox(entry.id)
                    continue
                }
                val fields = JsonPaths.decode(json.parseToJsonElement(entry.fieldsJson).jsonObject)
                try {
                    remote.write(target.familyId, docPath, fields, target.uid)
                    local.transaction {
                        local.deleteOutbox(entry.id)
                        if (local.outboxFor(target.familyId, entry.collection, entry.docId).isEmpty()) {
                            local.clearPending(target.familyId, entry.collection, entry.docId)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RemotePermissionException) {
                    // Permanent: keep the local value, stop retrying this change.
                    log.w(e) { "Write to ${entry.collection} denied; dropping change" }
                    local.transaction {
                        local.deleteOutbox(entry.id)
                        if (local.outboxFor(target.familyId, entry.collection, entry.docId).isEmpty()) {
                            local.clearPending(target.familyId, entry.collection, entry.docId)
                        }
                    }
                    _status.update { it.copy(rejectedWrites = it.rejectedWrites + 1, lastError = "permission:${entry.collection}") }
                } catch (e: Exception) {
                    local.markOutboxFailed(entry.id, e::class.simpleName ?: "error")
                    _status.update { it.copy(state = SyncState.Offline, lastError = e::class.simpleName) }
                    return false
                }
            }
            batch = local.nextOutbox(target.familyId, PUSH_BATCH)
        }
        true
    }

    /** Pushes now (e.g. from a background worker). */
    suspend fun flush(): Boolean = current?.let { pushPending(it) } ?: false

    private suspend fun remoteDocPath(familyId: String, spec: CollectionSpec, docId: String): String? = when (val loc = spec.location) {
        RemoteLocation.FamilyRoot -> ""
        is RemoteLocation.Flat -> "${loc.path}/$docId"
        is RemoteLocation.Nested -> {
            val row = local.get(familyId, spec.key, docId)
            val parent = row?.let {
                runCatching { json.parseToJsonElement(it.json).jsonObject[loc.parentField] }.getOrNull()
            } as? JsonPrimitive
            parent?.contentOrNull?.let { "${loc.parentRemotePath}/$it/${loc.subPath}/$docId" }
        }
    }

    // endregion

    private companion object {
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
        const val WATERMARK_OVERLAP_MS = 5 * 60_000L
        const val PERIODIC_PUSH_MS = 60_000L
        const val PUSH_BATCH = 50
    }
}
