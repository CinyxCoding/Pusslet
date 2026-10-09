package se.pusslet.android.screenshots

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.TestListenableWorkerBuilder
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.annotation.Config
import se.pusslet.android.work.SubscriptionRefreshWorker
import se.pusslet.core.model.CalendarSubscription
import se.pusslet.core.model.MemberColor
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.shell.PussletApp

/** A real public feed subscribed and fetched: the list, and week 44 where Swedish and US daylight saving differ. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
class SubscriptionsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() = TestEnvironment.stop()

    private fun startWithSubscription() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TestEnvironment.start(context, seed = true)
        runBlocking {
            GlobalContext.get().get<SessionProvider>().session.first { it.hasFamily }
            GlobalContext.get().get<CalendarRepository>().saveSubscription(
                CalendarSubscription("s-k8s", "Kubernetes community", TestEnvironment.PUBLIC_ICS_URL, color = MemberColor.Teal),
            )
            TestListenableWorkerBuilder<SubscriptionRefreshWorker>(context).build().doWork()
        }
        compose.setContent { PussletApp() }
        compose.waitUntilAtLeastOneExists(hasText("Ella", substring = true), timeoutMillis = 15_000)
    }

    private fun snap(name: String) = compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")

    @Test
    fun weekBetweenDaylightSavingChanges() {
        startWithSubscription()
        compose.onNodeWithText("Kalender").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Vecka 41"), timeoutMillis = 10_000)
        repeat(3) { compose.onNodeWithContentDescription("Nästa").performClick() }
        compose.waitUntilAtLeastOneExists(hasText("SIG Node CI Group: weekly"), timeoutMillis = 10_000)
        compose.onNodeWithText("18:00", substring = true).assertExists()
        snap("calendar_subscription_week44")
    }

    @Test
    fun subscriptionList() {
        startWithSubscription()
        compose.onNodeWithText("Kalender").performClick()
        // Wait for the tab to settle; during the transition the menu button is not in the merged tree yet.
        compose.waitUntilAtLeastOneExists(hasText("Vecka 41"), timeoutMillis = 10_000)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Visa i kalendern").performClick()
        compose.onNodeWithText("Prenumerationer …").performClick()
        compose.waitUntilAtLeastOneExists(hasText("hämtat", substring = true), timeoutMillis = 10_000)
        snap("subscriptions")
    }
}
