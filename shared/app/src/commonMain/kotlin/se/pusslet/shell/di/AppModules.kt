package se.pusslet.shell.di

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import se.pusslet.data.AppCoordinator
import se.pusslet.feature.calendar.calendarModule
import se.pusslet.feature.family.familyModule
import se.pusslet.feature.meals.mealsModule
import se.pusslet.feature.settings.settingsModule
import se.pusslet.feature.shopping.shoppingModule
import se.pusslet.feature.today.todayModule
import se.pusslet.shell.ShellViewModel
import se.pusslet.shell.quickadd.QuickAddViewModel

val shellModule: Module = module {
    viewModel { ShellViewModel(get<AppCoordinator>(), get(), get()) }
    viewModel { QuickAddViewModel(get(), get(), get(), get(), get(), get(), get()) }
}

/** Every UI module; platform code adds its own module plus `dataModule`. */
val appModules: List<Module> = listOf(
    shellModule,
    todayModule,
    calendarModule,
    familyModule,
    settingsModule,
    shoppingModule,
    mealsModule,
)
