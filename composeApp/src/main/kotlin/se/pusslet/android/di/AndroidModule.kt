package se.pusslet.android.di

import com.google.firebase.firestore.FirebaseFirestore
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import se.pusslet.android.calendar.AndroidDeviceCalendarSource
import se.pusslet.core.AppConfig
import se.pusslet.data.BackendConfig
import se.pusslet.data.BackendMode
import se.pusslet.data.db.createDatabase
import se.pusslet.data.di.AppScope
import se.pusslet.data.net.createHttpClient
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.DeletionCalls
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.remote.FirestoreFamilyRemote
import se.pusslet.data.remote.FirestoreRemoteStore
import se.pusslet.data.remote.GitLiveAuthRepository
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.DeviceCalendarSource
import se.pusslet.data.sync.OfflineRemoteStore
import se.pusslet.data.sync.RemoteStore

/** Android implementations of the platform interfaces the shared code needs. */
fun androidModule(config: BackendConfig): Module = module {
    single { config }
    single { createDatabase(androidContext()) }
    single { createHttpClient(AppConfig.userAgent(config.versionName)) }
    single<DeviceCalendarSource> { AndroidDeviceCalendarSource(androidContext(), get()) }

    if (config.mode == BackendMode.LocalOnly) {
        single<RemoteStore> { OfflineRemoteStore }
        single<FamilyRemote> { LocalOnlyFamilyRemote }
        single<AuthRepository> { LocalOnlyAuthRepository() }
    } else {
        single<RemoteStore> { FirestoreRemoteStore(FirebaseFirestore.getInstance()) }
        single<FamilyRemote> {
            // The demo flavor's callables (deleteFamily, deleteAccount) run in the local functions emulator.
            val calls = DeletionCalls(config.emulatorHost.takeIf { config.mode == BackendMode.Emulator })
            FirestoreFamilyRemote(FirebaseFirestore.getInstance(), calls)
        }
        single<AuthRepository> { GitLiveAuthRepository(get(AppScope)) }
    }
}
