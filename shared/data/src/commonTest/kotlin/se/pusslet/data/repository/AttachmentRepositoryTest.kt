package se.pusslet.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import se.pusslet.core.model.AttachmentOwner
import se.pusslet.core.model.Role
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Pictures kept on this phone only (ADR-049). */
class AttachmentRepositoryTest {
    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-10-13T06:00:00Z")
    }
    private val local = InMemorySyncLocalStore()
    private val docs = DocumentStore(local, FixedSessionProvider(Session("f", "m-sara", "u", Role.Parent)), clock)
    private val files = MemoryFiles()
    private val repo = AttachmentRepository(docs, files, SequentialIdGenerator(), clock)

    private class MemoryFiles : AttachmentFiles {
        val stored = mutableMapOf<String, Instant>()
        val deleted = mutableListOf<String>()

        override suspend fun delete(fileName: String) {
            stored.remove(fileName)
            deleted += fileName
        }

        override suspend fun deleteAll() {
            deleted += stored.keys
            stored.clear()
        }

        override suspend fun list(): Map<String, Instant> = stored.toMap()
    }

    @Test
    fun aPictureNeverSyncs() = runTest {
        files.stored["letter.jpg"] = clock.now()
        val a = repo.attach(AttachmentOwner.Event, "school", "letter.jpg")
        assertEquals(listOf(a), repo.forOwner(AttachmentOwner.Event, "school").first())
        assertTrue(local.outboxFor("f", "attachments", a.id).isEmpty(), "nothing is queued for upload")
        assertTrue(local.listFamily("f").none { it.collection == "attachments" && it.pending })

        repo.remove(a)
        assertTrue(local.outboxFor("f", "attachments", a.id).isEmpty(), "not even the deletion")
        assertEquals(listOf("letter.jpg"), files.deleted)
        assertTrue(repo.all.first().isEmpty())
    }

    @Test
    fun cleanUpRemovesPicturesOfDeletedThingsAndOldStrayFiles() = runTest {
        listOf("kept.jpg", "gone.jpg", "stray-new.jpg").forEach { files.stored[it] = clock.now() }
        files.stored["stray-old.jpg"] = clock.now() - 2.days
        repo.attach(AttachmentOwner.Task, "note", "kept.jpg")
        repo.attach(AttachmentOwner.Event, "deleted-event", "gone.jpg")

        repo.cleanUp(eventIds = emptySet(), taskIds = setOf("note"))

        assertEquals(setOf("kept.jpg", "stray-new.jpg"), files.stored.keys, "a fresh file may be a share in progress")
        assertEquals(listOf("kept.jpg"), repo.all.first().map { it.fileName })
    }
}
