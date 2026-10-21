package se.pusslet.feature.tasks

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import se.pusslet.feature.tasks.distribution.DistributionViewModel
import se.pusslet.feature.tasks.editor.TaskEditorViewModel

val tasksModule: Module = module {
    single { DeletedTasks() }
    viewModel { TasksViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { TaskEditorViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { DistributionViewModel(get(), get(), get(), get(), get(), get()) }
}
