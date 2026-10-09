package se.pusslet.feature.tasks

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import se.pusslet.feature.tasks.kid.AllowanceViewModel
import se.pusslet.feature.tasks.kid.KidModeViewModel
import se.pusslet.feature.tasks.kid.KidPickerViewModel

val tasksModule: Module = module {
    viewModel { TasksViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { TaskEditorViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { WorkloadViewModel(get(), get(), get(), get(), get()) }
    viewModel { KidModeViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { KidPickerViewModel(get()) }
    viewModel { AllowanceViewModel(get(), get(), get(), get(), get()) }
}
