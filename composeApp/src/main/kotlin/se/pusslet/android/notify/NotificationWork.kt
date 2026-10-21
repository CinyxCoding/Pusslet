package se.pusslet.android.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import se.pusslet.core.notify.PlannedNotification
import se.pusslet.data.notify.NotificationPlanSource
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Local notifications on WorkManager (ADR-002, ADR-036). The plan comes from [NotificationPlanSource]; each planned
 * notification is one unique work named after its stable key, holding only that key. When it fires, [ShowNotificationWorker]
 * plans again and shows the notification only if it is still due, so nothing stale or personal is kept in WorkManager.
 *
 * Planned again: while the app runs, a moment after any change in the data or the settings; otherwise every
 * [PLAN_EVERY_HOURS] hours, well inside the planner's 48 hours.
 */
object NotificationWork {
    private val log = Logger.withTag("NotificationWork")

    private const val TAG = "pusslet-notify"
    private const val KEY_TAG = "pusslet-notify-key:"
    private const val PLAN_WORK = "pusslet-notify-plan"
    private const val PLAN_EVERY_HOURS = 12L
    private val CHANGE_DEBOUNCE: Duration = 2.seconds

    /** From `Application.onCreate`: the periodic planning, and planning on changes while the process lives. */
    @OptIn(FlowPreview::class)
    fun start(context: Context, plans: NotificationPlanSource, clock: Clock, scope: CoroutineScope) {
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(
            PLAN_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PlanNotificationsWorker>(PLAN_EVERY_HOURS, TimeUnit.HOURS).build(),
        )
        scope.launch {
            AndroidNotifier(context).ensureChannels()
            plans.changes()
                .debounce(CHANGE_DEBOUNCE)
                .catch { e -> log.w(e) { "Planning notifications stopped" } }
                .collectLatest { plan ->
                    // The app scope has no handler: a failed run is logged, and the next change or the periodic work tries again.
                    try {
                        schedule(context, plan, clock)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        log.w(e) { "Could not schedule notifications" }
                    }
                }
        }
    }

    /** Enqueues (or moves) every planned notification and cancels the ones no longer planned. */
    suspend fun schedule(context: Context, plan: List<PlannedNotification>, clock: Clock) {
        val work = WorkManager.getInstance(context)
        val now = clock.now()
        plan.forEach { n ->
            val request = OneTimeWorkRequestBuilder<ShowNotificationWorker>()
                .setInitialDelay((n.at - now).coerceAtLeast(Duration.ZERO).inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(ShowNotificationWorker.KEY to n.key))
                .addTag(TAG)
                .addTag(KEY_TAG + n.key)
                .build()
            work.enqueueUniqueWork(uniqueName(n.key), ExistingWorkPolicy.REPLACE, request)
        }
        val planned = plan.map { it.key }.toSet()
        work.getWorkInfosByTagFlow(TAG).first()
            .filter { !it.state.isFinished }
            .mapNotNull { info -> info.tags.firstOrNull { it.startsWith(KEY_TAG) }?.removePrefix(KEY_TAG) }
            .filter { it !in planned }
            .forEach { work.cancelUniqueWork(uniqueName(it)) }
        log.d { "Scheduled ${plan.size} notifications" }
    }

    private fun uniqueName(key: String) = "notify:$key"
}

/** Plans the next 48 hours again (periodic, see [NotificationWork]). */
class PlanNotificationsWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    override suspend fun doWork(): Result {
        NotificationWork.schedule(applicationContext, get<NotificationPlanSource>().planNow(), get<Clock>())
        return Result.success()
    }
}

/** Shows one planned notification at its time, if it is still planned then ([NotificationPlanSource.due]). */
class ShowNotificationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY) ?: return Result.success()
        val due = get<NotificationPlanSource>().due(key) ?: return Result.success()
        AndroidNotifier(applicationContext).show(due)
        return Result.success()
    }

    companion object {
        const val KEY = "key"
    }
}
