package se.pusslet.android.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.sources.SubscriptionSync

/** Fetches every enabled ICS subscription. Runs every six hours on any network; a failed feed keeps its last good copy. */
class SubscriptionRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val calendar: CalendarRepository by inject()
    private val sync: SubscriptionSync by inject()

    override suspend fun doWork(): Result {
        val subs = calendar.subscriptions.first()
        if (subs.isEmpty()) return Result.success()
        val statuses = sync.refreshAll(subs)
        val failed = statuses.count { it.error != null }
        Logger.withTag("Subscriptions").i { "Refreshed ${statuses.size} feeds, $failed failed" }
        return Result.success()
    }
}
