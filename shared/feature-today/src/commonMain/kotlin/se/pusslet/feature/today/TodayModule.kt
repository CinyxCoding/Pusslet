package se.pusslet.feature.today

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val todayModule: Module = module {
    viewModel { TodayLayoutViewModel(get(), get(), get()) }
    viewModel { TodayViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
