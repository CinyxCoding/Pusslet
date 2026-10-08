package se.pusslet.android

import android.app.Application
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import se.pusslet.android.di.androidModule
import se.pusslet.android.notify.Notifications
import se.pusslet.android.notify.PushRegistration
import se.pusslet.android.work.BackgroundWork
import se.pusslet.core.AppConfig
import se.pusslet.data.AppCoordinator
import se.pusslet.data.di.AppScope
import se.pusslet.data.di.dataModule
import se.pusslet.data.usecase.DeviceCalendarExport
import se.pusslet.data.usecase.HandoffRequests
import se.pusslet.data.usecase.ReminderSync
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
        get<DeviceCalendarExport>().start(get(AppScope))
        get<ReminderSync>().start(get(AppScope))
        get<HandoffRequests>().watch(get(AppScope), get())
        get<PushRegistration>().start(get(AppScope))
        get<CoroutineScope>(AppScope).launch { Notifications.createChannels(this@PussletApplication) }
        BackgroundWork.schedule(this)
    }
}
