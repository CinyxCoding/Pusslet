package se.pusslet.data.usecase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.pusslet.core.model.Child
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.Event
import se.pusslet.core.model.Family
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Household
import se.pusslet.core.model.ManualLunchWeek
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.core.time.isoWeek
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.AuthUser
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.remote.InviteInfo
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import se.pusslet.data.sync.Collections
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.DomainJson
import se.pusslet.data.sync.InMemoryRemoteStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import se.pusslet.data.sync.MetaFields
import se.pusslet.data.sync.SyncEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Ahmed and Sara (home A) share Ella with Lina (home B); Noah is Ahmed's and Sara's alone (ADR-056). */
@OptIn(ExperimentalCoroutinesApi::class)
class OtherHomeSharingTest {
    private class FakeClock(var now: Long = 1_760_000_000_000) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(now++)
    }

    private class FakeAuth(uid: String) : AuthRepository {
        override val available = true
        override val currentUser: StateFlow<AuthUser?> = MutableStateFlow(AuthUser(uid, null, null, isAnonymous = true))

        override suspend fun signInAnonymously() = currentUser.value!!

        override suspend fun signInWithEmail(email: String, password: String) = currentUser.value!!

        override suspend fun createAccount(email: String, password: String) = currentUser.value!!

        override suspend fun signInWithGoogle(idToken: String) = currentUser.value!!

        override suspend fun signOut() = Unit
    }

    /** The online operations against the fake server, without security rules (those are tested in functions/). */
    private class FakeFamilyRemote(private val server: InMemoryRemoteStore) : FamilyRemote by LocalOnlyFamilyRemote {
        override val available = true
        val invites = mutableMapOf<String, InviteInfo>()

        override suspend fun bootstrapFamily(familyId: String, name: String, memberId: String, uid: String, shared: Boolean) {
            server.write(
                familyId,
                "",
                mapOf(
                    "name" to JsonPrimitive(name),
                    "createdBy" to JsonPrimitive(uid),
                    "shared" to JsonPrimitive(shared),
                ),
                uid,
            )
            access(familyId, uid, memberId)
        }

        override suspend fun createInvite(invite: InviteInfo, createdBy: String) {
            invites[invite.code] = invite
        }

        override suspend fun lookupInvite(code: String) = invites[code]

        override suspend fun join(invite: InviteInfo, uid: String, member: Member) = joinSpace(invite.familyId, "", uid, member)

        override suspend fun joinSpace(spaceId: String, viaFamily: String, uid: String, member: Member) {
            access(spaceId, uid, member.id)
            server.write(spaceId, "members/${member.id}", DomainJson.encodeToJsonElement(Member.serializer(), member).jsonObject, uid)
        }

        override suspend fun leaveSpace(spaceId: String, homeId: String, uid: String, memberIds: List<String>) {
            val homes = server.snapshot(spaceId, "")?.get("familyIds")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            server.write(spaceId, "", mapOf("familyIds" to JsonArray((homes - homeId).map(::JsonPrimitive))), uid)
            memberIds.forEach {
                server.write(
                    spaceId,
                    "members/$it",
                    mapOf(
                        MetaFields.DELETED_AT to JsonPrimitive(MetaFields.SERVER_TIMESTAMP),
                    ),
                    uid,
                )
            }
        }

        override suspend fun rememberFamily(uid: String, familyId: String) = Unit

        private suspend fun access(fid: String, uid: String, memberId: String) =
            server.write(fid, "access/$uid", mapOf("memberId" to JsonPrimitive(memberId), "role" to JsonPrimitive("parent")), uid)
    }

    private inner class Phone(remote: FamilyRemote, val home: String, val memberId: String, private val uid: String) {
        val local = InMemorySyncLocalStore()
        val sessions = FixedSessionProvider(Session(familyId = home, memberId = memberId, uid = uid, role = Role.Parent))
        private val clock = FakeClock()
        val docs = DocumentStore(local, sessions, clock)
        val engine = SyncEngine(local, server, docs)
        private val auth = FakeAuth(uid)
        private val ids = SequentialIdGenerator("$uid-")
        val sharing = OtherHomeSharing(docs, local, sessions, remote, auth, ids, clock)
        val family = FamilyRepository(docs, sessions, remote, auth, ids, clock)

        /** What AppCoordinator does: sync the family and the spaces of the session. */
        fun connect(scope: TestScope) = engine.start(scope.backgroundScope, home, uid, Role.Parent, sessions.session.value.spaceIds)

        suspend fun titles(): Set<String> = docs.observeAll(Collections.Events, Event.serializer()).first().map { it.title }.toSet()

        suspend fun event(title: String): Event = assertNotNull(
            docs.getAll(Collections.Events, Event.serializer()).firstOrNull {
                it.title ==
                    title
            },
        )

        val space: String get() = sessions.session.value.spaceIds.single()
    }

    private val server = InMemoryRemoteStore()
    private val remote = FakeFamilyRemote(server)

    private fun TestScope.settle() {
        repeat(40) {
            runCurrent()
            advanceTimeBy(50)
        }
        runCurrent()
    }

    private fun event(id: String, title: String, vararg people: String) = Event(
        id = id,
        title = title,
        start = LocalDateTime.parse("2026-10-13T17:30"),
        end = LocalDateTime.parse("2026-10-13T18:30"),
        participantIds = people.toList(),
    )

    private val monday = LocalDate(2026, 10, 12)

    // Long, UUID-like ids, as the app uses.
    private val ella = "child-ella-0000-0000-000000000001"
    private val noah = "child-noah-0000-0000-000000000002"
    private val linasElla = "child-lina-ella-0000-000000000003"

    private suspend fun seedAhmed(p: Phone) {
        p.docs.put(Collections.Family, p.home, Family(p.home, "Hemma hos pappa"), Family.serializer(), p.home)
        p.family.saveMember(Member("m-ahmed", "Ahmed"))
        p.family.saveMember(Member("m-sara", "Sara"))
        p.family.saveChild(Child(ella, "Ella"))
        p.family.saveChild(Child(noah, "Noah"))
        val schedule =
            CustodySchedule(
                "custody-a",
                listOf(ella),
                listOf(Household("h1", "Pappa", listOf("m-ahmed")), Household("h2", "Mamma")),
                anchorDate = monday,
            )
        p.family.saveCustody(schedule)
        for (e in listOf(
            event("e-football", "Fotboll", ella, "m-ahmed"),
            event("e-dentist", "Tandläkare", noah),
            event("e-dinner", "Middag", "m-ahmed", "m-sara"),
            event("e-cinema", "Bio", ella, noah),
        )) {
            p.docs.put(Collections.Events, e.id, e, Event.serializer())
        }
    }

    private suspend fun seedLina(p: Phone) {
        p.docs.put(Collections.Family, p.home, Family(p.home, "Hemma hos mamma"), Family.serializer(), p.home)
        p.family.saveMember(Member("m-lina", "Lina"))
        p.family.saveChild(Child(linasElla, "Ella"))
        p.family.saveCustody(CustodySchedule("custody-b", listOf(linasElla), listOf(Household("x", "Mamma")), anchorDate = monday))
        p.docs.put(Collections.Events, "e-swim", event("e-swim", "Simning", linasElla), Event.serializer())
        p.docs.put(Collections.Tasks, "t-words", FamilyTask("t-words", "Glosor", assigneeId = linasElla), FamilyTask.serializer())
        val lunch = ManualLunchWeek(linasElla, monday.isoWeek, mapOf(monday.toString() to "Fiskgratäng"))
        p.docs.put(Collections.SchoolLunches, lunch.docId, lunch, ManualLunchWeek.serializer())
    }

    @Test
    fun twoHomesShareOnlyTheChildTheyHaveTogether() = runTest {
        val ahmed = Phone(remote, "home-a", "m-ahmed", "u-ahmed")
        val lina = Phone(remote, "home-b", "m-lina", "u-lina")
        seedAhmed(ahmed)
        seedLina(lina)
        ahmed.connect(this)
        lina.connect(this)
        settle()

        // Ahmed shares Ella: she, her schedule and what is only about her move to the space; the rest stays home.
        val invite = ahmed.sharing.start(listOf(ella), shareExisting = true, shareDiets = false)
        assertEquals(listOf("Ella"), invite.children.map { it.name })
        assertEquals("Ahmed", invite.fromName)
        ahmed.connect(this)
        settle()
        val space = ahmed.space
        assertEquals(setOf("Fotboll"), ahmed.docs.getAllIn(space, Collections.Events, Event.serializer()).map { it.title }.toSet())
        assertEquals(setOf("Fotboll", "Tandläkare", "Middag", "Bio"), ahmed.titles(), "Ahmed still sees everything")
        assertEquals(listOf("custody-a"), ahmed.docs.getAllIn(space, Collections.Custody, CustodySchedule.serializer()).map { it.id })
        assertEquals(setOf("Ella", "Noah"), ahmed.family.children.first().map { it.firstName }.toSet())
        assertEquals(listOf(space), ahmed.docs.getIn(ahmed.home, Collections.Family, ahmed.home, Family.serializer())?.sharedSpaces)

        // Lina joins and says her Ella is the same child: her things now point at the shared Ella and move over.
        val found = assertNotNull(lina.sharing.lookup(invite.code))
        lina.sharing.join(found, mapOf(ella to linasElla), shareExisting = true)
        lina.connect(this)
        settle()
        lina.sharing.reconcile()
        settle()
        assertEquals(listOf(ella), lina.family.children.first().map { it.id }, "one Ella, with the shared id")
        assertEquals(setOf("Fotboll", "Simning"), lina.titles(), "Ahmed's private events never reach Lina")
        assertEquals(listOf(ella), lina.event("Simning").participantIds)
        assertEquals(listOf("custody-a"), lina.family.custody.first().map { it.id }, "the shared schedule replaces Lina's own")
        val words = assertNotNull(lina.docs.get(Collections.Tasks, "t-words", FamilyTask.serializer()))
        assertEquals(ella, words.assigneeId)
        assertTrue(lina.docs.isShared(Collections.Tasks, "t-words"))
        val lunch = ManualLunchWeek.docId(ella, monday.isoWeek)
        assertEquals(
            "Fiskgratäng",
            lina.docs.get(Collections.SchoolLunches, lunch, ManualLunchWeek.serializer())?.days?.get(monday.toString()),
        )
        assertEquals(listOf("home-a", "home-b"), lina.docs.getIn(space, Collections.Family, space, Family.serializer())?.familyIds)
        assertNull(server.snapshot(space, "events/e-dinner"))
        assertNull(server.snapshot(space, "events/e-cinema"), "Ella's cinema with Noah stays with Noah's home")

        // Both homes see each other's news about Ella; a new event about her lands in the space on its own.
        lina.docs.put(Collections.Events, "e-dentist-ella", event("e-dentist-ella", "Ellas tandläkare", ella, "m-lina"), Event.serializer())
        settle()
        assertTrue("Simning" in ahmed.titles() && "Ellas tandläkare" in ahmed.titles())
        assertEquals(listOf("Lina"), ahmed.family.otherHomeAdults.first().map { it.displayName })
        assertTrue(ahmed.family.everyone.first().single { it.name == "Lina" }.otherHome)
        assertEquals(listOf("Ahmed"), lina.family.otherHomeAdults.first().map { it.displayName })

        // Sara (Ahmed's partner) gets the space through their home, without an invite.
        val sara = Phone(remote, "home-a", "m-sara", "u-sara")
        sara.connect(this)
        settle()
        sara.sharing.reconcile()
        sara.connect(this)
        settle()
        assertEquals(listOf(space), sara.sessions.session.value.spaceIds)
        assertTrue("Simning" in sara.titles())

        // Ahmed stops sharing: his home keeps a copy, Lina keeps the space, Sara's phone follows the home.
        ahmed.sharing.leave(space)
        ahmed.connect(this)
        settle()
        assertEquals(emptyList(), ahmed.sessions.session.value.spaceIds)
        assertTrue(ahmed.titles().containsAll(setOf("Fotboll", "Simning", "Ellas tandläkare", "Middag")))
        assertEquals(setOf("Ella", "Noah"), ahmed.family.children.first().map { it.firstName }.toSet())
        assertTrue(ahmed.local.listFamily(space).isEmpty(), "the space is forgotten on Ahmed's phone")
        assertEquals(setOf("Fotboll", "Simning", "Ellas tandläkare"), lina.titles())
        assertEquals(emptyList(), lina.family.otherHomeAdults.first().filter { it.displayName == "Ahmed" })
        assertEquals(listOf("home-b"), lina.docs.getIn(space, Collections.Family, space, Family.serializer())?.familyIds)
        sara.sharing.reconcile()
        assertEquals(emptyList(), sara.sessions.session.value.spaceIds)
        assertFalse(sara.docs.isShared(Collections.Events, "e-football"))
    }

    @Test
    fun allergiesStayHomeUnlessTheParentSharesThem() = runTest {
        val ahmed = Phone(remote, "home-a", "m-ahmed", "u-ahmed")
        seedAhmed(ahmed)
        ahmed.family.saveDiet(se.pusslet.core.model.DietProfile(ella, allergies = setOf(se.pusslet.core.model.Allergen.Peanut)))
        ahmed.connect(this)
        settle()
        ahmed.sharing.start(listOf(ella), shareExisting = false, shareDiets = false)
        settle()
        val space = ahmed.space
        assertFalse(ahmed.docs.isShared(Collections.Diets, ella))
        assertFalse(ahmed.docs.isShared(Collections.Events, "e-football"), "existing events stay unless asked")
        assertTrue(ahmed.docs.getAllIn(space, Collections.Diets, se.pusslet.core.model.DietProfile.serializer()).isEmpty())
        // A new event about Ella goes to the space even so: from now on her things are shared.
        ahmed.docs.put(Collections.Events, "e-new", event("e-new", "Kalas hos Vera", ella), Event.serializer())
        assertTrue(ahmed.docs.isShared(Collections.Events, "e-new"))
    }
}
