package se.pusslet.feature.meals

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val mealsModule: Module = module {
    viewModel { MealsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { RecipeLibraryViewModel(get(), get()) }
    viewModel { RecipeDetailsViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { RecipeEditorViewModel(get(), get()) }
    viewModel { RecipeImportViewModel(get(), get()) }
}
