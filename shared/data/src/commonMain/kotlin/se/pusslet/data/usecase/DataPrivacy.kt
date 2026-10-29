package se.pusslet.data.usecase

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.pusslet.core.AppConfig
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.data.AppCoordinator
import se.pusslet.data.db.PreferenceDao
import se.pusslet.data.db.PurchaseLogDao
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.BackendUnavailableException
import se.pusslet.data.remote.DeletionRefusal
import se.pusslet.data.remote.DeletionRefusedException
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.repository.AttachmentFiles
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.NoAttachmentFiles
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.Session
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sync.SyncLocalStore
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What "Radera mitt konto" does for this person. */
enum class AccountDeletionPlan {
    /** The others keep the family; this person stays in it as someone without an account. */
    LeaveFamily,

    /** Nobody else in the family has an account, so the family is deleted too. */
    DeletesFamily,

    /** The only parent while others have accounts: delete the family for everyone or hand it over first. */
    LastParent,
}

/** The ways out the Integritet screen offers on this phone. */
data class PrivacyOptions(
    /** A backend is configured. Without one (BackendMode.LocalOnly) "this phone" is all there is. */
    val server: Boolean = false,
    val signedIn: Boolean = false,
    val role: Role = Role.Parent,
    val familyName: String = "",
    val accountPlan: AccountDeletionPlan = AccountDeletionPlan.LeaveFamily,
) {
    val canDeleteFamily: Boolean get() = server && signedIn && role == Role.Parent
    val canDeleteAccount: Boolean get() = server && signedIn

    /** A backend but nobody signed in: the server copy can only be deleted after signing in. */
    val needsSignIn: Boolean get() = server && !signedIn
}

/** How a deletion on the server ended, in the terms the screen needs. */
enum class DeletionOutcome { Done, LastParent, NotAllowed, SignInAgain, Offline, Failed }

/**
 * Mirrors `planFor` in functions/src/privacy.ts with the members this phone knows: a member with a `uid` has an account.
 * The server decides with its access index; this only chooses what the screen explains up front.
 */
fun accountPlan(role: Role, myMemberId: String?, members: List<Member>): AccountDeletionPlan {
    val others = members.filter { it.id != myMemberId && it.uid != null }
    return when {
        others.isEmpty() -> AccountDeletionPlan.DeletesFamily
        role == Role.Parent && others.none { it.role == Role.Parent } -> AccountDeletionPlan.LastParent
        else -> AccountDeletionPlan.LeaveFamily
    }
}

/**
 * GDPR (BRIEF §4.12 and §6): export what this phone has about the family as JSON, forget the family on this phone, and
 * delete the family or the account on the server through the callables in functions/src/privacy.ts. What each path
 * deletes and keeps is in docs/DECISIONS.md ("Radering av familj och konto").
 */
