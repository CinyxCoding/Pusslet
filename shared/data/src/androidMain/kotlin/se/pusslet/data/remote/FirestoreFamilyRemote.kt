package se.pusslet.data.remote

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.data.sync.DomainJson
import se.pusslet.data.sync.MetaFields
import se.pusslet.data.sync.RemotePermissionException
import java.util.Date
import kotlin.time.Instant

/**
 * Online-only family operations with the native Firestore SDK (ADR-006). The GDPR callables go through GitLive in [calls];
 * pass `DeletionCalls(config.emulatorHost)` in the demo flavor so they reach the Functions emulator.
 */
class FirestoreFamilyRemote(private val db: FirebaseFirestore, private val calls: DeletionCalls = DeletionCalls()) : FamilyRemote {
    override val available = true

    private fun stamp(uid: String) = mapOf(MetaFields.UPDATED_AT to FieldValue.serverTimestamp(), MetaFields.UPDATED_BY to uid)

    override suspend fun bootstrapFamily(familyId: String, name: String, memberId: String, uid: String, shared: Boolean) = guard {
        val familyRef = db.document("families/$familyId")
        val accessRef = db.document("families/$familyId/access/$uid")
        val access = runCatching { accessRef.get().await() }.getOrNull()
        if (access?.exists() == true) return@guard
        db.runBatch { batch ->
            val space = if (shared) mapOf("shared" to true) else emptyMap()
            batch.set(familyRef, mapOf("name" to name, "createdBy" to uid) + space + stamp(uid), SetOptions.merge())
            batch.set(accessRef, mapOf("memberId" to memberId, "role" to "parent"))
        }.await()
        Unit
    }

    override suspend fun createInvite(invite: InviteInfo, createdBy: String) = guard {
        db.document("invites/${invite.code}").set(
            mapOf(
                "familyId" to invite.familyId,
                "familyName" to invite.familyName,
                "role" to invite.role.wire(),
                "memberId" to invite.memberId,
                "createdBy" to createdBy,
                "expiresAt" to Timestamp(Date(invite.expiresAt.toEpochMilliseconds())),
            ) + if (invite.shared) {
                mapOf(
                    "shared" to true,
                    "fromName" to invite.fromName,
                    "children" to invite.children.map { mapOf("id" to it.id, "name" to it.name) },
                )
            } else {
                emptyMap()
            },
        ).await()
        Unit
    }

    override suspend fun lookupInvite(code: String): InviteInfo? = guard {
        val snap = db.document("invites/$code").get().await()
        if (!snap.exists()) return@guard null
        val expires = snap.getTimestamp("expiresAt")?.toDate()?.time ?: return@guard null
        InviteInfo(
            code = code,
            familyId = snap.getString("familyId") ?: return@guard null,
            familyName = snap.getString("familyName").orEmpty(),
            role = if (snap.getString("role") == "caregiver") Role.Caregiver else Role.Parent,
            memberId = snap.getString("memberId"),
            expiresAt = Instant.fromEpochMilliseconds(expires),
            shared = snap.getBoolean("shared") == true,
            children = (snap.get("children") as? List<*>).orEmpty().mapNotNull { c ->
                val m = c as? Map<*, *> ?: return@mapNotNull null
                val id = m["id"] as? String ?: return@mapNotNull null
                SharedChildRef(id, m["name"] as? String ?: "")
            },
            fromName = snap.getString("fromName").orEmpty(),
        )
    }

    override suspend fun join(invite: InviteInfo, uid: String, member: Member) = guard {
        val fid = invite.familyId
        val memberJson = DomainJson.encodeToJsonElement(Member.serializer(), member.copy(uid = uid, role = invite.role)).jsonObject
        db.runBatch { batch ->
            batch.set(
                db.document("families/$fid/access/$uid"),
                mapOf("memberId" to member.id, "role" to invite.role.wire(), "inviteCode" to invite.code),
            )
            batch.set(
                db.document("families/$fid/members/${member.id}"),
                FirestoreRemoteStore.toPlain(memberJson) + stamp(uid),
                SetOptions.merge(),
            )
            batch.set(db.document("users/$uid"), mapOf("familyIds" to FieldValue.arrayUnion(fid)), SetOptions.merge())
        }.await()
        Unit
    }

    override suspend fun familyIdsFor(uid: String): List<String> = guard {
        @Suppress("UNCHECKED_CAST")
        (db.document("users/$uid").get().await().get("familyIds") as? List<String>).orEmpty()
    }

    override suspend fun rememberFamily(uid: String, familyId: String) = guard {
        db.document("users/$uid").set(mapOf("familyIds" to FieldValue.arrayUnion(familyId)), SetOptions.merge()).await()
        Unit
    }

