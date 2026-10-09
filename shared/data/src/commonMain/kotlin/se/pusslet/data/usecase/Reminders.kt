package se.pusslet.data.usecase

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import se.pusslet.core.model.Role
import se.pusslet.core.notify.PlannedNotification
import se.pusslet.core.notify.QuietHours
import se.pusslet.core.notify.ReminderPlanner
import se.pusslet.core.notify.ReminderSettings
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.minusDays
import se.pusslet.core.time.plusDays
import se.pusslet.core.today.TodayInput
import se.pusslet.core.today.TodayPlan
import se.pusslet.core.today.TodayPlanner
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.LunchRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.PackingRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sources.WeatherSource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Shows planned notifications at their time (WorkManager on Android). Keys are stable: replacing is idempotent. */
interface NotificationScheduler {
    suspend fun replaceAll(plan: List<PlannedNotification>)
}

object NoNotifications : NotificationScheduler {
    override suspend fun replaceAll(plan: List<PlannedNotification>) = Unit
}

/** Re-plans this phone's reminders whenever events, tasks or notification settings change (and hourly as a safety net). */
class ReminderSync(
    private val calendar: CalendarRepository,
    private val tasks: TaskRepository,
    private val family: FamilyRepository,
    private val prefs: PreferencesRepository,
    private val sessions: SessionProvider,
    private val scheduler: NotificationScheduler,
    private val clock: Clock,
    private val zone: TimeZone,
) {
    private val log = Logger.withTag("Reminders")

    suspend fun plan(): List<PlannedNotification> {
        val me = sessions.session.value.memberId ?: return emptyList()
        val today = clock.todayIn(zone)
        val adults = family.people.first().filter { !it.isChild }.map { it.id }.toSet()
        val occurrences = calendar.occurrences(today.minusDays(1), today.plusDays(DAYS_AHEAD), zone).first()
        val quiet = if (prefs.get(Prefs.QuietHoursEnabled)) prefs.get(Prefs.QuietHours).let { QuietHours(it.start, it.end) } else null
        val morning = prefs.get(Prefs.Morning)
        val settings = ReminderSettings(morning.enabled, morning.time, quiet)
        val parent = sessions.session.value.role == Role.Parent
        return ReminderPlanner.plan(me, adults, occurrences, tasks.tasks.first(), settings, clock.now(), zone, iAmParent = parent)
    }

    suspend fun replanNow() {
        try {
            scheduler.replaceAll(plan())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "Re-planning reminders failed: ${e::class.simpleName}" }
        }
    }

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        val hourly = flow {
            while (true) {
                emit(Unit)
                delay(1.hours)
            }
        }
        scope.launch {
            combine(
                hourly,
                calendar.events,
                tasks.tasks,
                combine(prefs.observe(Prefs.Morning), prefs.observe(Prefs.QuietHours), prefs.observe(Prefs.QuietHoursEnabled)) { _, _, _ ->
                },
                sessions.session,
            ) { _, _, _, _, _ -> }
                .debounce(DEBOUNCE)
                .collect { replanNow() }
        }
    }

    private companion object {
        const val DAYS_AHEAD = 3
        val DEBOUNCE = 2.seconds
    }
}

/** The day's plan outside the Today screen – for the morning notification and the Today widget. */
class TodayPlans(
    private val family: FamilyRepository,
    private val calendar: CalendarRepository,
    private val packing: PackingRepository,
    private val meals: MealRepository,
    private val tasks: TaskRepository,
    private val lunches: LunchRepository,
    private val weather: WeatherSource,
    private val sessions: SessionProvider,
    private val zone: TimeZone,
) {
    suspend fun plan(date: LocalDate, now: LocalTime, withWeather: Boolean = true): TodayPlan {
        val place = family.settings.first().weatherPlace?.takeIf { withWeather }
        val forecast = place?.let { runCatching { weather.forecast(it, force = false) }.getOrNull()?.value }
        return TodayPlanner.build(
            TodayInput(
                today = date,
                now = now,
                zone = zone,
                me = sessions.session.value.memberId,
                people = family.everyone.first(),
                children = family.children.first(),
                occurrences = calendar.occurrences(date, date.plusDays(LOOK_AHEAD_DAYS), zone).first(),
                packingRules = packing.rules.first(),
                packingChecks = packing.checks.first(),
                custody = family.custody.first(),
                mealWeeks = meals.weeks.first().filter { it.week == date.isoWeek || it.week == date.plusDays(UPCOMING_DAYS).isoWeek },
                lunches = lunches.lunches.first(),
                tasks = tasks.tasks.first(),
                forecast = forecast,
            ),
        )
    }

    private companion object {
        const val UPCOMING_DAYS = 3

        /** Cards look further than the day previews: a party in two weeks may need an answer tomorrow. */
        const val LOOK_AHEAD_DAYS = 14
    }
}
