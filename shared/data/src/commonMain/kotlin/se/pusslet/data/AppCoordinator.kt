package se.pusslet.data

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import se.pusslet.core.medicine.MedicineRules
import se.pusslet.core.model.Family
import se.pusslet.core.model.MedicineCourse
import se.pusslet.core.model.MedicineEntry
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.core.model.SickDay
import se.pusslet.core.sick.SickDays
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.seed.DemoSeed
import se.pusslet.data.session.SessionRepository
import se.pusslet.data.sync.Collections
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.RemotePermissionException
import se.pusslet.data.sync.SyncEngine
import se.pusslet.data.sync.SyncLocalStore
import se.pusslet.data.usecase.OtherHomeSharing
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

enum class BackendMode {
    /** Firebase Local Emulator Suite (demo flavor). */
    Emulator,

    /** A real Firebase project (dev/prod with google-services.json). */
    Firebase,

    /** No backend configured: everything works on this device only. */
    LocalOnly,
}

data class BackendConfig(
    val mode: BackendMode,
    val seedDemoData: Boolean,
    val versionName: String,
    val emulatorHost: String = "10.0.2.2",
    /** Host for invite links (https://<linkHost>/join/<code>). */
    val linkHost: String = "pusslet.web.app",
    /** Developer rows in Settings ("Radera och börja om"): demo and dev builds only, never prod. Defaults to the demo flavor. */
    val developerTools: Boolean = seedDemoData,
    /** Where the familyCalendarFeed function answers (ADR-041); null without a backend, so no feed link is offered. */
    val calendarFeedBase: String? = null,
) {
    /**
     * The public privacy policy, built from docs/PRIVACY.md and published with Firebase Hosting on the invite links' host;
     * null without a server, where there is no page to show.
     */
    val privacyPolicyUrl: String? get() = if (mode == BackendMode.Firebase) "https://$linkHost/privacy/" else null
}

/**
 * Work that lives as long as the app process, such as keeping ICS subscriptions fresh. [AppCoordinator.start] starts each
 * one once, in the app scope, after the stored session has been read.
 */
fun interface AppProcessWork {
    fun start(scope: CoroutineScope)
}

/**
 * App-level wiring: restores the session, seeds the demo family, signs in anonymously against the emulator,
 * registers the account in the family's access index and keeps the sync engine running while signed in.
 */