    override suspend fun registerDevice(uid: String, deviceId: String, fcmToken: String, locale: String, quietHours: String?) = guard {
        db.document("users/$uid/devices/$deviceId").set(
            mapOf(
                "fcmToken" to fcmToken,
                "platform" to "android",
                "locale" to locale,
                "quietHours" to quietHours,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        Unit
    }

    override suspend fun createCalendarFeed(token: String, familyId: String, memberId: String?, uid: String) = guard {
        db.document("calendarFeeds/$token").set(mapOf("familyId" to familyId, "memberId" to memberId, "createdBy" to uid)).await()
        Unit
    }

    override suspend fun revokeCalendarFeed(token: String) = guard {
        db.document("calendarFeeds/$token").delete().await()
        Unit
    }

    override suspend fun createHandoverShare(
        token: String,
        familyId: String,
        uid: String,
        payload: JsonObject,
        expiresAt: Instant,
    ) = guard {
        db.document("handoverShares/$token").set(
            mapOf(
                "familyId" to familyId,
                "createdBy" to uid,
                "expiresAt" to Timestamp(Date(expiresAt.toEpochMilliseconds())),
                "card" to FirestoreRemoteStore.toPlain(payload),
            ),
        ).await()
        Unit
    }

    override suspend fun deleteFamily(familyId: String) = calls.deleteFamily(familyId)

    override suspend fun deleteAccount(familyId: String?) = calls.deleteAccount(familyId)

    override suspend fun familyDeleted(familyId: String): Boolean = guard {
        db.document("deletedFamilies/$familyId").get().await().exists()
    }

    override suspend fun accountData(uid: String): JsonObject? = guard {
        val user = db.document("users/$uid").get().await()
        val devices = db.collection("users/$uid/devices").get().await().documents
        if (!user.exists() && devices.isEmpty()) return@guard null
        JsonObject(
            mapOf(
                "user" to (if (user.exists()) FirestoreRemoteStore.toJson(user.data.orEmpty()) else JsonNull),
                "devices" to JsonArray(
                    devices.map { d -> JsonObject(FirestoreRemoteStore.toJson(d.data.orEmpty()) + ("_id" to JsonPrimitive(d.id))) },
                ),
            ),
        )
    }

    override suspend fun joinSpace(spaceId: String, viaFamily: String, uid: String, member: Member) = guard {
        val accessRef = db.document("families/$spaceId/access/$uid")
        val exists = runCatching { accessRef.get().await().exists() }.getOrDefault(false)
        val memberJson = DomainJson.encodeToJsonElement(Member.serializer(), member.copy(uid = uid, role = Role.Parent)).jsonObject
        db.runBatch { batch ->
            if (!exists) batch.set(accessRef, mapOf("memberId" to member.id, "role" to "parent", "viaFamily" to viaFamily))
            batch.set(
                db.document("families/$spaceId/members/${member.id}"),
                FirestoreRemoteStore.toPlain(memberJson) + stamp(uid),
                SetOptions.merge(),
            )
            batch.set(db.document("users/$uid"), mapOf("familyIds" to FieldValue.arrayUnion(spaceId)), SetOptions.merge())
        }.await()
        Unit
    }

    override suspend fun leaveSpace(spaceId: String, homeId: String, uid: String, memberIds: List<String>) = guard {
        val space = db.document("families/$spaceId")
        // While still a parent there: take the home out and hide its adults, then drop the access docs (our own last).
        space.update(mapOf("familyIds" to FieldValue.arrayRemove(homeId)) + stamp(uid)).await()
        val access = space.collection("access").get().await().documents
        val leaving = access.filter { it.getString("memberId") in memberIds }
        // Only adults who are in the space: writing a missing member doc would be a create, which the rules refuse.
        val present = space.collection("members").get().await().documents.map { it.id }.filter { it in memberIds }
        db.runBatch { batch ->
            present.forEach { id ->
                batch.set(
                    space.collection("members").document(id),
                    mapOf(MetaFields.DELETED_AT to FieldValue.serverTimestamp()) + stamp(uid),
                    SetOptions.merge(),
                )
            }
            leaving.filter { it.id != uid }.forEach { batch.delete(it.reference) }
        }.await()
        space.collection("access").document(uid).delete().await()
        db.document("users/$uid").set(mapOf("familyIds" to FieldValue.arrayRemove(spaceId)), SetOptions.merge()).await()
        Unit
    }

    private fun Role.wire() = when (this) {
        Role.Parent -> "parent"
        Role.Caregiver -> "caregiver"
        Role.Child -> "child"
    }

    private suspend fun <T> guard(block: suspend () -> T): T = try {
        block()
    } catch (e: FirebaseFirestoreException) {
        throw if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) RemotePermissionException("denied", e) else e
    }
}
