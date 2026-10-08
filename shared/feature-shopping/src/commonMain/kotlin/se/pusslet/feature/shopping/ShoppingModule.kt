package se.pusslet.feature.shopping

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val shoppingModule: Module = module {
    viewModel { ShoppingViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { PantryViewModel(get(), get(), get()) }
    viewModel { StoresViewModel(get(), get(), get()) }
}
