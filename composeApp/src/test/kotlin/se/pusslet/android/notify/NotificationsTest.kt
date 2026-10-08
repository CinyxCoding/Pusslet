package se.pusslet.android.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import se.pusslet.android.screenshots.TestEnvironment
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.notify.PlannedNotification
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.seed.DemoSeed
import se.pusslet.data.session.SessionProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv")
class NotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @After
    fun tearDown() = TestEnvironment.stop()

    private fun start() = runBlocking {
        TestEnvironment.start(context, seed = true)
        withTimeout(15_000) {
            GlobalContext.get().get<SessionProvider>().session.first { it.hasFamily }
            // The demo family is written in the background; wait until the whole week is there.
            GlobalContext.get().get<FamilyRepository>().children.first { kids -> kids.any { it.id == DemoSeed.SIMON } }
            GlobalContext.get().get<MealRepository>().weeks.first { it.isNotEmpty() }
        }
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(context)
    }

    private fun fire(plan: PlannedNotification) = runBlocking {
        val data = Data.Builder().putString(NotificationWorker.PLAN, Json.encodeToString(PlannedNotification.serializer(), plan)).build()
        TestListenableWorkerBuilder<NotificationWorker>(context).setInputData(data).build().doWork()
    }

    private fun posted() = shadowOf(manager).allNotifications

    @Test
    fun channelsAreCreatedInSwedish() {
        start()
        val names = manager.notificationChannels.associate { it.id to it.name.toString() }
        assertEquals("Lämna och hämta", names["handoffs"])
        assertEquals("Morgonkollen", names["morning"])
        assertEquals(5, names.size)
    }

    @Test
    fun morningBriefSummarisesTheDay() {
        start()
        fire(PlannedNotification.Morning("morning:2026-10-08", TestEnvironment.now, LocalDate(2026, 10, 8)))
        val n = posted().single()
        assertEquals("God morgon! Torsdag, vecka 41", n.extras.getString(NotificationCompat.EXTRA_TITLE))
        val lines = n.extras.getCharSequenceArray(NotificationCompat.EXTRA_TEXT_LINES).orEmpty().map { it.toString() }
        assertTrue(lines.any { it.startsWith("Middag:") }, "dinner from the meal plan: $lines")
        assertTrue(lines.size in 3..8, "a short summary: $lines")
        assertEquals("morning", n.channelId)
    }

    @Test
    fun pickUpNudgeNamesTheChild() {
        start()
        fire(
            PlannedNotification.Handoff(
                "handoff:x",
                TestEnvironment.now,
                eventId = "unknown",
                occurrenceDate = LocalDate(2026, 10, 8),
                kind = HandoffKind.PickUp,
                time = LocalTime(16, 0),
                childIds = listOf(DemoSeed.SIMON),
            ),
        )
        val n = posted().single()
        assertEquals("Dags att hämta Simon", n.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals("handoffs", n.channelId)
    }

    @Test
    fun nothingIsPostedWithoutPermission() {
        start()
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        fire(PlannedNotification.TaskDue("task:t", TestEnvironment.now, "t", "Ring skolan", LocalDate(2026, 10, 8)))
        assertTrue(posted().isEmpty())
    }

    @Test
    fun schedulerReplacesOnlyWhatChanged() = runBlocking {
        start()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val scheduler = WorkManagerNotificationScheduler(context, GlobalContext.get().get())
        val a = PlannedNotification.TaskDue("task:a", TestEnvironment.now + 2.hours, "a", "A", LocalDate(2026, 10, 8))
        val b = PlannedNotification.TaskDue("task:b", TestEnvironment.now + 3.hours, "b", "B", LocalDate(2026, 10, 8))
        scheduler.replaceAll(listOf(a, b))
        val work = WorkManager.getInstance(context)

        fun queued() = work.getWorkInfosByTag("pusslet-notification").get().filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(2, queued().size)
        val firstId = queued().first { "key:task:a" in it.tags }.id

        scheduler.replaceAll(listOf(a))
        assertEquals(listOf(firstId), queued().map { it.id }, "a unchanged is kept, b is cancelled")

        scheduler.replaceAll(listOf(a.copy(at = a.at + 1.hours)))
        assertTrue(queued().single().id != firstId, "a moved, so it is rescheduled")
    }
}
