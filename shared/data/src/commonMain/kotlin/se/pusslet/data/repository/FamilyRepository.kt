package se.pusslet.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import se.pusslet.core.model.Child
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.DietProfile
import se.pusslet.core.model.Family
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilySettings
import se.pusslet.core.model.Member
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.core.util.IdGenerator
import se.pusslet.core.util.InviteCodes
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.BackendUnavailableException
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.remote.InviteInfo
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sync.Collections
import se.pusslet.data.sync.DocumentStore
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class FamilyRepository(
    private val docs: DocumentStore,
    private val sessions: SessionProvider,
    private val remote: FamilyRemote,
    private val auth: AuthRepository,
    private val ids: IdGenerator,
    private val clock: Clock = Clock.System,
) {
    val family: Flow<Family?> = docs.observeAll(Collections.Family, Family.serializer()).map { it.firstOrNull() }

    val members: Flow<List<Member>> = docs.observeAll(Collections.Members, Member.serializer()).map { list ->
        list.sortedBy { it.displayName }
    }

    val children: Flow<List<Child>> = docs.observeAll(Collections.Children, Child.serializer())
        .map { list -> list.sortedWith(compareBy<Child>({ it.birthDate == null }, { it.birthDate }).thenBy { it.firstName }) }

    /** Everyone an event or task can be about. Adults first, then children (oldest first). */
    val people: Flow<List<FamilyPerson>> = combine(members, children) { m, c ->
        m.sortedBy { it.role.ordinal }.map(FamilyPerson::of) + c.map(FamilyPerson::of)
    }

    val custody: Flow<List<CustodySchedule>> = docs.observeAll(Collections.Custody, CustodySchedule.serializer())

    val diets: Flow<List<DietProfile>> = docs.observeAll(Collections.Diets, DietProfile.serializer())

    val settings: Flow<FamilySettings> = docs.observe(Collections.Settings, SETTINGS_DOC, FamilySettings.serializer()).map {
        it
            ?: FamilySettings()
    }

    /** Creates a family on this device. Works offline and without an account; syncing starts once signed in. */
    suspend fun createFamily(name: String, myName: String, myColor: MemberColor = MemberColor.Sage, demo: Boolean = false): Family {
        val familyId = ids.newId()
        val memberId = ids.newId()
        val uid = auth.currentUser.value?.uid
        val family = Family(id = familyId, name = name.trim(), createdBy = uid.orEmpty(), createdAt = clock.now())
        sessions.update { it.copy(familyId = familyId, memberId = memberId, role = Role.Parent, uid = uid ?: it.uid, demo = demo) }
        docs.put(Collections.Family, familyId, family, Family.serializer(), familyId)
        docs.put(
            Collections.Members,
            memberId,
            Member(memberId, myName.trim(), Role.Parent, myColor, uid = uid, joinedAt = clock.now()),
            Member.serializer(),
            familyId,
        )
        return family
    }

    suspend fun renameFamily(name: String) {
        val fid = sessions.session.value.familyId ?: return
        docs.update(Collections.Family, fid, Family.serializer()) { it.copy(name = name.trim()) }
    }

    suspend fun saveChild(child: Child) = docs.put(Collections.Children, child.id, child, Child.serializer())

    suspend fun deleteChild(id: String) = docs.delete(Collections.Children, id)

    suspend fun saveMember(member: Member) = docs.put(Collections.Members, member.id, member, Member.serializer())

    suspend fun deleteMember(id: String) = docs.delete(Collections.Members, id)

    suspend fun saveCustody(schedule: CustodySchedule) = docs.put(Collections.Custody, schedule.id, schedule, CustodySchedule.serializer())

    suspend fun deleteCustody(id: String) = docs.delete(Collections.Custody, id)

    suspend fun saveDiet(profile: DietProfile) = docs.put(Collections.Diets, profile.personId, profile, DietProfile.serializer())

    suspend fun deleteDiet(personId: String) = docs.delete(Collections.Diets, personId)

    suspend fun saveSettings(transform: (FamilySettings) -> FamilySettings) {
        val current = docs.get(Collections.Settings, SETTINGS_DOC, FamilySettings.serializer()) ?: FamilySettings()
        docs.put(Collections.Settings, SETTINGS_DOC, transform(current), FamilySettings.serializer())
    }

    fun newChildId(): String = ids.newId()

    fun newId(): String = ids.newId()

    /**
     * Creates an invite (online). Pass [claimMemberId] to let the invitee take over an existing person
     * (e.g. "Morfar Bengt" who was added before he had the app).
     */
    suspend fun createInvite(role: Role, claimMemberId: String? = null): InviteInfo {
        val session = sessions.session.value
        val fid = session.familyId ?: error("No family")
        val uid = auth.currentUser.value?.uid ?: throw BackendUnavailableException("Sign in to invite")
        val familyName = docs.get(Collections.Family, fid, Family.serializer())?.name.orEmpty()
        val invite = InviteInfo(
            code = InviteCodes.generate(),
            familyId = fid,
            familyName = familyName,
            role = role,
            memberId = claimMemberId,
            expiresAt = clock.now() + 7.days,
        )
        remote.createInvite(invite, uid)
        return invite
    }

    suspend fun lookupInvite(code: String): InviteInfo? = remote.lookupInvite(InviteCodes.normalize(code))

    /** Joins another family with an invite. The local session switches to that family; sync downloads its data. */
    suspend fun join(invite: InviteInfo, myName: String, myColor: MemberColor): String {
        val uid = auth.currentUser.value?.uid ?: auth.signInAnonymously().uid
        val memberId = invite.memberId ?: ids.newId()
        val member = Member(memberId, myName.trim(), invite.role, myColor, uid = uid, joinedAt = clock.now(), inviteCode = invite.code)
        remote.join(invite, uid, member)
        sessions.update { it.copy(familyId = invite.familyId, memberId = memberId, uid = uid, role = invite.role, demo = false) }
        return invite.familyId
    }

    companion object {
        const val SETTINGS_DOC = "family"
    }
}
