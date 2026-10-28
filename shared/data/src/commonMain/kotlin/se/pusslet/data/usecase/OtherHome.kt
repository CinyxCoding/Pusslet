package se.pusslet.data.usecase

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import se.pusslet.core.custody.SharedHome
import se.pusslet.core.model.Child
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.DietProfile
import se.pusslet.core.model.Event
import se.pusslet.core.model.Family
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.ManualLunchWeek
import se.pusslet.core.model.Member
import se.pusslet.core.model.PackingChecks
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.Role
import se.pusslet.core.model.WishItem
import se.pusslet.core.util.IdGenerator
import se.pusslet.core.util.InviteCodes
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.BackendUnavailableException
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.remote.InviteInfo
import se.pusslet.data.remote.SharedChildRef
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sync.CollectionSpec
import se.pusslet.data.sync.Collections
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.DomainJson
import se.pusslet.data.sync.RemotePermissionException
import se.pusslet.data.sync.SyncLocalStore
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** A space this family shares with another home, as the family sees it. */
data class SharedSpace(
    val id: String,
    val children: List<Child>,
    /** Adults of the other home (none until they have joined). */
    val otherAdults: List<Member>,
    val shareDiets: Boolean,
    /** The other home has joined (or is still part of it). */
    val otherHomeJoined: Boolean,
)

