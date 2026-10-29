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
import kotlin.test.assertTrue

/** Links reach [AndroidPlatformActions.openUrl] from synced data, e.g. a wish the other home wrote. */
@RunWith(AndroidJUnit4::class)
class OpenUrlTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
    private val platform = AndroidPlatformActions(activity, AndroidAttachmentFiles(activity))
    private val started = shadowOf(activity)

    @Test
    fun onlyWebLinksAreOpenedAndOnlyByAnAppThatOpensLinks() {
        listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.example.evil;end",
            "file:///data/data/se.pusslet.app/databases/pusslet.db",
            "content://se.pusslet.app.files/attachments/a.jpg",
            "swish://payment?data=x",
            "javascript:alert(1)",
            "https://",
            "lekia.se/lego",
        ).forEach { link ->
            platform.openUrl(link)
            assertNull(started.nextStartedActivity, link)
        }

        platform.openUrl(" https://www.lekia.se/lego ")
        val view = assertNotNull(started.nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("https://www.lekia.se/lego", view.dataString)
        assertTrue(view.hasCategory(Intent.CATEGORY_BROWSABLE))
    }
}
