package se.pusslet.data.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import se.pusslet.core.model.Child
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.AppCoordinator
import se.pusslet.data.BackendConfig
import se.pusslet.data.BackendMode
import se.pusslet.data.FakePreferenceDao
import se.pusslet.data.db.PurchaseLogDao
import se.pusslet.data.db.PurchaseLogEntity
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.AuthUser
import se.pusslet.data.remote.DeletionRefusal
import se.pusslet.data.remote.DeletionRefusedException
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.AppTheme
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.seed.DemoSeed
import se.pusslet.data.session.SessionRepository
import se.pusslet.data.sync.Collections
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemoryRemoteStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import se.pusslet.data.sync.SyncEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private class FakePurchases : PurchaseLogDao {
    val log = mutableListOf<PurchaseLogEntity>()

    override suspend fun insert(entity: PurchaseLogEntity) {
        log += entity
    }

    override suspend fun since(familyId: String, since: Long) = log.filter { it.familyId == familyId && it.boughtAt >= since }

    override suspend fun deleteFamily(familyId: String) {
        log.removeAll { it.familyId == familyId }
    }
}

/** Signed in as [uid] until [signOut]. */
private class SignedIn(uid: String?) : AuthRepository by LocalOnlyAuthRepository() {
    private val user = MutableStateFlow(uid?.let { AuthUser(it, null, null, isAnonymous = false) })
    override val available = true
    override val currentUser: StateFlow<AuthUser?> = user

    override suspend fun signOut() {
        user.value = null
    }
}

/** A backend that records the deletion calls; [refusal] makes them fail. */
private class RecordingRemote(
    var refusal: Exception? = null,
    var account: JsonObject? = null,
    var accountError: Exception? = null,
) : FamilyRemote by LocalOnlyFamilyRemote {
    override val available = true
    val familyDeletions = mutableListOf<String>()
    val accountDeletions = mutableListOf<String?>()

    override suspend fun deleteFamily(familyId: String) {
        refusal?.let { throw it }
        familyDeletions += familyId
    }

    override suspend fun deleteAccount(familyId: String?) {
        refusal?.let { throw it }
        accountDeletions += familyId
    }

    override suspend fun accountData(uid: String): JsonObject? {
        accountError?.let { throw it }
        return account
    }
}

class DataPrivacyTest {
    private val zone = TimeZone.of("Europe/Stockholm")

