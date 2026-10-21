package se.pusslet.android.calendar

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import se.pusslet.data.sources.SubscriptionRefresher
import se.pusslet.data.sources.SubscriptionSync
import java.util.concurrent.TimeUnit

/**
 * ICS subscriptions also when the app is closed (ADR-002, ADR-013): a periodic WorkManager job with network, every
 * [SubscriptionSync.MAX_AGE]. While the app runs, [SubscriptionRefresher] keeps them fresh on its own; its lock and the
 * maximum age keep the two from fetching a feed twice. Caregivers' phones do nothing (the refresher checks the role).
 */
object SubscriptionWork {
    private const val NAME = "pusslet-ics-refresh"

    /** From `Application.onCreate`; KEEP leaves an already planned job alone. */
    fun start(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshSubscriptionsWorker>(SubscriptionSync.MAX_AGE.inWholeMinutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Downloads the family's subscriptions that are due; a bad feed or a lost network is recorded on it, never thrown. */
class RefreshSubscriptionsWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    override suspend fun doWork(): Result {
        get<SubscriptionRefresher>().refreshAll()
        return Result.success()
    }
}
