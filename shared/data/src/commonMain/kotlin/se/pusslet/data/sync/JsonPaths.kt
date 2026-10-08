package se.pusslet.data.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Field-level diffs between two versions of a document. Regular fields are compared as a whole; fields in [mapFields]
 * are compared per key and produce dotted paths ("entries.2026-10-12_dinner") so two people can edit different keys.
 * A null value means "delete this field".
 */
object JsonPaths {
    fun diff(old: JsonObject?, new: JsonObject, mapFields: Set<String>): Map<String, JsonElement?> {
        val result = LinkedHashMap<String, JsonElement?>()
        val keys = (old?.keys.orEmpty() + new.keys).filter { it !in MetaFields.all }
        for (key in keys) {
            val before = old?.get(key)
            val after = new[key]
            if (key in mapFields && (before == null || before is JsonObject) && (after == null || after is JsonObject)) {
                val b = before ?: JsonObject(emptyMap())
                val a = after ?: JsonObject(emptyMap())
                for (sub in (b.keys + a.keys)) {
                    if (b[sub] != a[sub]) result["$key.$sub"] = a[sub]
                }
            } else if (before != after) {
                result[key] = after
            }
        }
        return result
    }

    /** Applies dotted-path changes on top of [base] (used to keep local pending edits over incoming server data). */
    fun overlay(base: JsonObject, changes: Map<String, JsonElement?>): JsonObject {
        var result = base
        for ((path, value) in changes) result = set(result, path.split('.'), value)
        return result
    }

    private fun set(obj: JsonObject, path: List<String>, value: JsonElement?): JsonObject {
        val head = path.first()
        val map = obj.toMutableMap()
        if (path.size == 1) {
            if (value == null || value == JsonNull) map.remove(head) else map[head] = value
        } else {
            val child = map[head] as? JsonObject ?: JsonObject(emptyMap())
            map[head] = set(child, path.drop(1), value)
        }
        return JsonObject(map)
    }

    /** Combines two change sets for the same document (later wins). */
    fun combine(first: Map<String, JsonElement?>, second: Map<String, JsonElement?>): Map<String, JsonElement?> {
        val result = LinkedHashMap(first)
        for ((path, value) in second) {
            // A whole-field write replaces earlier writes to its sub-paths.
            result.keys.removeAll { it.startsWith("$path.") }
            result[path] = value
        }
        return result
    }

    fun encode(changes: Map<String, JsonElement?>): JsonObject = buildJsonObject {
        changes.forEach { (k, v) -> put(k, v ?: JsonNull) }
    }

    fun decode(obj: JsonObject): Map<String, JsonElement?> = obj.mapValues { (_, v) -> if (v == JsonNull) null else v }

    fun withoutMeta(data: JsonObject): JsonObject = JsonObject(data.filterKeys { it !in MetaFields.all })

    fun millis(element: JsonElement?): Long? = (element as? JsonPrimitive)?.longOrNull
}