    /** 22:30 UTC is already the 20th in Stockholm. */
    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-10-19T22:30:00Z")
    }

    /** One phone with Familjen Lind; Sara is signed in as `uid-sara` unless [auth] says otherwise. */
    private inner class Phone(
        scope: CoroutineScope,
        val remote: FamilyRemote = RecordingRemote(),
        val auth: AuthRepository = SignedIn("uid-sara"),
    ) {
        val prefDao = FakePreferenceDao()
        val local = InMemorySyncLocalStore()
        val sessions = SessionRepository(prefDao)
        val prefs = PreferencesRepository(prefDao)
        val docs = DocumentStore(local, sessions, clock)
        val purchases = FakePurchases()
        val family = FamilyRepository(docs, sessions, remote, auth, SequentialIdGenerator(), clock)
        val coordinator = AppCoordinator(
            scope = scope,
            config = BackendConfig(BackendMode.LocalOnly, seedDemoData = false, versionName = "test"),
            sessions = sessions,
            auth = auth,
            familyRemote = remote,
            docs = docs,
            engine = SyncEngine(local, InMemoryRemoteStore(), docs),
            seed = DemoSeed(family, docs, clock),
            prefs = prefs,
            zone = zone,
            clock = clock,
        )
        val privacy = DataPrivacy(local, sessions, coordinator, family, remote, auth, purchases, prefDao, zone, clock)

        suspend fun withFamily(role: Role = Role.Parent): String {
            val id = family.createFamily("Familjen Lind", "Sara").id
            sessions.update { it.copy(role = role) }
            return id
        }

        fun exported(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun exportHasTheCollectionsPurchasesPreferencesAndAccount() = runTest {
        val remote = RecordingRemote(account = buildJsonObject { put("user", buildJsonObject { put("locale", "sv") }) })
        val phone = Phone(backgroundScope, remote)
        val fid = phone.withFamily()
        phone.sessions.update { it.copy(uid = "uid-sara") }
        phone.family.saveChild(Child(id = "ella", firstName = "Ella"))
        phone.family.saveChild(Child(id = "simon", firstName = "Simon"))
        phone.family.deleteChild("simon")
        phone.purchases.insert(PurchaseLogEntity(familyId = fid, normalizedName = "mjölk", displayName = "Mjölk", boughtAt = 1_000))
        phone.purchases.insert(PurchaseLogEntity(familyId = "other", normalizedName = "bröd", displayName = "Bröd", boughtAt = 2_000))
        phone.prefs.set(Prefs.Theme, AppTheme.Dark)
        phone.prefs.set(Prefs.RecentQuickAdds, listOf("Simning tis 17"))
        phone.prefs.set(Prefs.OnboardingDone, true)

        val export = phone.exported(phone.privacy.exportJson())

        assertEquals(DataPrivacy.FORMAT, export.getValue("format").jsonPrimitive.int)
        assertEquals(fid, export.getValue("familyId").jsonPrimitive.content)
        assertEquals("parent", export.getValue("role").jsonPrimitive.content)
        val children = export.getValue("collections").jsonObject.getValue(Collections.Children.key).jsonArray.map { it.jsonObject }
        assertEquals(listOf("ella" to false, "simon" to true), children.map { it.getValue("_id").jsonPrimitive.content to it.deleted() })
        assertEquals("Ella", children.first().getValue("firstName").jsonPrimitive.content)
        val bought = export.getValue("purchaseHistory").jsonArray.map { it.jsonObject }
        assertEquals(listOf("Mjölk"), bought.map { it.getValue("name").jsonPrimitive.content })
        assertEquals("1970-01-01T00:00:01Z", bought.single().getValue("boughtAt").jsonPrimitive.content)
        val thisPhone = export.getValue("thisPhone").jsonObject
        assertEquals(fid, thisPhone.getValue("session").jsonObject.getValue("familyId").jsonPrimitive.content)
        val preferences = thisPhone.getValue("preferences").jsonObject
        assertEquals(JsonPrimitive("Dark"), preferences[Prefs.Theme.name])
        assertEquals(JsonArray(listOf(JsonPrimitive("Simning tis 17"))), preferences[Prefs.RecentQuickAdds.name])
        assertNull(preferences[Prefs.OnboardingDone.name], "app-state flags are not personal data")
        assertEquals("sv", export.getValue("account").jsonObject.getValue("user").jsonObject.getValue("locale").jsonPrimitive.content)
        val notIncluded = export.notIncluded()
        assertTrue(DataPrivacy.NOT_PUBLIC_DATA in notIncluded)
        assertFalse(DataPrivacy.NOT_ACCOUNT in notIncluded)
        assertFalse(DataPrivacy.NOT_SHARED_WITH_YOU in notIncluded)
    }

    @Test
    fun exportWithoutBackendOrAsCaregiverSaysWhatIsMissing() = runTest {
        val phone = Phone(backgroundScope, LocalOnlyFamilyRemote, LocalOnlyAuthRepository())
        phone.withFamily(Role.Caregiver)

        val export = phone.exported(phone.privacy.exportJson())

        assertEquals(JsonNull, export["account"])
        assertEquals("caregiver", export.getValue("role").jsonPrimitive.content)
        assertTrue(DataPrivacy.NOT_ACCOUNT in export.notIncluded())
        assertTrue(DataPrivacy.NOT_SHARED_WITH_YOU in export.notIncluded())
    }

    @Test
    fun anUnreadableAccountDoesNotStopTheExport() = runTest {
        val phone = Phone(backgroundScope, RecordingRemote(accountError = IllegalStateException("offline")))
        phone.withFamily()
        phone.sessions.update { it.copy(uid = "uid-sara") }

        val export = phone.exported(phone.privacy.exportJson())

        assertEquals(JsonNull, export["account"])
        assertTrue(DataPrivacy.NOT_ACCOUNT in export.notIncluded())
        assertTrue(export.getValue("collections").jsonObject.containsKey(Collections.Family.key))
    }

    @Test
    fun exportFileIsNamedAfterTheLocalDate() = runTest {
        assertEquals("pusslet-export-2026-10-20.json", Phone(backgroundScope).privacy.exportFileName())
    }

    @Test
    fun forgettingClearsTheDocumentsThePurchaseHistoryAndTheFamilysPreferences() = runTest {
        val phone = Phone(backgroundScope)
        val fid = phone.withFamily()
        phone.purchases.insert(PurchaseLogEntity(familyId = fid, normalizedName = "mjölk", displayName = "Mjölk", boughtAt = 1_000))
        phone.purchases.insert(PurchaseLogEntity(familyId = "other", normalizedName = "bröd", displayName = "Bröd", boughtAt = 2_000))
        phone.prefs.set(Prefs.RecentQuickAdds, listOf("Simning tis 17"))
        phone.prefs.set(Prefs.DismissedStaples, mapOf("mjölk" to 1L))
        phone.prefs.set(Prefs.SetupHidden, setOf(fid, "other"))
        phone.prefs.set(Prefs.Theme, AppTheme.Dark)

        phone.privacy.forgetOnDevice()

        assertFalse(phone.sessions.session.value.hasFamily)
        assertEquals(emptyList(), phone.local.listFamily(fid))
        assertEquals(listOf("other"), phone.purchases.log.map { it.familyId })
        assertEquals(emptyList(), phone.prefs.get(Prefs.RecentQuickAdds))
        assertEquals(emptyMap(), phone.prefs.get(Prefs.DismissedStaples))
        assertEquals(setOf("other"), phone.prefs.get(Prefs.SetupHidden))
        assertEquals(AppTheme.Dark, phone.prefs.get(Prefs.Theme), "the phone's own settings stay")
    }

    @Test
    fun deletingTheFamilyForEveryoneAsksTheServerThenForgetsAndSignsOut() = runTest {
        val remote = RecordingRemote()
        val phone = Phone(backgroundScope, remote)
        val fid = phone.withFamily()
        phone.sessions.update { it.copy(uid = "uid-sara") }

        assertEquals(DeletionOutcome.Done, phone.privacy.deleteFamilyForEveryone())

        assertEquals(listOf(fid), remote.familyDeletions)
        assertEquals(emptyList(), phone.local.listFamily(fid))
        assertNull(phone.auth.currentUser.value)
        assertNull(phone.sessions.session.value.uid)
        assertFalse(phone.sessions.session.value.hasFamily)
    }

    @Test
    fun deletingTheAccountNamesTheFamilyOnThisPhone() = runTest {
        val remote = RecordingRemote()
        val phone = Phone(backgroundScope, remote)
        val fid = phone.withFamily(Role.Caregiver)

        assertEquals(DeletionOutcome.Done, phone.privacy.deleteAccount())

        assertEquals(listOf<String?>(fid), remote.accountDeletions)
        assertFalse(phone.sessions.session.value.hasFamily)
    }

    @Test
    fun aRefusedDeletionChangesNothingOnThePhone() = runTest {
        val remote = RecordingRemote(refusal = DeletionRefusedException(DeletionRefusal.LastParent))
        val phone = Phone(backgroundScope, remote)
        val fid = phone.withFamily()

        assertEquals(DeletionOutcome.LastParent, phone.privacy.deleteAccount())

        assertTrue(phone.sessions.session.value.hasFamily)
        assertTrue(phone.local.listFamily(fid).isNotEmpty())
        assertNotNull(phone.auth.currentUser.value)
    }

    @Test
    fun everyRefusalHasAnOutcome() = runTest {
        val cases = mapOf(
            DeletionRefusedException(DeletionRefusal.NotSignedIn) to DeletionOutcome.SignInAgain,
            DeletionRefusedException(DeletionRefusal.RecentLoginRequired) to DeletionOutcome.SignInAgain,
            DeletionRefusedException(DeletionRefusal.NotAllowed) to DeletionOutcome.NotAllowed,
            DeletionRefusedException(DeletionRefusal.Network) to DeletionOutcome.Offline,
            DeletionRefusedException(DeletionRefusal.Unknown) to DeletionOutcome.Failed,
            IllegalStateException("boom") to DeletionOutcome.Failed,
        )
        for ((error, outcome) in cases) {
            val phone = Phone(backgroundScope, RecordingRemote(refusal = error))
            phone.withFamily()
            assertEquals(outcome, phone.privacy.deleteFamilyForEveryone(), error.toString())
            assertTrue(phone.sessions.session.value.hasFamily)
        }
        val local = Phone(backgroundScope, LocalOnlyFamilyRemote)
        local.withFamily()
        assertEquals(DeletionOutcome.Failed, local.privacy.deleteFamilyForEveryone(), "no backend, nothing to delete there")
        assertTrue(local.sessions.session.value.hasFamily)
    }

    @Test
    fun optionsFollowTheBackendTheAccountAndTheRole() = runTest {
        val offline = Phone(backgroundScope, LocalOnlyFamilyRemote, LocalOnlyAuthRepository())
        offline.withFamily()
        val none = offline.privacy.options.first()
        assertFalse(none.server)
        assertFalse(none.canDeleteFamily || none.canDeleteAccount || none.needsSignIn)

        val parent = Phone(backgroundScope)
        parent.withFamily()
        val p = parent.privacy.options.first()
        assertTrue(p.canDeleteFamily && p.canDeleteAccount)
        assertEquals("Familjen Lind", p.familyName)
        assertEquals(AccountDeletionPlan.DeletesFamily, p.accountPlan, "Sara is the only one with an account")

        val caregiver = Phone(backgroundScope)
        caregiver.withFamily(Role.Caregiver)
        val c = caregiver.privacy.options.first()
        assertFalse(c.canDeleteFamily)
        assertTrue(c.canDeleteAccount)

        val signedOut = Phone(backgroundScope, auth = SignedIn(null))
        signedOut.withFamily()
        val s = signedOut.privacy.options.first()
        assertTrue(s.needsSignIn)
        assertFalse(s.canDeleteFamily || s.canDeleteAccount)
    }

    @Test
    fun accountPlanMirrorsTheServer() {
        val sara = Member("m-sara", "Sara", Role.Parent, uid = "uid-sara")
        val johan = Member("m-johan", "Johan", Role.Parent, uid = "uid-johan")
        val johanWithoutAccount = johan.copy(uid = null)
        val bengt = Member("m-bengt", "Bengt", Role.Caregiver, uid = "uid-bengt")

        assertEquals(AccountDeletionPlan.DeletesFamily, accountPlan(Role.Parent, sara.id, listOf(sara, johanWithoutAccount)))
        assertEquals(AccountDeletionPlan.LastParent, accountPlan(Role.Parent, sara.id, listOf(sara, bengt, johanWithoutAccount)))
        assertEquals(AccountDeletionPlan.LeaveFamily, accountPlan(Role.Parent, sara.id, listOf(sara, johan, bengt)))
        assertEquals(AccountDeletionPlan.LeaveFamily, accountPlan(Role.Caregiver, bengt.id, listOf(sara, bengt)))
        assertEquals(AccountDeletionPlan.DeletesFamily, accountPlan(Role.Caregiver, bengt.id, listOf(johanWithoutAccount, bengt)))
    }

    private fun JsonObject.deleted() = getValue("_deleted").jsonPrimitive.boolean

    private fun JsonObject.notIncluded() = getValue("notIncluded").jsonArray.map { it.jsonPrimitive.content }
}
