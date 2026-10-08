package se.pusslet.android.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Everything that runs without the app open. Unique names, so calling this on every start is harmless. */
object BackgroundWork {
    private const val SUBSCRIPTIONS = "subscriptions-refresh"
    private const val SUBSCRIPTION_HOURS = 6L

    fun schedule(context: Context) {
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SUBSCRIPTIONS,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(SUBSCRIPTION_HOURS, TimeUnit.HOURS).setConstraints(online).build(),
        )
    }
}
