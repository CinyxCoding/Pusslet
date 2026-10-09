package se.pusslet.android

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds prove themselves with a debug token: the first start prints it to logcat
 * (`DebugAppCheckProvider`), and the owner adds it under App Check → Manage debug tokens (docs/SETUP.md 2.2).
 */
internal object AppCheckProviders {
    fun factory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()
}
