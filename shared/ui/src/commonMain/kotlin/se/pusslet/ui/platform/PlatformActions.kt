package se.pusslet.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf

enum class AppPermission { Notifications, Calendar, Camera, CoarseLocation }

data class CoarseLocation(val latitude: Double, val longitude: Double)

/**
 * Things only the platform can do. Android implements this in MainActivity; previews and tests use [NoopPlatformActions].
 * Permissions are always requested in context, from the feature that needs them.
 */
interface PlatformActions {
    fun shareText(text: String, subject: String? = null)

    fun openUrl(url: String)

    /** Opens the camera scanner (QR codes and EAN barcodes). Null result = cancelled. */
    fun scanCode(onResult: (String?) -> Unit)

    fun hasPermission(permission: AppPermission): Boolean

    fun requestPermission(permission: AppPermission, onResult: (Boolean) -> Unit)

    /** Per-app language: "sv", "en" or "" for the system language. */
    fun setLanguage(tag: String)

    fun currentLanguage(): String

    /** Lets the user pick where to save a file (export of all data). */
    fun saveFile(fileName: String, mimeType: String, content: String, onDone: (Boolean) -> Unit)

    fun keepScreenOn(on: Boolean)

    /** Coarse position for the weather, if permission is granted. */
    fun coarseLocation(onResult: (CoarseLocation?) -> Unit)

    /** True when Google sign-in is configured for this build (needs the owner's Firebase web client id). */
    val googleSignInAvailable: Boolean

    /** Google sign-in via Credential Manager; returns an ID token or null. */
    fun googleSignIn(onResult: (String?) -> Unit)
}

object NoopPlatformActions : PlatformActions {
    override fun shareText(text: String, subject: String?) = Unit

    override fun openUrl(url: String) = Unit

    override fun scanCode(onResult: (String?) -> Unit) = onResult(null)

    override fun hasPermission(permission: AppPermission) = false

    override fun requestPermission(permission: AppPermission, onResult: (Boolean) -> Unit) = onResult(false)

    override fun setLanguage(tag: String) = Unit

    override fun currentLanguage() = ""

    override fun saveFile(fileName: String, mimeType: String, content: String, onDone: (Boolean) -> Unit) = onDone(false)

    override fun keepScreenOn(on: Boolean) = Unit

    override fun coarseLocation(onResult: (CoarseLocation?) -> Unit) = onResult(null)

    override val googleSignInAvailable = false

    override fun googleSignIn(onResult: (String?) -> Unit) = onResult(null)
}

val LocalPlatformActions = staticCompositionLocalOf<PlatformActions> { NoopPlatformActions }
