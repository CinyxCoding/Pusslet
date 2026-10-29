package se.pusslet.android.platform

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import se.pusslet.android.attachments.AndroidAttachmentFiles
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** "Ring 1177" in the medicine view (ADR-061): the phone app opens with the number, and the user presses call. */
@RunWith(AndroidJUnit4::class)
class DialTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
    private val platform = AndroidPlatformActions(activity, AndroidAttachmentFiles(activity))
    private val started = shadowOf(activity)

    @Test
    fun onlyAPlainNumberOpensThePhoneAppWithoutCalling() {
        listOf("", "tel:1177", "1177;", "*#06#", "11 77", "+", "https://www.1177.se").forEach { number ->
            platform.dial(number)
            assertNull(started.nextStartedActivity, number)
        }

        platform.dial("1177")
        val dial = assertNotNull(started.nextStartedActivity)
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("tel:1177", dial.dataString)
    }
}
