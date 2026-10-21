package se.pusslet.data.sources

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import se.pusslet.core.model.CalendarSubscription
import se.pusslet.core.model.Role
import se.pusslet.data.AppProcessWork
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.session.SessionProvider
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What the subscription screens need besides the repository: a test download and "Uppdatera nu". */
interface SubscriptionFeeds {
    /** "Hämta och visa": one download of [url], nothing cached. */
    suspend fun preview(url: String): PreviewResult

    /** "Uppdatera nu": downloads every enabled subscription again, even a fresh copy. */
    suspend fun refreshAllNow(): List<SubscriptionStatus>
}

/**
 * Keeps the family's ICS subscriptions fresh while the app process lives, for parents only (caregivers cannot read
 * icsSubscriptions, firestore.rules). [start], called once by [se.pusslet.data.AppCoordinator], refreshes every enabled
 * subscription, then a subscription as soon as it is added, switched on or given a new link, and each one again
 * [interval] after its last download ([retryAfter] after a failed try). Timing is [clock] plus `delay` in the app scope;
 * nothing runs in a ViewModel.
 *
 * A platform worker (Android WorkManager) calls [refreshAll]. The lock keeps it and the in-process timer from fetching a
 * feed twice, and a copy younger than [SubscriptionSync.MAX_AGE] is never downloaded again unless forced.
 */
class SubscriptionRefresher(
    private val calendar: CalendarRepository,
    private val sync: SubscriptionSync,
    private val sessions: SessionProvider,
    private val clock: Clock,
    private val interval: Duration = SubscriptionSync.MAX_AGE,
    private val retryAfter: Duration = 1.hours,
) : AppProcessWork,
    SubscriptionFeeds {
    private val log = Logger.withTag("Subscriptions")
    private val lock = Mutex()
    private var job: Job? = null

    override fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            // Restarts for another family or role; stops (and does nothing) for caregivers and before onboarding.
            sessions.session.map { s -> s.familyId.takeIf { s.hasFamily && s.role == Role.Parent } }
                .distinctUntilChanged()
                .collectLatest { familyId -> if (familyId != null) keepFresh() }
        }
    }

    /**
     * Entry point for a periodic platform worker: refreshes every enabled subscription once ([force] also fresh copies).
     * Waits for the stored session, does nothing for caregivers and never throws for a bad feed or a lost network.
     */
    suspend fun refreshAll(force: Boolean = false): List<SubscriptionStatus> {
        withTimeoutOrNull(SESSION_WAIT) { sessions.loaded.first { it } } ?: return emptyList()
        if (!isParent()) return emptyList()
        val subs = calendar.subscriptions.first()
        return lock.withLock { sync.refreshAll(subs, force) }
    }

    override suspend fun refreshAllNow(): List<SubscriptionStatus> = refreshAll(force = true)

    override suspend fun preview(url: String): PreviewResult = sync.preview(url)

    private fun isParent(): Boolean = sessions.session.value.let { it.hasFamily && it.role == Role.Parent }

    private suspend fun keepFresh() {
        coroutineScope {
            launch { refreshChanges() }
            while (true) {
                delay(untilNextDue())
                refreshDue()
            }
        }
    }

    /** First everything, then what was added, switched on or given a new link (a new name or colour needs no download). */
    private suspend fun refreshChanges() {
        var known: Map<String, String?>? = null
        calendar.subscriptions.collect { list ->
            val enabled = list.filter { it.enabled }
            val links = enabled.associate { it.id to SubscriptionSync.normalizeUrl(it.url) }
            val before = known
            known = links
            val changed = if (before == null) enabled else enabled.filter { it.id !in before || before[it.id] != links[it.id] }
            if (changed.isNotEmpty()) refresh(changed)
        }
    }

    private suspend fun refreshDue() {
        val now = clock.now()
        val due = calendar.subscriptions.first().filter { it.enabled && dueAt(it) <= now }
        if (due.isNotEmpty()) refresh(due)
    }

    private suspend fun refresh(subs: List<CalendarSubscription>) {
        val statuses = lock.withLock { sync.refreshAll(subs) }
        // Counts only: no ids, links or names in the log.
        log.i { "Refreshed ${statuses.size} subscriptions, ${statuses.count { it.error != null }} failed" }
    }

    /** When [sub] should be fetched again: [interval] after its last download, [retryAfter] after a failed try. */
    private suspend fun dueAt(sub: CalendarSubscription): Instant {
        val status = calendar.subscriptionStatus(sub.id)
        val checked = status?.checkedAt ?: return Instant.DISTANT_PAST
        return if (status.error != null) checked + retryAfter else (status.fetchedAt ?: checked) + interval
    }

    private suspend fun untilNextDue(): Duration {
        val subs = calendar.subscriptions.first().filter { it.enabled }
        if (subs.isEmpty()) return interval
        val next = subs.minOf { dueAt(it) }
        return (next - clock.now()).coerceIn(minOf(MIN_WAIT, interval), interval)
    }

    private companion object {
        /** Never sooner than this, so a status that cannot be saved does not turn the timer into a busy loop. */
        val MIN_WAIT = 5.minutes
        val SESSION_WAIT = 30.seconds
    }
}
