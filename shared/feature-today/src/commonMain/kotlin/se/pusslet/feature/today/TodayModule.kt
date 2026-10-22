package se.pusslet.feature.today

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import se.pusslet.data.sync.SyncEngine
import se.pusslet.feature.today.helper.HelperPreviewViewModel
import se.pusslet.feature.today.helper.HelperViewModel

val todayModule: Module = module {
    viewModel { TodayLayoutViewModel(get(), get(), get()) }
    viewModel {
        TodayViewModel(
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
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
        )
    }
    viewModel {
        HelperViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get<SyncEngine>().status, get(), get())
    }
    viewModel { HelperPreviewViewModel(get(), get(), get(), get(), get(), get(), get()) }
}
