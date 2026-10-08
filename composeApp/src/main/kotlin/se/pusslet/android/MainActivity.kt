package se.pusslet.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import se.pusslet.shell.PussletApp

/**
 * Single activity. Edge-to-edge is mandatory on Android 16; insets are handled by the Compose Scaffold.
 * Predictive back is enabled in the manifest and handled by Navigation 3 (no onBackPressed overrides).
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PussletApp()
        }
    }
}
