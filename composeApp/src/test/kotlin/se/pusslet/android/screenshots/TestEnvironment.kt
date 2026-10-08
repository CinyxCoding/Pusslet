package se.pusslet.android.screenshots

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.datetime.TimeZone
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import se.pusslet.data.AppCoordinator
import se.pusslet.data.BackendConfig
import se.pusslet.data.BackendMode
import se.pusslet.data.db.PussletDatabase
import se.pusslet.data.di.AppScope
import se.pusslet.data.di.dataModule
import se.pusslet.data.net.NetworkJson
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.DeviceCalendarSource
import se.pusslet.data.repository.NoDeviceCalendars
import se.pusslet.data.sync.OfflineRemoteStore
import se.pusslet.data.sync.RemoteStore
import se.pusslet.shell.di.appModules
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The real app graph (Room, repositories, view models) with a fixed clock, an in-memory database, no backend and a
 * recorded SMHI forecast, so screenshots are reproducible. Seeds the demo family "Familjen Lind" when [seed] is true.
 */
object TestEnvironment {
    val zone: TimeZone = TimeZone.of("Europe/Stockholm")

    /** Thursday 8 October 2026, 07:10 in Stockholm (week 41) – matches the recorded forecast. */
    val now: Instant = Instant.parse("2026-10-08T05:10:00Z")

    private object FixedClock : Clock {
        override fun now(): Instant = now
    }

    fun start(context: Context, seed: Boolean = true) {
        if (GlobalContext.getOrNull() != null) stop()
        val config = BackendConfig(BackendMode.LocalOnly, seedDemoData = seed, versionName = "test")
        val platform = module {
            single { config }
            single {
                Room.inMemoryDatabaseBuilder<PussletDatabase>(context)
                    .setDriver(AndroidSQLiteDriver())
                    .setQueryCoroutineContext(Dispatchers.IO)
                    .build()
            }
            single { httpClient() }
            single<DeviceCalendarSource> { NoDeviceCalendars }
            single<RemoteStore> { OfflineRemoteStore }
            single<FamilyRemote> { LocalOnlyFamilyRemote }
            single<AuthRepository> { LocalOnlyAuthRepository() }
        }
        val overrides = module {
            single<Clock> { FixedClock }
            single<TimeZone> { zone }
        }
        val koin = startKoin {
            androidContext(context)
            allowOverride(true)
            modules(listOf(platform, dataModule, overrides) + appModules)
        }.koin
        koin.get<AppCoordinator>().start()
    }

    fun stop() {
        GlobalContext.getOrNull()?.let { koin ->
            koin.get<CoroutineScope>(AppScope).cancel()
            koin.get<PussletDatabase>().close()
        }
        stopKoin()
    }

    private fun httpClient(): HttpClient = HttpClient(
        MockEngine { request ->
            val url = request.url.toString()
            if ("smhi.se" in url && "/point/" in url) {
                respond(fixture("smhi_snow1g_stockholm.json"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        },
    ) {
        expectSuccess = false
        install(ContentNegotiation) { json(NetworkJson) }
    }

    private fun fixture(name: String): String = File("../shared/data/src/commonTest/resources/fixtures/$name").readText()
}
