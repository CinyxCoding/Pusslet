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

    @Test
    fun handoffRequestCanBeAnsweredFromTheNotification() = runBlocking {
        start()
        val koin = GlobalContext.get()
        val calendar = koin.get<se.pusslet.data.repository.CalendarRepository>()
        val event = calendar.events.first().first { it.pickUp != null && DemoSeed.SIMON in it.participantIds }
        val date = event.start.date
        // Someone else asks; the demo phone belongs to Sara.
        val request = se.pusslet.core.model.HandoffRequest("req-1", event.id, date, HandoffKind.PickUp, fromMemberId = DemoSeed.JOHAN)
        calendar.saveHandoffRequest(request)
        val requests = koin.get<se.pusslet.data.usecase.HandoffRequests>()
        val alert = requests.alertFor(request, koin.get<se.pusslet.data.repository.FamilyRepository>().people.first().associateBy { it.id })
        AndroidFamilyAlerts(context, this, koin.get(), koin.get(), koin.get()).handoffRequest(requireNotNull(alert))
        withTimeout(5_000) { while (posted().isEmpty()) kotlinx.coroutines.delay(20) }
        val n = posted().single()
        assertEquals("Kan du hämta Simon?", n.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals(listOf("Jag tar det", "Kan inte"), n.actions.map { it.title.toString() })

        n.actions.first().actionIntent.send()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        withTimeout(5_000) {
            while (calendar.handoffRequests.first().single { it.id == "req-1" }.status == se.pusslet.core.model.RequestStatus.Pending) {
                kotlinx.coroutines.delay(20)
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
            }
        }
        assertEquals(se.pusslet.core.model.RequestStatus.Accepted, calendar.handoffRequests.first().single { it.id == "req-1" }.status)
    }

    /** The real Android alerts instead of the test environment's silent ones. */
    private fun useAndroidAlerts(scope: kotlinx.coroutines.CoroutineScope) {
        val koin = GlobalContext.get()
        org.koin.core.context.loadKoinModules(
            org.koin.dsl.module {
                single<se.pusslet.data.usecase.FamilyAlerts> { AndroidFamilyAlerts(context, scope, koin.get(), koin.get(), koin.get()) }
            },
        )
    }

    private suspend fun awaitPosted(count: Int) = withTimeout(5_000) {
        while (posted().size < count ||
            posted().any { it.extras.getString(NotificationCompat.EXTRA_TITLE) == null }
        ) {
            kotlinx.coroutines.delay(20)
        }
    }

    @Test
    fun pushesBecomeNotificationsAndListAdditionsAreMerged() = runBlocking {
        start()
        useAndroidAlerts(this)
        val service = org.robolectric.Robolectric.setupService(PussletMessagingService::class.java)

        fun push(vararg pairs: Pair<String, String>) =
            service.onMessageReceived(com.google.firebase.messaging.RemoteMessage.Builder("test").setData(mapOf(*pairs)).build())

        push(
            "type" to "handoff_request",
            "requestId" to "r9",
            "fromName" to "Johan",
            "kind" to "pickup",
            "children" to "Simon",
            "eventId" to "e",
            "eventTitle" to "Fotboll",
            "date" to "2026-10-08",
            "time" to "18:15",
        )
        awaitPosted(1)
        assertEquals("Kan du hämta Simon?", posted().single().extras.getString(NotificationCompat.EXTRA_TITLE))

        push("type" to "shopping_added", "listId" to "l1", "byName" to "Johan", "name" to "Mjölk", "date" to "2026-10-08")
        awaitPosted(2)
        push("type" to "shopping_added", "listId" to "l1", "byName" to "Johan", "name" to "Bröd", "date" to "2026-10-08")
        withTimeout(5_000) {
            while (posted().none {
                    it.extras.getString(NotificationCompat.EXTRA_TITLE) == "Johan lade till 2 saker"
                }
            ) {
                kotlinx.coroutines.delay(20)
            }
        }
        val shopping = posted().filter { it.channelId == "shopping" }
        assertEquals(1, shopping.size, "one notification per list, updated")
        assertEquals(
            listOf("Johan lade till Mjölk", "Johan lade till Bröd"),
            shopping.single().extras.getCharSequenceArray(NotificationCompat.EXTRA_TEXT_LINES)?.map { it.toString() },
        )
    }
}
