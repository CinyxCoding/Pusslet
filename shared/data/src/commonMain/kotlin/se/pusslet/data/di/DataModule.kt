package se.pusslet.data.di

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
import se.pusslet.data.notify.NotificationPlanSource
import se.pusslet.data.notify.PushMessages
import se.pusslet.data.notify.PushRegistration
import se.pusslet.data.premium.AllUnlocked
import se.pusslet.data.premium.Entitlements
import se.pusslet.data.repository.AttachmentHousekeeping
import se.pusslet.data.repository.AttachmentRepository
import se.pusslet.data.repository.BreakRepository
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.ExpenseRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.LunchRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.MedicineRepository
import se.pusslet.data.repository.PackingRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.repository.SickDayRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.repository.WishRepository
import se.pusslet.data.seed.DemoSeed
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.session.SessionRepository
import se.pusslet.data.sources.CachedFetcher
import se.pusslet.data.sources.DeviceCalendarExporter
import se.pusslet.data.sources.FoodDatabase
import se.pusslet.data.sources.LivsmedelsverketFoodDatabase
import se.pusslet.data.sources.LunchMenuProvider
import se.pusslet.data.sources.LunchMenus
import se.pusslet.data.sources.MatildaRssProvider
import se.pusslet.data.sources.OpenFoodFactsLookup
import se.pusslet.data.sources.ProductLookup
import se.pusslet.data.sources.SchoolRegistry
import se.pusslet.data.sources.SkolmatenRssProvider
import se.pusslet.data.sources.SkolverketSchoolRegistry
import se.pusslet.data.sources.SmhiWeatherSource
import se.pusslet.data.sources.SubscriptionFeeds
import se.pusslet.data.sources.SubscriptionRefresher
import se.pusslet.data.sources.SubscriptionSync
import se.pusslet.data.sources.WeatherSource
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.RoomSyncLocalStore
import se.pusslet.data.sync.SyncEngine
import se.pusslet.data.sync.SyncLocalStore
import se.pusslet.data.usecase.AcceptedRequestApplier
import se.pusslet.data.usecase.AllowanceRepository
import se.pusslet.data.usecase.CalendarFeedLink
import se.pusslet.data.usecase.DataPrivacy
import se.pusslet.data.usecase.FamilySearch
import se.pusslet.data.usecase.FirstTimeHints
import se.pusslet.data.usecase.HandoffAssigner
import se.pusslet.data.usecase.HandoffRequests
import se.pusslet.data.usecase.KidChoreActions
import se.pusslet.data.usecase.MealShopping
import se.pusslet.data.usecase.OtherHomeSharing
import se.pusslet.data.usecase.PickupReceipts
import se.pusslet.data.usecase.QuickAddExecutor
import se.pusslet.data.usecase.RecipeImporter
import se.pusslet.data.usecase.RecipeNutrition
import se.pusslet.data.usecase.ShoppingAdder
import se.pusslet.data.usecase.TodayInputSource
import se.pusslet.data.widget.WidgetSource
import kotlin.time.Clock

val AppScope = named("appScope")

/**
 * Platform-independent bindings. The platform module must provide: [PussletDatabase], HttpClient, RemoteStore,
 * FamilyRemote, AuthRepository, DeviceCalendarSource and BackendConfig.
 */