/**
 * Sharing the children's schedule with the other home (ADR-056). The family keeps its own data; the shared children,
 * their custody schedule and everything about only them live in a space both homes' parents belong to. Starting,
 * joining and leaving need the network; using the space afterwards works offline like everything else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OtherHomeSharing(
    private val docs: DocumentStore,
    private val local: SyncLocalStore,
    private val sessions: SessionProvider,
    private val remote: FamilyRemote,
    private val auth: AuthRepository,
    private val ids: IdGenerator,
    private val clock: Clock = Clock.System,
) {
    private val log = Logger.withTag("OtherHome")
    private val mutex = Mutex()

    val available: Boolean get() = remote.available

    val spaces: Flow<List<SharedSpace>> = sessions.session.map { it.familyId to it.spaceIds }.distinctUntilChanged()
        .flatMapLatest { (home, spaceIds) ->
            if (home == null || spaceIds.isEmpty()) {
                flowOf(emptyList())
            } else {
                val parts = combine(spaceIds.map(::observeSpace)) { it.toList() }
                combine(parts, docs.observeAll(Collections.Members, Member.serializer())) { all, ours ->
                    val mine = ours.map { it.id }.toSet()
                    all.mapNotNull { p ->
                        val space = p.family ?: return@mapNotNull null
                        val others = p.members.filter { it.id !in mine }
                        SharedSpace(p.id, p.children, others, space.shareDiets, others.isNotEmpty() || space.familyIds.size > 1)
                    }
                }
            }
        }

    private class SpaceParts(val id: String, val family: Family?, val children: List<Child>, val members: List<Member>)

    private fun observeSpace(id: String): Flow<SpaceParts> = combine(
        docs.observeIn(id, Collections.Family, Family.serializer()),
        docs.observeIn(id, Collections.Children, Child.serializer()),
        docs.observeIn(id, Collections.Members, Member.serializer()),
    ) { f, c, m -> SpaceParts(id, f.firstOrNull(), c.sortedBy { it.firstName }, m) }

    /**
     * Moves [childIds] and what is only about them (their custody schedule; with [shareExisting] also their events,
     * tasks, packing and lists; with [shareDiets] their allergies and diets) into a new shared space and returns an
     * invite for the other home.
     */
    suspend fun start(childIds: List<String>, shareExisting: Boolean, shareDiets: Boolean): InviteInfo = mutex.withLock {
        val (home, me, uid) = requireOnline()
        val children = docs.getAll(Collections.Children, Child.serializer()).filter { it.id in childIds }
        require(children.isNotEmpty()) { "No children to share" }
        val spaceId = ids.newId()
        val name = children.joinToString(", ") { it.firstName }
        remote.bootstrapFamily(spaceId, name, me.id, uid, shared = true)
        remote.rememberFamily(uid, spaceId)

        // The home lists the space before the phone syncs it, so the family's other phones join too.
        updateHome(home) { it.copy(sharedSpaces = (it.sharedSpaces + spaceId).distinct()) }
        sessions.update { it.copy(spaceIds = (it.spaceIds + spaceId).distinct()) }
        val space =
            Family(
                spaceId,
                name,
                createdBy = uid,
                createdAt = clock.now(),
                shared = true,
                familyIds = listOf(home),
                shareDiets = shareDiets,
            )
        docs.put(Collections.Family, spaceId, space, Family.serializer(), spaceId)
        docs.put(Collections.Members, me.id, me, Member.serializer(), spaceId)

        val rules = SharedHome(children.map { it.id }.toSet(), allChildIds())
        children.forEach { docs.move(Collections.Children, it.id, spaceId) }
        moveAll(Collections.Custody, CustodySchedule.serializer(), spaceId) { rules.shares(it) }
        if (shareExisting) moveAbout(rules, spaceId)
        if (shareDiets) moveAll(Collections.Diets, DietProfile.serializer(), spaceId) { rules.shares(it, shareDiets = true) }
        invite(spaceId, me, uid, children)
    }

    /** A new code for the same space (the old one expired or was lost). */
    suspend fun newInvite(spaceId: String): InviteInfo = mutex.withLock {
        val (_, me, uid) = requireOnline()
        invite(spaceId, me, uid, docs.getAllIn(spaceId, Collections.Children, Child.serializer()))
    }

    private suspend fun invite(spaceId: String, me: Member, uid: String, children: List<Child>): InviteInfo {
        val invite = InviteInfo(
            code = InviteCodes.generate(),
            familyId = spaceId,
            familyName = children.joinToString(", ") { it.firstName },
            role = Role.Parent,
            memberId = null,
            expiresAt = clock.now() + INVITE_DAYS.days,
            shared = true,
            children = children.map { SharedChildRef(it.id, it.firstName) },
            fromName = me.displayName,
        )
        remote.createInvite(invite, uid)
        return invite
    }

    /**
     * Joins the other home's space. [matches] maps each shared child to the same child in this family (or null when
     * the child is new here): this family's things then point at the shared child, our own custody schedule for them is
     * replaced by the shared one, and with [shareExisting] our things about only them move to the space.
     */
    suspend fun join(invite: InviteInfo, matches: Map<String, String?>, shareExisting: Boolean) = mutex.withLock {
        require(invite.shared) { "Not an other-home invite" }
        val (home, me, uid) = requireOnline()
        val spaceId = invite.familyId
        remote.join(invite, uid, me.copy(role = Role.Parent))
        updateHome(home) { it.copy(sharedSpaces = (it.sharedSpaces + spaceId).distinct()) }
        sessions.update { it.copy(spaceIds = (it.spaceIds + spaceId).distinct()) }

        val shared = invite.children.map { it.id }.toSet()
        matches.forEach { (sharedId, ownId) ->
            if (ownId != null && ownId != sharedId &&
                sharedId in shared
            ) {
                docs.replaceId(Collections.Children, ownId, sharedId)
            }
        }
        val rules = SharedHome(shared, allChildIds() + shared)
        docs.getAllIn(home, Collections.Custody, CustodySchedule.serializer()).filter { rules.shares(it) }.forEach {
            docs.delete(Collections.Custody, it.id)
        }
        if (shareExisting) moveAbout(rules, spaceId)
    }

    /**
     * Stops sharing: a private copy of everything in the space stays in this family, the family leaves the space and
     * this phone forgets it. The other home keeps the space with all its data.
     */
    suspend fun leave(spaceId: String) = mutex.withLock {
        val (home, _, uid) = requireOnline()
        val ourAdults = docs.getAllIn(home, Collections.Members, Member.serializer()).map { it.id }
        docs.copySpaceToHome(spaceId)
        remote.leaveSpace(spaceId, home, uid, ourAdults)
        updateHome(home) { it.copy(sharedSpaces = it.sharedSpaces - spaceId) }
        forget(spaceId)
    }

    /**
     * Keeps this phone's spaces in line with the family's list (run while signed in as a parent): joins spaces another
     * parent of the family started or joined, adds this family to a space it joined, and forgets spaces the family left.
     */
    suspend fun reconcile() = mutex.withLock {
        val session = sessions.session.value
        val home = session.familyId ?: return@withLock
        if (session.role != Role.Parent || !remote.available) return@withLock
        val family = docs.getIn(home, Collections.Family, home, Family.serializer()) ?: return@withLock
        val uid = auth.currentUser.value?.uid ?: return@withLock
        val me = session.memberId?.let { docs.getIn(home, Collections.Members, it, Member.serializer()) } ?: return@withLock
        for (spaceId in family.sharedSpaces - session.spaceIds.toSet()) {
            try {
                remote.joinSpace(spaceId, home, uid, me)
                sessions.update { it.copy(spaceIds = (it.spaceIds + spaceId).distinct()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemotePermissionException) {
                log.w(e) { "Joining a shared space was refused" }
            } catch (e: Exception) {
                log.i { "Joining a shared space waits for the network (${e::class.simpleName})" }
            }
        }
        for (spaceId in session.spaceIds - family.sharedSpaces.toSet()) forget(spaceId)
        for (spaceId in sessions.session.value.spaceIds) {
            val space = docs.getIn(spaceId, Collections.Family, spaceId, Family.serializer()) ?: continue
            if (home !in
                space.familyIds
            ) {
                docs.put(Collections.Family, spaceId, space.copy(familyIds = space.familyIds + home), Family.serializer(), spaceId)
            }
        }
    }

    private suspend fun forget(spaceId: String) {
        sessions.update { it.copy(spaceIds = it.spaceIds - spaceId) }
        local.deleteFamily(spaceId)
    }

    /** Everything else that is only about the shared children: events (and their requests), tasks, packing, lists. */
    private suspend fun moveAbout(rules: SharedHome, spaceId: String) {
        val moved = moveAll(Collections.Events, Event.serializer(), spaceId) { rules.shares(it) }
        val sharedEvents = moved + docs.getAllIn(spaceId, Collections.Events, Event.serializer()).map { it.id }
        moveAll(Collections.HandoffRequests, HandoffRequest.serializer(), spaceId) { rules.shares(it, sharedEvents.toSet()) }
        moveAll(Collections.Tasks, FamilyTask.serializer(), spaceId) { rules.shares(it) }
        moveAll(Collections.PackingRules, PackingRule.serializer(), spaceId) { rules.shares(it) }
        moveAll(Collections.PackingChecks, PackingChecks.serializer(), spaceId) { rules.shares(it) }
        moveAll(Collections.Wishes, WishItem.serializer(), spaceId) { rules.shares(it) }
        moveAll(Collections.SchoolLunches, ManualLunchWeek.serializer(), spaceId) { rules.shares(it) }
    }

    /** Moves the family's own documents of [spec] that match; returns their ids. */
    private suspend fun <T> moveAll(
        spec: CollectionSpec,
        serializer: KSerializer<T>,
        spaceId: String,
        matches: (T) -> Boolean,
    ): List<String> {
        val home = sessions.session.value.familyId ?: return emptyList()
        val rows = local.list(home, spec.key)
        val moving = rows.filter { row ->
            runCatching { DomainJson.decodeFromString(serializer, row.json) }.getOrNull()?.let(matches) == true
        }.map { it.docId }
        moving.forEach { docs.move(spec, it, spaceId) }
        return moving
    }

    private suspend fun allChildIds(): Set<String> = docs.getAll(Collections.Children, Child.serializer()).map { it.id }.toSet()

    private suspend fun updateHome(home: String, transform: (Family) -> Family) {
        val family = docs.getIn(home, Collections.Family, home, Family.serializer()) ?: return
        docs.put(Collections.Family, home, transform(family), Family.serializer(), home)
    }

    private data class Online(val home: String, val me: Member, val uid: String)

    private suspend fun requireOnline(): Online {
        if (!remote.available) throw BackendUnavailableException("No backend configured")
        val session = sessions.session.value
        val home = session.familyId ?: error("No family")
        val uid = auth.currentUser.value?.uid ?: throw BackendUnavailableException("Sign in to share")
        val me = session.memberId?.let { docs.getIn(home, Collections.Members, it, Member.serializer()) } ?: error("No member")
        return Online(home, me, uid)
    }

    suspend fun lookup(code: String): InviteInfo? = remote.lookupInvite(InviteCodes.normalize(code))

    private companion object {
        const val INVITE_DAYS = 7
    }
}
