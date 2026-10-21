package se.pusslet.android

import android.content.Context
import co.touchlab.kermit.Logger
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import se.pusslet.data.BackendConfig
import se.pusslet.data.BackendMode

/**
 * Initialises Firebase from BuildConfig (filled from google-services.json per flavor, or demo values) and points the
 * demo flavor at the Local Emulator Suite. Without configuration the app runs local-only.
 */
object FirebaseSetup {
    private val log = Logger.withTag("Firebase")

    fun initialize(context: Context): BackendConfig {
        val mode = when {
            BuildConfig.BACKEND == "emulator" -> BackendMode.Emulator
            BuildConfig.FIREBASE_PROJECT_ID.isBlank() || BuildConfig.FIREBASE_APP_ID.isBlank() -> BackendMode.LocalOnly
            else -> BackendMode.Firebase
        }
        val config = BackendConfig(
            mode,
            BuildConfig.SEED_DEMO_DATA,
            BuildConfig.VERSION_NAME,
            BuildConfig.EMULATOR_HOST,
            BuildConfig.LINK_HOST,
            developerTools = BuildConfig.DEVELOPER_TOOLS,
            calendarFeedBase = when (mode) {
                BackendMode.Emulator ->
                    "http://${BuildConfig.EMULATOR_HOST}:$FUNCTIONS_PORT/${BuildConfig.FIREBASE_PROJECT_ID}/$REGION/$FEED"
                BackendMode.Firebase -> "https://$REGION-${BuildConfig.FIREBASE_PROJECT_ID}.cloudfunctions.net/$FEED"
                BackendMode.LocalOnly -> null
            },
        )
        if (mode == BackendMode.LocalOnly) {
            log.i { "No Firebase configuration for this flavor; running local-only" }
            return config
        }
        if (FirebaseApp.getApps(context).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .apply { if (BuildConfig.FIREBASE_STORAGE_BUCKET.isNotBlank()) setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET) }
                .build()
            FirebaseApp.initializeApp(context, options)
        }
        val firestore = FirebaseFirestore.getInstance()
        // Room is the offline store (ADR-006); Firestore only needs an in-memory cache.
        firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
        if (mode == BackendMode.Emulator) {
            firestore.useEmulator(config.emulatorHost, FIRESTORE_PORT)
            FirebaseAuth.getInstance().useEmulator(config.emulatorHost, AUTH_PORT)
        }
        return config
    }

    const val FIRESTORE_PORT = 8080
    const val AUTH_PORT = 9099
    private const val FUNCTIONS_PORT = 5001

    /** As in functions/src/setup.ts and calendarFeed.ts. */
    private const val REGION = "europe-north1"
    private const val FEED = "familyCalendarFeed"
}
