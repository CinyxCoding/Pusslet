package se.pusslet.android.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import se.pusslet.core.notify.NotificationKind
import se.pusslet.ui.components.TestNotification
import se.pusslet.ui.platform.AppPermission
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.platform.NoopPlatformActions
import se.pusslet.ui.platform.PlatformActions
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** "Skicka testnotis" (ADR-065): the notification itself, and what the button says about it. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
class TestNotificationTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val now = Instant.parse("2026-10-20T06:00:00Z")

    private fun allowNotifications() = shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun itComesNowOnTheChannelForReminders() = runBlocking {
        allowNotifications()

        assertTrue(AndroidNotifier(context).showTest(now))

        val shown = shadowOf(manager).allNotifications.single()
        assertEquals(NotificationKind.Reminder.channelId, shown.channelId)
        assertEquals("Testnotis från Pusslet", shown.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Så här ser en påminnelse ut.", shown.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun withNotificationsOffItSaysSo() = runBlocking {
        allowNotifications()
        shadowOf(manager).setNotificationsEnabled(false)

        assertFalse(AndroidNotifier(context).showTest(now))

        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    /** All other notifications may be on, but it comes on the channel for reminders and that one is off. */
    @Test
    fun withTheChannelOffItSaysSo() = runBlocking {
        allowNotifications()
        val off = NotificationChannel(NotificationKind.Reminder.channelId, "x", NotificationManager.IMPORTANCE_NONE)
        manager.createNotificationChannel(off)

        assertFalse(AndroidNotifier(context).showTest(now))

        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    private class FakePhone(val granted: Boolean = true, val shown: Boolean = true) : PlatformActions by NoopPlatformActions {
        val asked = mutableListOf<AppPermission>()
        var sent = 0
        var opened = 0

        override fun requestPermission(permission: AppPermission, onResult: (Boolean) -> Unit) {
            asked += permission
            onResult(granted)
        }

        override fun sendTestNotification(onResult: (Boolean) -> Unit) {
            sent++
            onResult(shown)
        }

        override fun openNotificationSettings() {
            opened++
        }
    }

    private fun press(phone: FakePhone) {
        compose.setContent { CompositionLocalProvider(LocalPlatformActions provides phone) { TestNotification() } }
        compose.onNodeWithText("Skicka testnotis").performClick()
        compose.waitForIdle()
    }

    @Test
    fun theButtonAsksForThePermissionAndSends() {
        val phone = FakePhone()

        press(phone)

        assertEquals(listOf(AppPermission.Notifications), phone.asked)
        assertEquals(1, phone.sent)
        compose.onNodeWithText("Testnotisen är skickad", substring = true).assertExists()
        compose.onNodeWithText("Öppna inställningar").assertDoesNotExist()
    }

    /** Android only asks once or twice; after that the way forward is the phone's own settings. */
    @Test
    fun aRefusedPermissionShowsTheWayToThePhonesSettings() {
        val phone = FakePhone(granted = false)

        press(phone)

        assertEquals(0, phone.sent)
        compose.onNodeWithText("Telefonen stoppar notiser från Pusslet", substring = true).assertExists()
        compose.onNodeWithText("Öppna inställningar").performClick()
        assertEquals(1, phone.opened)
    }

    @Test
    fun aStoppedNotificationShowsTheWayToThePhonesSettings() {
        val phone = FakePhone(shown = false)

        press(phone)

        assertEquals(1, phone.sent)
        compose.onNodeWithText("Telefonen stoppar notiser från Pusslet", substring = true).assertExists()
        compose.onNodeWithText("Öppna inställningar").assertExists()
    }

    /** A second try does not keep saying what the first one found. */
    @Test
    fun tryingAgainReplacesTheAnswer() {
        var shown = false
        val phone = object : PlatformActions by NoopPlatformActions {
            override fun requestPermission(permission: AppPermission, onResult: (Boolean) -> Unit) = onResult(true)

            override fun sendTestNotification(onResult: (Boolean) -> Unit) = onResult(shown)
        }
        compose.setContent { CompositionLocalProvider(LocalPlatformActions provides phone) { TestNotification() } }
        compose.onNodeWithText("Skicka testnotis").performClick()
        compose.onNodeWithText("Telefonen stoppar notiser från Pusslet", substring = true).assertExists()

        shown = true
        compose.onNodeWithText("Skicka testnotis").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Telefonen stoppar notiser från Pusslet", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Testnotisen är skickad", substring = true).assertExists()
    }
}
