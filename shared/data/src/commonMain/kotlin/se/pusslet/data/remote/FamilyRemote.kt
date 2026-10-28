package se.pusslet.data.remote

import kotlinx.serialization.json.JsonObject
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import kotlin.time.Instant

data class InviteInfo(
    val code: String,
    val familyId: String,
    val familyName: String,
    val role: Role,
    /** When set, accepting the invite claims this existing person ("Morfar Bengt") instead of creating a new one. */
    val memberId: String?,
    val expiresAt: Instant,
    /** An invite from the other home to a space shared for [children] (ADR-056), not to join the family. */
    val shared: Boolean = false,
    val children: List<SharedChildRef> = emptyList(),
    /** First name of the parent who invited ("Ahmed vill dela …"). */
    val fromName: String = "",
)

/** A child in an other-home invite: the id the space uses and the first name, so the invitee can match their own. */
data class SharedChildRef(val id: String, val name: String)

/** Thrown when the backend is not configured (local-only mode) or unreachable for an online-only operation. */
class BackendUnavailableException(message: String) : Exception(message)

/** Why a deletion on the server (callables `deleteFamily` / `deleteAccount` in functions/src/privacy.ts) did not go through. */
enum class DeletionRefusal {
    /** No signed-in account, or its token was refused. */
    NotSignedIn,

    /** Not a parent of the family, or not in it at all. */
    NotAllowed,

    /** The only parent while others in the family have accounts: delete the family or hand it over first. */
    LastParent,

    /** The backend wants a fresh sign-in before it deletes anything. */
    RecentLoginRequired,

    /** No connection, or the server did not answer. */
    Network,

    Unknown,
}

class DeletionRefusedException(val refusal: DeletionRefusal, cause: Throwable? = null) : Exception(refusal.name, cause)

/** Online-only family operations (everything else goes through the sync engine). */
interface FamilyRemote {
    val available: Boolean

    /**
     * Creates `families/{id}` (createdBy = uid) if missing and registers the caller as parent in the access index.
     * [shared] marks a new space shared with the other home; the rules never let it change afterwards.
     */
    suspend fun bootstrapFamily(familyId: String, name: String, memberId: String, uid: String, shared: Boolean = false)

    suspend fun createInvite(invite: InviteInfo, createdBy: String)

    suspend fun lookupInvite(code: String): InviteInfo?

    /** Writes the caller's access doc and member doc atomically. */
    suspend fun join(invite: InviteInfo, uid: String, member: Member)

    /** Families this account belongs to (for a new device after sign-in). */
    suspend fun familyIdsFor(uid: String): List<String>

    suspend fun rememberFamily(uid: String, familyId: String)

    suspend fun registerDevice(uid: String, deviceId: String, fcmToken: String, locale: String, quietHours: String?)

    suspend fun createCalendarFeed(token: String, familyId: String, memberId: String?, uid: String)

    suspend fun revokeCalendarFeed(token: String)

    suspend fun createHandoverShare(token: String, familyId: String, uid: String, payload: JsonObject, expiresAt: Instant)

    /**
     * Deletes the family on the server for everyone in it (callable `deleteFamily`, parents only, safe to repeat): every
     * document, the invites, calendar feeds and handover links that point at it, and every account's link to it. Other
     * people's accounts are kept. A tombstone stops any phone from creating the family again.
     * @throws DeletionRefusedException when the server says no or cannot be reached.
     */
    suspend fun deleteFamily(familyId: String)

    /**
     * Deletes the signed-in account (callable `deleteAccount`, safe to repeat): leaves every family, or deletes it when
     * nobody else there has an account, then removes `users/{uid}` with its devices and the Firebase account.
     * [familyId] is the family on this phone, in case the profile missed it.
     * @throws DeletionRefusedException with [DeletionRefusal.LastParent] while the caller is the family's only parent.
     */
    suspend fun deleteAccount(familyId: String?)

    /** True when the family was deleted on the server (its tombstone exists). For phones that still have it locally. */
    suspend fun familyDeleted(familyId: String): Boolean

    /** The caller's own `users/{uid}` document and devices, for the data export. Null when there is nothing to read. */
    suspend fun accountData(uid: String): JsonObject?

    /**
     * Joins a space shared with the other home because the caller is a parent of [viaFamily], one of its homes (the
     * second parent of a home that already shares). Writes the access doc (unless it exists) and the member doc.
     */
    suspend fun joinSpace(spaceId: String, viaFamily: String, uid: String, member: Member)

    /**
     * Takes [homeId] out of a shared space: removes the home from the space, hides [memberIds] there and deletes their
     * access (the caller's last). The other home keeps the space.
     */
    suspend fun leaveSpace(spaceId: String, homeId: String, uid: String, memberIds: List<String>)
}

/** Used when no backend is configured: the app works fully on the device, sharing features explain why they are off. */
object LocalOnlyFamilyRemote : FamilyRemote {
    override val available = false

    private fun unavailable(): Nothing = throw BackendUnavailableException("No backend configured")

    override suspend fun bootstrapFamily(familyId: String, name: String, memberId: String, uid: String, shared: Boolean) = unavailable()

    override suspend fun createInvite(invite: InviteInfo, createdBy: String) = unavailable()

    override suspend fun lookupInvite(code: String): InviteInfo = unavailable()

    override suspend fun join(invite: InviteInfo, uid: String, member: Member) = unavailable()

    override suspend fun familyIdsFor(uid: String): List<String> = emptyList()

    override suspend fun rememberFamily(uid: String, familyId: String) = Unit

    override suspend fun registerDevice(uid: String, deviceId: String, fcmToken: String, locale: String, quietHours: String?) = Unit

    override suspend fun createCalendarFeed(token: String, familyId: String, memberId: String?, uid: String) = unavailable()

    override suspend fun revokeCalendarFeed(token: String) = unavailable()

    override suspend fun joinSpace(spaceId: String, viaFamily: String, uid: String, member: Member) = unavailable()

    override suspend fun leaveSpace(spaceId: String, homeId: String, uid: String, memberIds: List<String>) = unavailable()

    override suspend fun createHandoverShare(
        token: String,
        familyId: String,
        uid: String,
        payload: JsonObject,
        expiresAt: Instant,
    ) = unavailable()

    override suspend fun deleteFamily(familyId: String) = unavailable()

    override suspend fun deleteAccount(familyId: String?) = unavailable()

    override suspend fun familyDeleted(familyId: String) = false

    override suspend fun accountData(uid: String): JsonObject? = null
}
