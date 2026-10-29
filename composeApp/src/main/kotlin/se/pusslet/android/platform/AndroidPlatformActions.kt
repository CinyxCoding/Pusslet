package se.pusslet.android.platform

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch
import se.pusslet.android.BuildConfig
import se.pusslet.android.attachments.AndroidAttachmentFiles
import se.pusslet.android.scanner.ScannerActivity
import se.pusslet.ui.platform.AppPermission
import se.pusslet.ui.platform.CoarseLocation
import se.pusslet.ui.platform.PlatformActions
import java.io.IOException

/**
 * Android side of [PlatformActions]. Must be created in `onCreate` (before the activity is started) because it
 * registers activity-result launchers. Every permission is requested only when the user uses the feature.
 */
class AndroidPlatformActions(
    private val activity: ComponentActivity,
    private val attachments: AndroidAttachmentFiles,
) : PlatformActions {
    private val log = Logger.withTag("Platform")

    private var pendingPermission: ((Boolean) -> Unit)? = null
    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            pendingPermission?.invoke(result.isNotEmpty() && result.values.all { it })
            pendingPermission = null
        }

    private var pendingScan: ((String?) -> Unit)? = null
    private val scanLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingScan?.invoke(result.data?.getStringExtra(ScannerActivity.EXTRA_VALUE))
        pendingScan = null
    }

    private var pendingSave: Pair<String, (Boolean) -> Unit>? = null
    private val saveLauncher = activity.registerForActivityResult(ActivityResultContracts.CreateDocument(EXPORT_MIME)) { uri ->
        val (content, done) = pendingSave ?: return@registerForActivityResult
        pendingSave = null
        done(uri != null && write(uri, content))
    }

    override fun shareText(text: String, subject: String?) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        activity.startActivity(Intent.createChooser(send, subject))
    }

    override fun openUrl(url: String) {
        // A link can come from the family's shared data (a wish from the other home), so only web links, and only to an app
        // that opens links from a browser: never a file, our own content or another app's own scheme.
        val uri = Uri.parse(url.trim())
        if (uri.scheme?.lowercase() !in WEB_SCHEMES || uri.host.isNullOrBlank()) {
            log.w { "Not a web link; not opened" }
            return
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            log.w(e) { "No app can open links" }
        }
    }

    override fun dial(number: String) {
        // Only a plain number such as 1177: the phone app opens with it filled in, and the user decides to call.
        if (!PHONE_NUMBER.matches(number)) {
            log.w { "Not a phone number; not dialled" }
            return
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
        } catch (e: ActivityNotFoundException) {
            log.w(e) { "No phone app (a tablet without calls)" }
        }
    }

    override fun scanCode(onResult: (String?) -> Unit) {
        requestPermission(AppPermission.Camera) { granted ->
            if (!granted) {
                onResult(null)
            } else {
                pendingScan = onResult
                scanLauncher.launch(Intent(activity, ScannerActivity::class.java))
            }
        }
    }

    override fun hasPermission(permission: AppPermission): Boolean =
        manifestPermissions(permission).all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }

    override fun requestPermission(permission: AppPermission, onResult: (Boolean) -> Unit) {
        val needed = manifestPermissions(permission)
        if (needed.isEmpty() || hasPermission(permission)) {
            onResult(true)
            return
        }
        pendingPermission = onResult
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun manifestPermissions(permission: AppPermission): List<String> = when (permission) {
        AppPermission.Notifications -> if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }
        AppPermission.Calendar -> listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        AppPermission.Camera -> listOf(Manifest.permission.CAMERA)
        AppPermission.CoarseLocation -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    override fun setLanguage(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
        )
    }

    override fun currentLanguage(): String = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')

    override fun saveFile(fileName: String, mimeType: String, content: String, onDone: (Boolean) -> Unit) {
        pendingSave = content to onDone
        saveLauncher.launch(fileName)
    }

    private fun write(uri: Uri, content: String): Boolean = try {
        activity.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } != null
    } catch (e: IOException) {
        log.w(e) { "Export could not be written" }
        false
    } catch (e: SecurityException) {
        log.w(e) { "Export target not writable" }
        false
    }

    override fun keepScreenOn(on: Boolean) {
        if (on) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun coarseLocation(onResult: (CoarseLocation?) -> Unit) {
        val manager = activity.getSystemService(LocationManager::class.java)
        if (manager == null || !hasPermission(AppPermission.CoarseLocation) || !LocationManagerCompat.isLocationEnabled(manager)) {
            onResult(null)
            return
        }
        val provider = if (manager.isProviderEnabled(
                LocationManager.NETWORK_PROVIDER,
            )
        ) {
            LocationManager.NETWORK_PROVIDER
        } else {
            LocationManager.PASSIVE_PROVIDER
        }
        try {
            LocationManagerCompat.getCurrentLocation(
                manager,
                provider,
                CancellationSignal(),
                ContextCompat.getMainExecutor(activity),
            ) { location: Location? ->
                onResult(location?.let { CoarseLocation(it.latitude, it.longitude) })
            }
        } catch (e: SecurityException) {
            log.w(e) { "Location permission revoked" }
            onResult(null)
        }
    }

    override val googleSignInAvailable: Boolean = BuildConfig.FIREBASE_WEB_CLIENT_ID.isNotBlank()

    override fun googleSignIn(onResult: (String?) -> Unit) {
        if (!googleSignInAvailable) {
            onResult(null)
            return
        }
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.FIREBASE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        activity.lifecycleScope.launch {
            val token = try {
                val response = CredentialManager.create(activity).getCredential(activity, request)
                GoogleIdTokenCredential.createFrom(response.credential.data).idToken
            } catch (e: GetCredentialException) {
                log.i { "Google sign-in cancelled or unavailable (${e.type})" }
                null
            } catch (e: GoogleIdTokenParsingException) {
                log.w(e) { "Unexpected credential from Google sign-in" }
                null
            }
            onResult(token)
        }
    }

    override fun loadImage(fileName: String, thumbnail: Boolean, onResult: (ImageBitmap?) -> Unit) {
        activity.lifecycleScope.launch { onResult(attachments.load(fileName, thumbnail)?.asImageBitmap()) }
    }

    override fun openImage(fileName: String) {
        val uri = attachments.shareableUri(fileName) ?: return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(view)
        } catch (e: ActivityNotFoundException) {
            log.w { "No picture viewer: ${e::class.simpleName}" }
        }
    }

    private companion object {
        const val EXPORT_MIME = "application/json"
        val WEB_SCHEMES = setOf("https", "http")
        val PHONE_NUMBER = Regex("""\+?\d+""")
    }
}
