package se.pusslet.data.remote

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import se.pusslet.data.sync.MetaFields
import se.pusslet.data.sync.RemoteDoc
import se.pusslet.data.sync.RemoteFilter
import se.pusslet.data.sync.RemotePermissionException
import se.pusslet.data.sync.RemoteStore
import java.util.Date

/**
 * Firestore implementation of [RemoteStore] using the native Android SDK (ADR-006).
 * Writes use `set(..., mergeFieldPaths)` so exactly the changed fields (or map keys) are replaced.
 */
class FirestoreRemoteStore(private val db: FirebaseFirestore) : RemoteStore {
    override fun listen(
        familyId: String,
        path: String,
        sinceServerTime: Long?,
        filter: RemoteFilter?,
    ): Flow<List<RemoteDoc>> = callbackFlow {
        val registration = if (path.isEmpty()) {
            db.document("families/$familyId").addSnapshotListener(MetadataChanges.EXCLUDE) { snap, error ->
                when {
                    error != null -> close(translate(error))
                    snap == null || snap.metadata.hasPendingWrites() -> Unit
                    !snap.exists() -> trySend(listOf(RemoteDoc(familyId, JsonObject(emptyMap()), null, removed = true)))
                    else -> trySend(listOf(RemoteDoc(familyId, toJson(snap.data.orEmpty()), millis(snap.get(MetaFields.UPDATED_AT)))))
                }
            }
        } else {
            var query: Query = db.collection("families/$familyId/$path")
            if (filter != null) query = query.whereEqualTo(filter.field, filter.equalsTo)
            if (sinceServerTime != null) query = query.whereGreaterThan(MetaFields.UPDATED_AT, Timestamp(Date(sinceServerTime)))
            query.addSnapshotListener(MetadataChanges.EXCLUDE) { snap, error ->
                if (error != null) {
                    close(translate(error))
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                val docs = snap.documentChanges.filter { !it.document.metadata.hasPendingWrites() }.map { change ->
                    val d = change.document
                    if (change.type == DocumentChange.Type.REMOVED) {
                        RemoteDoc(d.id, JsonObject(emptyMap()), null, removed = true)
                    } else {
                        RemoteDoc(d.id, toJson(d.data), millis(d.get(MetaFields.UPDATED_AT)))
                    }
                }
                if (docs.isNotEmpty() || !snap.metadata.isFromCache) trySend(docs)
            }
        }
        awaitClose { registration.remove() }
    }

    override suspend fun write(familyId: String, docPath: String, fields: Map<String, JsonElement?>, uid: String) {
        val ref = db.document(if (docPath.isEmpty()) "families/$familyId" else "families/$familyId/$docPath")
        val data = HashMap<String, Any?>()
        val paths = ArrayList<FieldPath>()
        for ((path, value) in fields) {
            val segments = path.split('.')
            paths += FieldPath.of(*segments.toTypedArray())
            put(data, segments, toFirestore(value))
        }
        data[MetaFields.UPDATED_AT] = FieldValue.serverTimestamp()
        data[MetaFields.UPDATED_BY] = uid
        paths += FieldPath.of(MetaFields.UPDATED_AT)
        paths += FieldPath.of(MetaFields.UPDATED_BY)
        try {
            ref.set(data, SetOptions.mergeFieldPaths(paths)).await()
        } catch (e: FirebaseFirestoreException) {
            throw translate(e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun put(target: MutableMap<String, Any?>, segments: List<String>, value: Any?) {
        if (segments.size == 1) {
            target[segments[0]] = value
            return
        }
        val child = target.getOrPut(segments[0]) { HashMap<String, Any?>() } as MutableMap<String, Any?>
        put(child, segments.drop(1), value)
    }

    private fun toFirestore(value: JsonElement?): Any? = when (value) {
        null, JsonNull -> FieldValue.delete()
        is JsonPrimitive -> when {
            value.isString && value.content == MetaFields.SERVER_TIMESTAMP -> FieldValue.serverTimestamp()
            value.isString -> value.content
            value.booleanOrNull != null -> value.booleanOrNull
            value.longOrNull != null -> value.longOrNull
            else -> value.doubleOrNull
        }
        is JsonObject -> value.mapValues { (_, v) -> plain(v) }
        is JsonArray -> value.map { plain(it) }
    }

    companion object {
        /** Nested values: nulls stay null (FieldValue.delete is only valid at the top of a merge path). */
        fun plain(value: JsonElement): Any? = when (value) {
            JsonNull -> null
            is JsonPrimitive -> when {
                value.isString -> value.content
                value.booleanOrNull != null -> value.booleanOrNull
                value.longOrNull != null -> value.longOrNull
                else -> value.doubleOrNull
            }
            is JsonObject -> value.mapValues { (_, v) -> plain(v) }
            is JsonArray -> value.map { plain(it) }
        }

        fun toPlain(obj: JsonObject): Map<String, Any?> = obj.mapValues { (_, v) -> plain(v) }

        fun toJson(map: Map<String, Any?>): JsonObject = JsonObject(map.mapValues { (_, v) -> toJsonElement(v) })

        @Suppress("UNCHECKED_CAST")
        fun toJsonElement(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is String -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Long -> JsonPrimitive(v)
            is Int -> JsonPrimitive(v)
            is Double -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v.toDouble())
            is Timestamp -> JsonPrimitive(v.toDate().time)
            is Map<*, *> -> JsonObject((v as Map<String, Any?>).mapValues { (_, x) -> toJsonElement(x) })
            is List<*> -> JsonArray(v.map { toJsonElement(it) })
            else -> JsonPrimitive(v.toString())
        }

        fun millis(v: Any?): Long? = (v as? Timestamp)?.toDate()?.time

        fun translate(e: FirebaseFirestoreException): Exception =
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) RemotePermissionException("permission denied", e) else e
    }
}
