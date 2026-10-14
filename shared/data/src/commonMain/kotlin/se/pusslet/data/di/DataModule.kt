package se.pusslet.data.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.TimeZone
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import se.pusslet.core.ics.CalendarFeedParser
import se.pusslet.core.ics.IcsParser
import se.pusslet.core.quickadd.SwedishQuickAddParser
import se.pusslet.core.util.IdGenerator
import se.pusslet.core.util.RandomIdGenerator
import se.pusslet.data.AppCoordinator
import se.pusslet.data.db.PussletDatabase
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.LunchRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.PackingRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.seed.DemoSeed
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.session.SessionRepository
import se.pusslet.data.sources.CachedFetcher
import se.pusslet.data.sources.FoodDatabase
import se.pusslet.data.sources.LivsmedelsverketFoodDatabase
import se.pusslet.data.sources.LunchMenuProvider
import se.pusslet.data.sources.OpenFoodFactsLookup
import se.pusslet.data.sources.ProductLookup
import se.pusslet.data.sources.SchoolRegistry
import se.pusslet.data.sources.SkolmatenRssProvider
import se.pusslet.data.sources.SkolverketSchoolRegistry
import se.pusslet.data.sources.SmhiWeatherSource
import se.pusslet.data.sources.SubscriptionSync
import se.pusslet.data.sources.WeatherSource
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.RoomSyncLocalStore
import se.pusslet.data.sync.SyncEngine
import se.pusslet.data.sync.SyncLocalStore
import se.pusslet.data.usecase.AcceptedRequestApplier
import se.pusslet.data.usecase.DataPrivacy
import se.pusslet.data.usecase.HandoffAssigner
import se.pusslet.data.usecase.HandoffRequests
import se.pusslet.data.usecase.MealShopping
import se.pusslet.data.usecase.QuickAddExecutor
import se.pusslet.data.usecase.RecipeImporter
import se.pusslet.data.usecase.RecipeNutrition
import se.pusslet.data.usecase.ShoppingAdder
import kotlin.time.Clock

val AppScope = named("appScope")

/**
 * Platform-independent bindings. The platform module must provide: [PussletDatabase], HttpClient, RemoteStore,
 * FamilyRemote, AuthRepository, DeviceCalendarSource and BackendConfig.
 */
val dataModule: Module = module {
    single(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<Clock> { Clock.System }
    single<TimeZone> { TimeZone.currentSystemDefault() }
    single<IdGenerator> { RandomIdGenerator }

    single { get<PussletDatabase>().preferenceDao() }
    single { get<PussletDatabase>().cacheDao() }
    single { get<PussletDatabase>().foodDao() }
    single { get<PussletDatabase>().purchaseLogDao() }

    single { SessionRepository(get()) } bind SessionProvider::class
    single<SyncLocalStore> { RoomSyncLocalStore(get()) }
    single { DocumentStore(get(), get(), get()) }
    single { SyncEngine(get(), get(), get()) }
    single { PreferencesRepository(get()) }

    single { FamilyRepository(get(), get(), get(), get(), get(), get()) }
    single { CalendarRepository(get(), get(), get(), get(), get()) }
    single { PackingRepository(get()) }
    single { MealRepository(get()) }
    single { RecipeRepository(get()) }
    single { ShoppingRepository(get(), get(), get(), get()) }
    single { TaskRepository(get()) }
    single { LunchRepository(get(), get(), get()) }
    single { HandoffAssigner(get()) }
    single { HandoffRequests(get(), get(), get(), get()) }
    single { AcceptedRequestApplier(get(), get(), get(), get(), get()) }
    single { DataPrivacy(get(), get(), get(), get()) }
    single { QuickAddExecutor(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { RecipeImporter(get(), get()) }
    single { MealShopping(get(), get(), get(), get(), get(), get(), get()) }
    single { RecipeNutrition(get(), get()) }
    single { ShoppingAdder(get(), get(), get(), get(), get(), get()) }

    single { CachedFetcher(get(), get(), get()) }
    single<WeatherSource> { SmhiWeatherSource(get(), get()) }
    single<SchoolRegistry> { SkolverketSchoolRegistry(get()) }
    single<LunchMenuProvider> { SkolmatenRssProvider(get(), get()) }
    single<FoodDatabase> { LivsmedelsverketFoodDatabase(get(), get(), get()) }
    single<ProductLookup> { OpenFoodFactsLookup(get()) }
    single<CalendarFeedParser> { IcsParser() }
    single { SubscriptionSync(get(), get(), get()) }
    single { SwedishQuickAddParser() }

    single { DemoSeed(get(), get(), get()) }
    single { AppCoordinator(get(AppScope), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
