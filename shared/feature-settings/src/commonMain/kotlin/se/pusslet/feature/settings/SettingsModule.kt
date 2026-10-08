package se.pusslet.feature.settings

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val settingsModule: Module = module {
    viewModel { SettingsViewModel(get(), get(), get(), get(), get()) }
    viewModel { AccountViewModel(get(), get()) }
    viewModel { AboutDataViewModel(get()) }
    viewModel { PrivacyViewModel(get()) }
}
