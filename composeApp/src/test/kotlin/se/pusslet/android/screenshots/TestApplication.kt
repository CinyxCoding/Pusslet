package se.pusslet.android.screenshots

import android.app.Application
import android.content.ContentProvider
import android.content.pm.ProviderInfo

/**
 * Plain application for Robolectric: screenshot tests provide their own (fake) dependencies.
 *
 * Robolectric does not start manifest ContentProviders, but Compose Multiplatform resources rely on one
 * (`AndroidContextProvider`) to learn the application context. Start it by hand so tests read strings exactly like the app.
 */
class TestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val provider = Class.forName("org.jetbrains.compose.resources.AndroidContextProvider")
            .getDeclaredConstructor()
            .newInstance() as ContentProvider
        provider.attachInfo(this, ProviderInfo().apply { authority = "$packageName.resources.AndroidContextProvider" })
    }
}
