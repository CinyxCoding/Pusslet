package se.pusslet.android

import android.app.Application
import co.touchlab.kermit.Logger
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import se.pusslet.core.AppConfig
import se.pusslet.data.db.createDatabase
import se.pusslet.data.di.dataModule
import se.pusslet.data.net.createHttpClient

class PussletApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.setTag(AppConfig.APP_NAME)
        startKoin {
            androidContext(this@PussletApplication)
            modules(
                module {
                    single { createDatabase(get()) }
                    single { createHttpClient(AppConfig.userAgent(BuildConfig.VERSION_NAME)) }
                },
                dataModule,
            )
        }
    }
}
