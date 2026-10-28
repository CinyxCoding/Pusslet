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
import org.koin.android.ext.android.inject
import se.pusslet.android.attachments.AndroidAttachmentFiles
import se.pusslet.android.notify.AndroidNotifier
import se.pusslet.android.platform.AndroidPlatformActions
import se.pusslet.core.notify.NotificationTarget
import se.pusslet.core.util.InviteCodes
import se.pusslet.shell.IncomingIntent
import se.pusslet.shell.PussletApp
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
            // Read while the sharing app's permission lasts; the copy is ours from then on (ADR-049).
            lifecycleScope.launch { incoming = IncomingIntent.SharedImage(attachments.import(image)) }
        }
    }

    private fun sharedImage(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return null
        return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    }

    private fun parse(intent: Intent?): IncomingIntent? = when (intent?.action) {
        AndroidNotifier.ACTION_OPEN -> NotificationTarget.decode(intent.getStringExtra(AndroidNotifier.EXTRA_TARGET))
            ?.let { IncomingIntent.Open(it) }
        Intent.ACTION_VIEW -> intent.data?.let(::joinCode)?.let { IncomingIntent.JoinCode(it) }
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf {
            it.isNotBlank()
        }?.let { IncomingIntent.SharedText(it.take(MAX_SHARED_TEXT)) }
        else -> null
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
    }
}
