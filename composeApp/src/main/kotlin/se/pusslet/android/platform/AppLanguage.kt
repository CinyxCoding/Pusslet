package se.pusslet.android.platform

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Compose resources pick the language from `Locale.getDefault()` outside composition. An activity applies the per-app
 * language chosen in Inställningar; a worker or a widget may run without one (and the system may have reset the default to
 * its own language), so the default is set from the chosen language here. On Android 13+ it is read from [LocaleManager],
 * since `AppCompatDelegate` only knows it while one of the app's activities is alive; without a choice the system's applies.
 */
internal fun useAppLanguage(context: Context) {
    val chosen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        LocaleListCompat.wrap(context.getSystemService(LocaleManager::class.java).applicationLocales)
    } else {
        AppCompatDelegate.getApplicationLocales()
    }
    chosen[0]?.let(Locale::setDefault)
}
