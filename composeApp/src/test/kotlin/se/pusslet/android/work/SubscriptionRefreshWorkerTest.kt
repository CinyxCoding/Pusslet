package se.pusslet.android.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import se.pusslet.android.screenshots.TestEnvironment
import se.pusslet.core.model.CalendarSubscription
import se.pusslet.core.model.EventSource
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.session.SessionProvider
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 3 acceptance, end to end: a public ICS feed added as a subscription is fetched by the background worker and
 * its recurring meetings show in the family calendar at the right Swedish times across both DST changes.
 */
@RunWith(AndroidJUnit4::class)
class SubscriptionRefreshWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = TestEnvironment.stop()

    @Test
    fun workerFetchesFeedAndCalendarShowsItInSwedishTime() = runBlocking {
        TestEnvironment.start(context, seed = true)
        val calendar = GlobalContext.get().get<CalendarRepository>()
        // The demo family is created in the background on start.
        withTimeout(TIMEOUT_MS) { GlobalContext.get().get<SessionProvider>().session.first { it.hasFamily } }
        calendar.saveSubscription(CalendarSubscription("s-k8s", "Kubernetes", TestEnvironment.PUBLIC_ICS_URL))

        val result = TestListenableWorkerBuilder<SubscriptionRefreshWorker>(context).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val status = calendar.subscriptionStatus("s-k8s").first()
        assertNull(status.lastError)
        assertEquals(7, status.eventCount)

        val meetings = calendar.occurrences(LocalDate(2026, 10, 19), LocalDate(2026, 11, 8), TestEnvironment.zone).first()
            .filter { it.source == EventSource.Subscription && it.event.title == "SIG Node CI Group: weekly" }
            .map { it.start }
        assertEquals(
            listOf(LocalDateTime(2026, 10, 21, 19, 0), LocalDateTime(2026, 10, 28, 18, 0), LocalDateTime(2026, 11, 4, 19, 0)),
            meetings,
        )
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
