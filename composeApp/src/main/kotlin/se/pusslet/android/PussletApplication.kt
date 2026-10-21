package se.pusslet.android

import android.app.Application
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import se.pusslet.android.calendar.SubscriptionWork
import se.pusslet.android.di.androidModule
import se.pusslet.android.notify.NotificationWork
import se.pusslet.android.push.Push
import se.pusslet.android.widget.WidgetUpdates
import se.pusslet.core.AppConfig
import se.pusslet.data.AppCoordinator
import se.pusslet.data.di.AppScope
import se.pusslet.data.di.dataModule
import se.pusslet.data.usecase.AcceptedRequestApplier
import se.pusslet.shell.di.appModules

class PussletApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.setTag(AppConfig.APP_NAME)
        val backend = FirebaseSetup.initialize(this)
        startKoin {
            androidContext(this@PussletApplication)
            modules(listOf(androidModule(backend), dataModule) + appModules)
        }
        get<AppCoordinator>().start()
        // Parents' phones write caregivers' accepted answers into the events (ADR-030); a no-op on other phones.
        get<AcceptedRequestApplier>().start(get<CoroutineScope>(AppScope))
        // Local notifications (ADR-036): planned from Room, scheduled on WorkManager.
        NotificationWork.start(this, get(), get(), get<CoroutineScope>(AppScope))
        // Home-screen widgets (ADR-039): drawn again a moment after what they show changes.
        WidgetUpdates.start(this, get(), get<CoroutineScope>(AppScope))
        // ICS subscriptions also while the app is closed (every 6 hours with network).
        SubscriptionWork.start(this)
        // Push from other phones (ADR-042): requests, answers and items while shopping; only with a real Firebase project.
        Push.start(backend, get(), get<CoroutineScope>(AppScope))
    }
}