class AppCoordinator(
    private val scope: CoroutineScope,
    private val config: BackendConfig,
    private val sessions: SessionRepository,
    private val auth: AuthRepository,
    private val familyRemote: FamilyRemote,
    private val docs: DocumentStore,
    private val engine: SyncEngine,
    private val seed: DemoSeed,
    private val prefs: PreferencesRepository,
    /** Null in tests that do not share with another home. */
    private val otherHome: OtherHomeSharing? = null,
    private val zone: TimeZone,
    private val clock: Clock = Clock.System,
    private val processWork: List<AppProcessWork> = emptyList(),
) {
    private val log = Logger.withTag("App")
    private val _ready = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** True once the session is restored (and the demo family seeded): the UI can decide between onboarding and Today. */
    val ready: kotlinx.coroutines.flow.StateFlow<Boolean> = _ready

    /** True while the family on this device is the example family ("Familjen Lind"), for a quiet "Exempelfamilj" row. */
    val isDemo: Flow<Boolean> = sessions.session.map { it.demo }.distinctUntilChanged()

    fun start() {
        scope.launch {
            val session = sessions.load()
            // After "Radera och börja om" the welcome screen stays, even when the app is restarted.
            if (config.seedDemoData && !session.hasFamily && !prefs.get(Prefs.SkipDemoSeed)) {
                seed.seed(clock.todayIn(zone))
                prefs.set(Prefs.OnboardingDone, true)
            }
            _ready.value = true
            processWork.forEach { it.start(scope) }
            purgeDeleted()
            if (config.mode == BackendMode.Emulator && auth.currentUser.value == null && auth.available) {
                runCatching { auth.signInAnonymously() }.onFailure { log.i { "Emulator not reachable yet; staying local" } }
            }
            combine(sessions.session, auth.currentUser) { s, user -> Connection(s.familyId, s.memberId, user?.uid, s.role, s.spaceIds) }
                .distinctUntilChanged()
                .collectLatest { c ->
                    if (c.familyId == null || c.memberId == null || c.uid == null || !familyRemote.available) {
                        engine.stop()
                    } else {
                        connect(c.familyId, c.memberId, c.uid, c.role, c.spaces)
                    }
                }
        }
    }

    /**
     * What was deleted more than 30 days ago leaves this phone too (ADR-046), and a parent's phone removes sick days from
     * before last year, which no longer count for VAB (ADR-047), and the medicine log after [MedicineRules.KEEP_DAYS] days
     * (ADR-050); the purge then clears them. Counts only in the log.
     */
    private suspend fun purgeDeleted() {
        try {
            if (sessions.session.value.role == Role.Parent) {
                val expired = SickDays.expired(docs.getAll(Collections.SickDays, SickDay.serializer()), clock.todayIn(zone))
                expired.forEach { docs.delete(Collections.SickDays, it.id) }
                if (expired.isNotEmpty()) log.i { "Removed ${expired.size} old sick days" }
                // The medicine log is for the illness at hand (ADR-050).
                val keepFrom = clock.now() - MedicineRules.KEEP_DAYS.days
                val oldDoses = MedicineRules.expired(docs.getAll(Collections.MedicineLog, MedicineEntry.serializer()), keepFrom)
                oldDoses.forEach { docs.delete(Collections.MedicineLog, it.id) }
                val oldCourses = MedicineRules.expiredCourses(
                    docs.getAll(Collections.MedicineCourses, MedicineCourse.serializer()),
                    keepFrom.toLocalDateTime(zone).date,
                )
                oldCourses.forEach { docs.delete(Collections.MedicineCourses, it.id) }
                if (oldDoses.size + oldCourses.size > 0) log.i { "Removed ${oldDoses.size + oldCourses.size} old medicine entries" }
            }
            val gone = docs.purgeDeleted()
            if (gone > 0) log.i { "Purged $gone deleted documents" }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.w(e) { "Could not purge deleted documents" }
        }
    }

    private data class Connection(val familyId: String?, val memberId: String?, val uid: String?, val role: Role, val spaces: List<String>)

    private suspend fun connect(familyId: String, memberId: String, uid: String, role: Role, spaces: List<String>) {
        if (sessions.session.value.uid != uid) sessions.update { it.copy(uid = uid) }
        if (role == Role.Parent) {
            // Claim a family created before sign-in, then register in the access index.
            docs.update(Collections.Family, familyId, Family.serializer()) { if (it.createdBy.isEmpty()) it.copy(createdBy = uid) else it }
            docs.update(Collections.Members, memberId, Member.serializer()) { if (it.uid == null) it.copy(uid = uid) else it }
        }
        // The access doc must exist before anything is pushed, otherwise the rules would reject the queued changes.
        var backoff = 2_000L
        while (true) {
            try {
                if (role == Role.Parent) {
                    val name = docs.get(Collections.Family, familyId, Family.serializer())?.name.orEmpty()
                    familyRemote.bootstrapFamily(familyId, name, memberId, uid)
                }
                familyRemote.rememberFamily(uid, familyId)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemotePermissionException) {
                log.w(e) { "Family bootstrap refused; syncing what the rules allow" }
                break
            } catch (e: Exception) {
                log.i { "Family bootstrap waiting for network (${e::class.simpleName})" }
                delay(backoff)
                backoff = minOf(backoff * 2, 5 * 60_000L)
            }
        }
        engine.start(scope, familyId, uid, role, if (role == Role.Parent) spaces else emptyList())
        if (role == Role.Parent) {
            // Join spaces the family's other parent started, forget ones the family left (ADR-056).
            combine(
                docs.observeAll(Collections.Family, Family.serializer()),
                docs.observeSpaces(Collections.Family, Family.serializer()),
            ) { home, shared -> home to shared }
                .distinctUntilChanged()
                .collectLatest { otherHome?.reconcile() }
        }
    }

    /** Leaves the current family on this device (data stays for the others). */
    suspend fun forgetFamilyOnDevice(local: SyncLocalStore) {
        val fid = sessions.session.value.familyId ?: return
        val spaces = sessions.session.value.spaceIds
        engine.stop()
        local.deleteFamily(fid)
        spaces.forEach { local.deleteFamily(it) }
        sessions.update { it.copy(familyId = null, memberId = null, demo = false, spaceIds = emptyList()) }
    }

    /**
     * Removes the family and the example data from this device and resets onboarding, so the welcome screen shows again.
     * The demo family is not seeded again at the next start. Allowed as a developer tool ([BackendConfig.developerTools])
     * and, in every build, to leave the example family ("Börja på riktigt" on Idag, UX plan 4.8): any flavor can open it
     * from the welcome screen. A real family in a production build is never removed this way.
     * Fails soft: returns false (and changes nothing visible) when it is not allowed or something went wrong.
     */
    suspend fun replayOnboarding(local: SyncLocalStore): Boolean {
        if (!config.developerTools && !sessions.session.value.demo) return false
        // The screen that asked is torn down as soon as the session changes; finish the steps anyway.
        return withContext(NonCancellable) {
            try {
                prefs.set(Prefs.SkipDemoSeed, true)
                forgetFamilyOnDevice(local)
                prefs.set(Prefs.OnboardingDone, false)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "Could not reset onboarding (${e::class.simpleName})" }
                false
            }
        }
    }
}
