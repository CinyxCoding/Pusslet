package se.pusslet.data.repository

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import se.pusslet.core.model.AttachmentOwner
import se.pusslet.core.model.LocalAttachment
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sync.Collections
import se.pusslet.data.sync.DocumentStore
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** The phone's private folder of attached pictures. */
interface AttachmentFiles {
    suspend fun delete(fileName: String)

    suspend fun deleteAll()

    /** File name -> last modified. */
    suspend fun list(): Map<String, Instant>
}

object NoAttachmentFiles : AttachmentFiles {
    override suspend fun delete(fileName: String) = Unit

    override suspend fun deleteAll() = Unit

    override suspend fun list(): Map<String, Instant> = emptyMap()
}

/** Pictures attached to events and notes – on this phone only (local-only collection, ADR-049). */
class AttachmentRepository(
    private val docs: DocumentStore,
    private val files: AttachmentFiles,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    private val log = Logger.withTag("Attachments")

    val all: Flow<List<LocalAttachment>> = docs.observeAll(Collections.Attachments, LocalAttachment.serializer())

    fun forOwner(owner: AttachmentOwner, ownerId: String): Flow<List<LocalAttachment>> = all.map { list ->
        list.filter { it.owner == owner && it.ownerId == ownerId }.sortedBy { it.addedAt }
    }

    suspend fun attach(owner: AttachmentOwner, ownerId: String, fileName: String, mimeType: String = "image/jpeg"): LocalAttachment {
        val a = LocalAttachment(ids.newId(), owner, ownerId, fileName, mimeType, clock.now())
        docs.put(Collections.Attachments, a.id, a, LocalAttachment.serializer())
        return a
    }

    suspend fun remove(attachment: LocalAttachment) {
        docs.delete(Collections.Attachments, attachment.id)
        files.delete(attachment.fileName)
    }

    /** A shared picture the user decided not to keep. */
    suspend fun discard(fileName: String) = files.delete(fileName)

    /**
     * Removes pictures whose event or note is gone, and files nothing has pointed at for a day (an app closed in the
     * middle of sharing; younger files may be a share in progress). [eventIds] and [taskIds] are everything that exists.
     */
    suspend fun cleanUp(eventIds: Set<String>, taskIds: Set<String>) {
        try {
            val attachments = all.first()
            attachments.filter { a ->
                when (a.owner) {
                    AttachmentOwner.Event -> a.ownerId !in eventIds
                    AttachmentOwner.Task -> a.ownerId !in taskIds
                }
            }.forEach { remove(it) }
            val kept = attachments.map { it.fileName }.toSet()
            val dayAgo = clock.now() - 1.days
            files.list().filter { (name, modified) -> name !in kept && modified < dayAgo }.keys.forEach { files.delete(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "Attachment clean-up failed: ${e::class.simpleName}" }
        }
    }
}

/** Run at start-up: pictures of events and notes that were deleted (here or on another phone) are removed. */
class AttachmentHousekeeping(
    private val attachments: AttachmentRepository,
    private val calendar: CalendarRepository,
    private val tasks: TaskRepository,
    private val sessions: SessionProvider,
) {
    suspend fun run() {
        sessions.loaded.first { it }
        if (sessions.session.value.familyId == null) return
        attachments.cleanUp(calendar.events.first().map { it.id }.toSet(), tasks.tasks.first().map { it.id }.toSet())
    }
}
