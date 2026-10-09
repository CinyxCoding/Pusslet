package se.pusslet.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.koin.android.ext.android.inject
import se.pusslet.android.attachments.AndroidAttachmentFiles
import se.pusslet.android.notify.Notifications
import se.pusslet.android.platform.AndroidPlatformActions
import se.pusslet.android.widget.WidgetIntents
import se.pusslet.core.util.InviteCodes
import se.pusslet.data.usecase.RecipeImporter
import se.pusslet.shell.IncomingIntent
import se.pusslet.shell.PussletApp
import se.pusslet.shell.navigation.TopLevelTab
import se.pusslet.ui.platform.LocalPlatformActions

/**
 * Single activity. Edge-to-edge is mandatory on Android 16; insets are handled by the Compose Scaffolds.
 * Predictive back is enabled in the manifest and handled by Navigation 3 (no onBackPressed overrides).
 */
class MainActivity : AppCompatActivity() {
    private var incoming by mutableStateOf<IncomingIntent?>(null)
    private val attachments: AndroidAttachmentFiles by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val platform = AndroidPlatformActions(this, attachments)
        if (savedInstanceState == null) receive(intent)
        setContent {
            CompositionLocalProvider(LocalPlatformActions provides platform) {
                PussletApp(incoming = incoming, onIncomingHandled = { incoming = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent?) {
        val image = sharedImage(intent)
        if (image == null) {
            parse(intent)?.let { incoming = it }
        } else {
            // Read while the sharing app's permission lasts; the copy is ours from then on.
            lifecycleScope.launch { incoming = IncomingIntent.SharedImage(attachments.import(image)) }
        }
    }

    private fun sharedImage(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return null
        return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    }

    private fun parse(intent: Intent?): IncomingIntent? = openEvent(intent) ?: openTab(intent) ?: when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data?.let { uri ->
            if (uri.scheme.equals("webcal", ignoreCase = true) || uri.scheme.equals("webcals", ignoreCase = true)) {
                IncomingIntent.SharedLink(uri.toString())
            } else {
                joinCode(uri)?.let { IncomingIntent.JoinCode(it) }
            }
        }
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf {
            it.isNotBlank()
        }?.let(::sharedText)
        else -> null
    }

    /** From a notification: open that event occurrence. */
    private fun openEvent(intent: Intent?): IncomingIntent? {
        val id = intent?.getStringExtra(Notifications.EXTRA_EVENT_ID) ?: return null
        val date = intent.getStringExtra(Notifications.EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return IncomingIntent.OpenEvent(id, date)
    }

    /** From a widget: open its tab. */
    private fun openTab(intent: Intent?): IncomingIntent? = intent?.getStringExtra(WidgetIntents.EXTRA_TAB)?.let { name ->
        TopLevelTab.entries.firstOrNull { it.name == name }?.let { IncomingIntent.OpenTab(it) }
    }

    /** A shared page ("Krämig kycklinggryta https://…") goes to recipe import; anything else to quick add. */
    private fun sharedText(text: String): IncomingIntent {
        val url = RecipeImporter.findUrl(text)
        return if (url != null && text.length - url.length <= MAX_LINK_CAPTION) {
            IncomingIntent.SharedLink(url)
        } else {
            IncomingIntent.SharedText(text.take(MAX_SHARED_TEXT))
        }
    }

    /** https://<linkHost>/join/<code> or pusslet://join/<code>. */
    private fun joinCode(uri: Uri): String? {
        val raw = when (uri.scheme) {
            "https" -> uri.pathSegments.takeIf { it.size >= 2 && it[0] == "join" }?.get(1)
            "pusslet" -> uri.pathSegments.firstOrNull() ?: uri.lastPathSegment
            else -> null
        } ?: return null
        return InviteCodes.normalize(raw).takeIf(InviteCodes::isValid)
    }

    private companion object {
        /** Quick add is for short messages; anything longer is cut rather than freezing the parser. */
        const val MAX_SHARED_TEXT = 2_000
        const val MAX_LINK_CAPTION = 120
    }
}
