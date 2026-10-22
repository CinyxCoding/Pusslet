package se.pusslet.data.notify

import co.touchlab.kermit.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.notify.PlannedNotification
import se.pusslet.core.notify.QuietSettings
import se.pusslet.core.notify.ReminderInput
import se.pusslet.core.notify.ReminderPlanner
import se.pusslet.core.today.TodayInput
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.Session
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.TodayInputSource
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The local notifications of the next [ReminderPlanner.HORIZON] on this phone, from Room (offline first): every calendar
 * layer (a reminder never depends on Kalender's filter, ADR-031), accepted hand-off answers, tasks, packing, the session
 * and the notification preferences (`Prefs.Morning`, `Prefs.QuietHours`, `Prefs.QuietHoursEnabled`). Quiet hours and
 * bundling are applied ([ReminderPlanner.schedule]).
 *
 * Meant for a WorkManager worker (ADR-002): [planNow] when scheduling, [due] when a scheduled one fires. There is no
 * timer here; the caller decides when to plan again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPlanSource(
    private val today: TodayInputSource,
    private val prefs: PreferencesRepository,
    private val sessions: SessionProvider,
    private val clock: Clock,
    private val zone: TimeZone,
) {
    private val log = Logger.withTag("NotifyPlan")

    /** What to schedule from now; empty without a family on this phone. */
    suspend fun planNow(): List<PlannedNotification> = plan(clock.now())

    /**
     * The notification [key] as it is planned now, for a worker that fires at its time: null when it was removed, was
     * merged into a summary or moved more than [grace] away (then the next planning run has scheduled it anew). Planning
     * again at fire time means the text is current ("Allt är packat" since) and nothing stale is stored with the work.
     */
    suspend fun due(key: String, grace: Duration = DUE_GRACE): PlannedNotification? {
        val now = clock.now()
        val found = plan(now - grace).firstOrNull { it.key == key }
        if (found == null) log.d { "Planned notification gone at fire time" }
        return found?.takeIf { it.at <= now + grace }
    }

    /**
     * The plan again whenever the data or the preferences change, for the app process to reschedule while it runs (the
     * caller should debounce). Each value is planned from the clock's time at that change.
     */
    fun changes(): Flow<List<PlannedNotification>> = sessions.session
        .map { it.hasFamily }
        .distinctUntilChanged()
        .flatMapLatest { hasFamily ->
            if (!hasFamily) {
                flowOf(emptyList())
            } else {
                val date = clock.now().toLocalDateTime(zone).date
                combine(
                    today.household,
                    today.occurrences(date, TodayInputSource.Layers.All),
                    today.extras,
                    today.requests,
                    settings(),
                ) { h, occ, ex, requests, s ->
                    val now = clock.now()
                    val input = today.input(now.toLocalDateTime(zone), h, occ, ex, requests)
                    ReminderPlanner.schedule(reminderInput(input, h.session, s, now))
                }
            }
        }

    private suspend fun plan(now: Instant): List<PlannedNotification> {
        val session = sessions.session.value
        if (!session.hasFamily) return emptyList()
        val input = today.current(TodayInputSource.Layers.All)
        return ReminderPlanner.schedule(reminderInput(input, session, settings().first(), now))
    }

    private data class Settings(val morning: Boolean, val morningAt: LocalTime, val quiet: QuietSettings, val pickupWarnings: Boolean)

    private fun settings(): Flow<Settings> = combine(
        prefs.observe(Prefs.Morning),
        prefs.observe(Prefs.QuietHours),
        prefs.observe(Prefs.QuietHoursEnabled),
        prefs.observe(Prefs.PickupWarnings),
    ) { morning, window, enabled, pickups ->
        Settings(morning.enabled, morning.time, QuietSettings(window, enabled), pickups)
    }

    private fun reminderInput(base: TodayInput, session: Session, s: Settings, now: Instant) = ReminderInput(
        base = base,
        now = now,
        role = session.role,
        morningAt = s.morningAt.takeIf { s.morning },
        quiet = s.quiet,
        pickupWarnings = s.pickupWarnings,
    )

    companion object {
        /** How late a worker may fire and still show its notification. */
        val DUE_GRACE: Duration = 15.minutes
    }
}
