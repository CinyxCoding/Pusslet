package se.pusslet.core.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
enum class AttachmentOwner { Event, Task }

/**
 * A picture kept on this phone only (e.g. a photographed weekly letter shared from the camera roll). It never syncs:
 * photos can show children, names and addresses, and the family's cloud data stays small (ADR-049).
 */
@Serializable
data class LocalAttachment(
    val id: String,
    val owner: AttachmentOwner,
    /** The event (series id for recurring events) or task it belongs to. */
    val ownerId: String,
    /** File name in the app's private attachment folder. */
    val fileName: String,
    val mimeType: String = "image/jpeg",
    val addedAt: Instant? = null,
)