class DataPrivacy(
    private val local: SyncLocalStore,
    private val sessions: SessionProvider,
    private val coordinator: AppCoordinator,
    private val family: FamilyRepository,
    private val remote: FamilyRemote,
    private val auth: AuthRepository,
    private val purchases: PurchaseLogDao,
    private val prefDao: PreferenceDao,
    private val zone: TimeZone,
    private val clock: Clock = Clock.System,
    private val attachments: AttachmentFiles = NoAttachmentFiles,
) {
    private val log = Logger.withTag("Privacy")
    private val json = Json { prettyPrint = true }
    private val prefs = PreferencesRepository(prefDao)

    val options: Flow<PrivacyOptions> =
        combine(sessions.session, auth.currentUser, family.family, family.members) { session, user, current, members ->
            PrivacyOptions(
                server = remote.available,
                signedIn = user != null,
                role = session.role,
                familyName = current?.name.orEmpty(),
                accountPlan = accountPlan(session.role, session.memberId, members),
            )
        }.distinctUntilChanged()

    /** Whether someone else in the family has an account, who can invite this person back after [signOut] with forget. */
    val othersHaveAccounts: Flow<Boolean> = combine(sessions.session, family.members) { session, members ->
        members.any { it.uid != null && it.id != session.memberId }
    }.distinctUntilChanged()

    /**
     * "Logga ut" (ADR-059): signs out on this phone and, with [forget], removes the family from it like [forgetOnDevice].
     * The others in the family keep everything either way. Finishes even if the screen that asked closes.
     */
    suspend fun signOut(forget: Boolean) = withContext(NonCancellable) {
        auth.signOut()
        if (forget) forgetOnDevice()
    }

    /**
     * Everything this phone has about the family (format 2): the synced collections with a `_deleted` flag (a caregiver's
     * phone only has what is shared with them), the purchase history, this phone's session and personal preferences, and
     * the account's own `users/{uid}` profile and devices when they can be read. `notIncluded` names what is left out.
     */
    suspend fun exportJson(): String {
        val session = sessions.session.value
        val familyId = session.familyId
        val docs = familyId?.let { local.listFamily(it) }.orEmpty()
        val bought = familyId?.let { purchases.since(it, 0) }.orEmpty()
        val preferences = EXPORTED_PREFERENCES.mapNotNull { key -> prefDao.get(key)?.let { key to parse(it) } }.toMap()
        val account = session.uid?.takeIf { remote.available }?.let { readAccount(it) }
        val collections = docs.groupBy { it.collection }.entries.sortedBy { it.key }.associate { (collection, rows) ->
            collection to JsonArray(
                rows.sortedBy { it.docId }.map { row ->
                    val data = parse(row.json) as? JsonObject ?: JsonObject(emptyMap())
                    JsonObject(data + mapOf("_id" to JsonPrimitive(row.docId), "_deleted" to JsonPrimitive(row.deletedAt != null)))
                },
            )
        }
        val export = buildJsonObject {
            put("app", AppConfig.APP_NAME)
            put("exportedAt", clock.now().toString())
            put("format", FORMAT)
            put("scope", "thisPhone")
            put("familyId", familyId)
            put("role", json.encodeToJsonElement(Role.serializer(), session.role))
            put("collections", JsonObject(collections))
            put(
                "purchaseHistory",
                JsonArray(
                    bought.sortedBy { it.boughtAt }.map { p ->
                        buildJsonObject {
                            put("name", p.displayName)
                            put("normalizedName", p.normalizedName)
                            put("boughtAt", Instant.fromEpochMilliseconds(p.boughtAt).toString())
                        }
                    },
                ),
            )
            put(
                "thisPhone",
                buildJsonObject {
                    put("session", json.encodeToJsonElement(Session.serializer(), session))
                    put("preferences", JsonObject(preferences))
                },
            )
            put("account", account ?: JsonNull)
            put("notIncluded", JsonArray(notIncluded(session.role, account != null).map(::JsonPrimitive)))
        }
        return json.encodeToString(JsonObject.serializer(), export)
    }

    fun exportFileName(): String = "${AppConfig.APP_NAME.lowercase()}-export-${clock.todayIn(zone)}.json"

    /**
     * Removes the family from this phone only; the others in it keep everything. Besides the synced documents this clears
     * the purchase history (never synced, ADR-019), the preferences that point into the family and the pictures kept on
     * this phone (ADR-049).
     */
    suspend fun forgetOnDevice() = withContext(NonCancellable) {
        val familyId = sessions.session.value.familyId ?: return@withContext
        // RoomSyncLocalStore.deleteFamily clears the purchase history too; this keeps forgetting complete for every store.
        purchases.deleteFamily(familyId)
        FAMILY_PREFERENCES.forEach { prefDao.remove(it) }
        prefs.update(Prefs.SetupHidden) { it - familyId }
        attachments.deleteAll()
        // Last: the session change closes the screen that asked (and with it the caller's scope).
        coordinator.forgetFamilyOnDevice(local)
    }

    /** "Radera familjen för alla" (parents): deleted on the server for everyone, then forgotten here and signed out. */
    suspend fun deleteFamilyForEveryone(): DeletionOutcome {
        val familyId = sessions.session.value.familyId ?: return DeletionOutcome.Failed
        return deleteOnServer("family") { remote.deleteFamily(familyId) }
    }

    /** "Radera mitt konto": the account and its links on the server, then this phone forgets the family and signs out. */
    suspend fun deleteAccount(): DeletionOutcome = deleteOnServer("account") { remote.deleteAccount(sessions.session.value.familyId) }

    /** Finishes even if the screen closes: the server part cannot be undone, so this phone has to follow it. */
    private suspend fun deleteOnServer(what: String, call: suspend () -> Unit): DeletionOutcome = withContext(NonCancellable) {
        val refused = try {
            call()
            null
        } catch (e: DeletionRefusedException) {
            log.w { "Server refused to delete the $what: ${e.refusal}" }
            e.refusal.outcome()
        } catch (e: BackendUnavailableException) {
            log.w { "Deleting the $what needs a backend (${e::class.simpleName})" }
            DeletionOutcome.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "Could not delete the $what on the server (${e::class.simpleName})" }
            DeletionOutcome.Failed
        }
        refused ?: DeletionOutcome.Done.also { followServerDeletion(what) }
    }

    private suspend fun followServerDeletion(what: String) {
        try {
            forgetOnDevice()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "The $what is deleted on the server but still on this phone (${e::class.simpleName})" }
        }
        try {
            auth.signOut()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "Could not sign out after deleting the $what (${e::class.simpleName})" }
        }
        sessions.update { it.copy(uid = null) }
    }

    private suspend fun readAccount(uid: String): JsonObject? = try {
        withTimeoutOrNull(ACCOUNT_TIMEOUT) { remote.accountData(uid) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w { "Account data left out of the export (${e::class.simpleName})" }
        null
    }

    private fun parse(raw: String): JsonElement = try {
        json.parseToJsonElement(raw)
    } catch (e: IllegalArgumentException) {
        log.d { "Stored value is not JSON (${e::class.simpleName}); exported as text" }
        JsonPrimitive(raw)
    }

    private fun DeletionRefusal.outcome(): DeletionOutcome = when (this) {
        DeletionRefusal.NotSignedIn, DeletionRefusal.RecentLoginRequired -> DeletionOutcome.SignInAgain
        DeletionRefusal.NotAllowed -> DeletionOutcome.NotAllowed
        DeletionRefusal.LastParent -> DeletionOutcome.LastParent
        DeletionRefusal.Network -> DeletionOutcome.Offline
        DeletionRefusal.Unknown -> DeletionOutcome.Failed
    }

    companion object {
        const val FORMAT = 2

        private val ACCOUNT_TIMEOUT = 10.seconds

        /** Ids in the export's `notIncluded` list. */
        const val NOT_PUBLIC_DATA = "publicDataCache"
        const val NOT_SYNC_QUEUE = "syncQueue"
        const val NOT_SERVER_LINKS = "serverOnlyLinks"
        const val NOT_OTHER_PHONES = "otherPhones"
        const val NOT_SHARED_WITH_YOU = "notSharedWithYou"
        const val NOT_ACCOUNT = "account"

        /**
         * Left out on purpose: weather, school lunches, the food database, schools and raw calendar feeds are public data
         * fetched again (cache); queued changes are already in the collections; invites, calendar feed and handover links
         * live only on the server; other phones keep their own preferences and purchase history.
         */
        private fun notIncluded(role: Role, account: Boolean): List<String> = buildList {
            add(NOT_PUBLIC_DATA)
            add(NOT_SYNC_QUEUE)
            add(NOT_SERVER_LINKS)
            add(NOT_OTHER_PHONES)
            if (role != Role.Parent) add(NOT_SHARED_WITH_YOU)
            if (!account) add(NOT_ACCOUNT)
        }

        /** Personal, device-local preferences. App-state flags (onboarding done, developer tools) are not exported. */
        private val EXPORTED_PREFERENCES: List<String> = listOf(
            Prefs.Theme,
            Prefs.DynamicColor,
            Prefs.Language,
            Prefs.CalendarLayers,
            Prefs.CalendarViewMode,
            Prefs.CalendarPersonFilter,
            Prefs.DeviceCalendarsRead,
            Prefs.DeviceCalendarExport,
            Prefs.Morning,
            Prefs.QuietHours,
            Prefs.QuietHoursEnabled,
            Prefs.CrashReportsConsent,
            Prefs.ActiveShoppingList,
            Prefs.ActiveStore,
            Prefs.DismissedStaples,
            Prefs.RecentQuickAdds,
            Prefs.QuickAddSaves,
            Prefs.DeviceId,
            Prefs.TodayLayout,
            Prefs.HelperFullView,
            Prefs.SeenHints,
            Prefs.SetupHidden,
        ).map { it.name }

        /** Preferences that point into the family (its lists, stores, members, items, typed text): cleared when it is forgotten. */
        private val FAMILY_PREFERENCES: List<String> = listOf(
            Prefs.ActiveShoppingList,
            Prefs.ActiveStore,
            Prefs.CalendarPersonFilter,
            Prefs.DismissedStaples,
            Prefs.RecentQuickAdds,
            Prefs.TodayLayout,
        ).map { it.name }
    }
}
