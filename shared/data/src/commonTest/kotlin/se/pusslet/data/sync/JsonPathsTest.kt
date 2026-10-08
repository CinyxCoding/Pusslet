package se.pusslet.data.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonPathsTest {
    private val old = buildJsonObject {
        put("title", "Fotboll")
        put("location", "Vallen")
        putJsonObject("entries") {
            putJsonObject("mon") { put("title", "Tacos") }
            putJsonObject("tue") { put("title", "Fisk") }
        }
    }

    @Test
    fun diffReportsChangedRemovedAndAddedFields() {
        val new = buildJsonObject {
            put("title", "Match")
            put("note", "Ta med vatten")
            put("entries", old["entries"]!!)
        }
        val diff = JsonPaths.diff(old, new, emptySet())
        assertEquals(mapOf("title" to JsonPrimitive("Match"), "location" to null, "note" to JsonPrimitive("Ta med vatten")), diff)
    }

    @Test
    fun mapFieldsDiffPerKey() {
        val new = buildJsonObject {
            put("title", "Fotboll")
            put("location", "Vallen")
            putJsonObject("entries") {
                putJsonObject("mon") { put("title", "Tacos") }
                putJsonObject("wed") { put("title", "Soppa") }
            }
        }
        val diff = JsonPaths.diff(old, new, setOf("entries"))
        assertEquals(setOf("entries.tue", "entries.wed"), diff.keys)
        assertEquals(null, diff["entries.tue"])
    }

    @Test
    fun overlayAppliesNestedPathsAndDeletes() {
        val result = JsonPaths.overlay(old, mapOf("entries.tue" to null, "entries.thu" to JsonPrimitive("x"), "location" to null))
        val entries = result["entries"] as JsonObject
        assertEquals(setOf("mon", "thu"), entries.keys)
        assertEquals(null, result["location"])
    }

    @Test
    fun combineLetsWholeFieldWritesReplaceEarlierSubPaths() {
        val combined = JsonPaths.combine(mapOf("entries.mon" to JsonPrimitive(1)), mapOf("entries" to JsonPrimitive(2), "title" to JsonPrimitive("t")))
        assertEquals(mapOf("entries" to JsonPrimitive(2), "title" to JsonPrimitive("t")), combined)
    }

    @Test
    fun metaFieldsAreIgnored() {
        val withMeta = JsonObject(old + (MetaFields.UPDATED_AT to JsonPrimitive(5)))
        assertEquals(emptyMap(), JsonPaths.diff(old, withMeta, emptySet()))
    }
}
