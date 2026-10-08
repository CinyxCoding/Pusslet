package se.pusslet.android

import android.app.Application
import co.touchlab.kermit.Logger
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import se.pusslet.android.di.androidModule
import se.pusslet.core.AppConfig
import se.pusslet.data.AppCoordinator
import se.pusslet.data.di.dataModule
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
    }
}
