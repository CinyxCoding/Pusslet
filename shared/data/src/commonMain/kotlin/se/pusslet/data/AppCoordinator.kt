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
import kotlinx.datetime.todayIn
import se.pusslet.core.model.Family
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
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
import kotlin.time.Clock

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
    /** Developer rows in Settings ("Visa första start igen"): demo and dev builds only, never prod. Defaults to the demo flavor. */
    val developerTools: Boolean = seedDemoData,
)

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
    private val zone: TimeZone,
    private val clock: Clock = Clock.System,
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
            // After "Visa första start igen" the welcome screen stays, even when the app is restarted.
            if (config.seedDemoData && !session.hasFamily && !prefs.get(Prefs.SkipDemoSeed)) {
                seed.seed(clock.todayIn(zone))
                prefs.set(Prefs.OnboardingDone, true)
            }
            _ready.value = true
            if (config.mode == BackendMode.Emulator && auth.currentUser.value == null && auth.available) {
                runCatching { auth.signInAnonymously() }.onFailure { log.i { "Emulator not reachable yet; staying local" } }
            }
            combine(sessions.session, auth.currentUser) { s, user -> Triple(s.familyId, s.memberId, user?.uid) to s.role }
                .distinctUntilChanged()
                .collectLatest { (ids, role) ->
                    val (familyId, memberId, uid) = ids
                    if (familyId == null || memberId == null || uid == null || !familyRemote.available) {
                        engine.stop()
                    } else {
                        connect(familyId, memberId, uid, role)
                    }
                }
        }
    }

    private suspend fun connect(familyId: String, memberId: String, uid: String, role: Role) {
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
        engine.start(scope, familyId, uid, role)
    }

    /** Leaves the current family on this device (data stays for the others). */
    suspend fun forgetFamilyOnDevice(local: SyncLocalStore) {
        val fid = sessions.session.value.familyId ?: return
        engine.stop()
        local.deleteFamily(fid)
        sessions.update { it.copy(familyId = null, memberId = null, demo = false) }
    }

    /**
     * Developer tool ([BackendConfig.developerTools] only): removes the family and the example data from this device
     * and resets onboarding, so the welcome screen shows again. The demo family is not seeded again at the next start.
     * Fails soft: returns false (and changes nothing visible) when it is not allowed or something went wrong.
     */
    suspend fun replayOnboarding(local: SyncLocalStore): Boolean {
        if (!config.developerTools) return false
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
