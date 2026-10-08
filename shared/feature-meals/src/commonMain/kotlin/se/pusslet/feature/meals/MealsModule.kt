package se.pusslet.feature.meals

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import se.pusslet.feature.meals.recipes.RecipeEditorViewModel
import se.pusslet.feature.meals.recipes.RecipeLibraryViewModel
import se.pusslet.feature.meals.recipes.RecipeViewModel

val mealsModule: Module = module {
    viewModel { MealsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { RecipeLibraryViewModel(get(), get()) }
    viewModel { RecipeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { RecipeEditorViewModel(get(), get(), get()) }
}
