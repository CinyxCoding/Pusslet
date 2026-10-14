package se.pusslet.feature.shopping

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import se.pusslet.data.di.AppScope

val shoppingModule: Module = module {
    // One per app: store mode hands "Klar – töm vagnen" to the list underneath.
    single { ShoppingReceipts() }
    viewModel { ShoppingViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(AppScope), get()) }
    viewModel { StoresViewModel(get(), get(), get(), get()) }
    viewModel { PantryViewModel(get(), get(), get(), get()) }
}
