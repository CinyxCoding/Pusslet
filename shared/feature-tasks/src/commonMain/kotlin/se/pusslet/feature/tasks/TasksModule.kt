package se.pusslet.feature.tasks

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val tasksModule: Module = module {
    viewModel { TasksViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { TaskEditorViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { WorkloadViewModel(get(), get(), get(), get(), get()) }
}