val dataModule: Module = module {
    single(AppScope) { backgroundScope() }
    single<Clock> { Clock.System }
    single<TimeZone> { TimeZone.currentSystemDefault() }
    single<IdGenerator> { RandomIdGenerator }
    // Nothing is sold yet: every build unlocks everything (BRIEF §7, docs/MONETIZATION.md).
    single<Entitlements> { AllUnlocked }

    single { get<PussletDatabase>().preferenceDao() }
    single { get<PussletDatabase>().cacheDao() }
    single { get<PussletDatabase>().cacheKeyDao() }
    single { get<PussletDatabase>().foodDao() }
    single { get<PussletDatabase>().purchaseLogDao() }

    single { SessionRepository(get()) } bind SessionProvider::class
    single<SyncLocalStore> { RoomSyncLocalStore(get()) }
    single { DocumentStore(get(), get(), get()) }
    single { SyncEngine(get(), get(), get()) }
    single { PreferencesRepository(get()) }

    single { FamilyRepository(get(), get(), get(), get(), get(), get()) }
    single { CalendarRepository(get(), get(), get(), get(), get(), cacheKeys = get()) }
    single { PackingRepository(get()) }
    single { MealRepository(get()) }
    single { RecipeRepository(get()) }
    single { ShoppingRepository(get(), get(), get(), get()) }
    single { TaskRepository(get()) }
    single { SickDayRepository(get(), get()) }
    single { MedicineRepository(get(), get(), get(), get()) }
    single { BreakRepository(get(), get(), get(), get()) }
    single { FamilySearch(get(), get(), get(), get(), get(), get(), get()) }
    single { WishRepository(get(), get(), get(), get()) }
    single { ExpenseRepository(get(), get(), get()) }
    single { KidChoreActions(get(), get()) }
    single { AllowanceRepository(get(), get()) }
    single { PickupReceipts(get(), get(), get()) }
    single { LunchRepository(get(), get(), get()) }
    single { HandoffAssigner(get()) }
    single { HandoffRequests(get(), get(), get(), get()) }
    single { AcceptedRequestApplier(get(), get(), get(), get(), get()) }
    single { FirstTimeHints(get()) }
    // Idag's data for a worker, and the local notification plan built from it (WorkManager lives in composeApp, ADR-002).
    single { TodayInputSource(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { NotificationPlanSource(get(), get(), get(), get(), get()) }
    // Push from other phones as notifications on this one (ADR-042); FCM lives in composeApp.
    single { PushMessages(get(), get(), get(), get(), get(), get()) }
    single { PushRegistration(get(), get(), get(), get()) }
    // The home-screen widgets' data (ADR-039); Glance lives in composeApp.
    single { WidgetSource(get(), get(), get(), get(), get(), get(), get()) }
    single { DataPrivacy(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), attachments = get()) }
    single { AttachmentRepository(get(), get(), get(), get()) }
    single { AttachmentHousekeeping(get(), get(), get(), get()) }
    // The family's own calendar feed link (ADR-041).
    single { CalendarFeedLink(get(), get(), get(), get()) }
    single { QuickAddExecutor(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { RecipeImporter(get(), get()) }
    single { MealShopping(get(), get(), get(), get(), get(), get(), get()) }
    single { RecipeNutrition(get(), get()) }
    single { ShoppingAdder(get(), get(), get(), get(), get(), get()) }

    single { CachedFetcher(get(), get(), get()) }
    single<WeatherSource> { SmhiWeatherSource(get(), get()) }
    single<SchoolRegistry> { SkolverketSchoolRegistry(get()) }
    single<LunchMenuProvider> { LunchMenus(listOf(SkolmatenRssProvider(get(), get()), MatildaRssProvider(get()))) }
    single<FoodDatabase> { LivsmedelsverketFoodDatabase(get(), get(), get()) }
    single<ProductLookup> { OpenFoodFactsLookup(get()) }
    single<CalendarFeedParser> { IcsParser() }
    single { SubscriptionSync(get(), get(), get(), get(), get(), get()) }
    // Keeps subscriptions fresh while the app runs (started by AppCoordinator); a WorkManager worker can call refreshAll().
    single { SubscriptionRefresher(get(), get(), get(), get()) } bind SubscriptionFeeds::class
    // Keeps family events in the phone calendar chosen in Inställningar (ADR-040), also started by AppCoordinator.
    single { DeviceCalendarExporter(get(), get(), get(), get()) }
    single { SwedishQuickAddParser() }

    single { DemoSeed(get(), get(), get()) }
    single { OtherHomeSharing(get(), get(), get(), get(), get(), get(), get()) }
    single {
        AppCoordinator(
            get(AppScope),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            processWork = listOf(get<SubscriptionRefresher>(), get<DeviceCalendarExporter>()),
        )
    }
}
