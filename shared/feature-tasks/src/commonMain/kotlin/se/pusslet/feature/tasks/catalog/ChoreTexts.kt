package se.pusslet.feature.tasks.catalog

import org.jetbrains.compose.resources.StringResource
import se.pusslet.core.tasks.CatalogChore
import se.pusslet.core.tasks.ChoreGroup
import se.pusslet.core.tasks.TaskRepeat
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.chore_change_sheets
import se.pusslet.ui.resources.chore_change_towels
import se.pusslet.ui.resources.chore_check_spare_clothes
import se.pusslet.ui.resources.chore_clean_dishwasher
import se.pusslet.ui.resources.chore_clean_floor_drain
import se.pusslet.ui.resources.chore_clean_fridge
import se.pusslet.ui.resources.chore_clean_microwave
import se.pusslet.ui.resources.chore_clean_oven
import se.pusslet.ui.resources.chore_clean_shower
import se.pusslet.ui.resources.chore_clean_stove
import se.pusslet.ui.resources.chore_clean_toilet
import se.pusslet.ui.resources.chore_clean_washer
import se.pusslet.ui.resources.chore_descale_coffee_maker
import se.pusslet.ui.resources.chore_do_laundry
import se.pusslet.ui.resources.chore_dust
import se.pusslet.ui.resources.chore_empty_backpacks
import se.pusslet.ui.resources.chore_fold_laundry
import se.pusslet.ui.resources.chore_mop_floors
import se.pusslet.ui.resources.chore_mow_lawn
import se.pusslet.ui.resources.chore_pull_weeds
import se.pusslet.ui.resources.chore_recycle
import se.pusslet.ui.resources.chore_return_deposits
import se.pusslet.ui.resources.chore_sort_outgrown_clothes
import se.pusslet.ui.resources.chore_sweep_balcony
import se.pusslet.ui.resources.chore_take_out_trash
import se.pusslet.ui.resources.chore_vacuum
import se.pusslet.ui.resources.chore_wash_gym_clothes
import se.pusslet.ui.resources.chore_water_plants
import se.pusslet.ui.resources.chores_group_bathroom
import se.pusslet.ui.resources.chores_group_cleaning
import se.pusslet.ui.resources.chores_group_kids
import se.pusslet.ui.resources.chores_group_kitchen
import se.pusslet.ui.resources.chores_group_laundry
import se.pusslet.ui.resources.chores_group_outdoors
import se.pusslet.ui.resources.chores_group_recycling
import se.pusslet.ui.resources.repeat_biweekly
import se.pusslet.ui.resources.repeat_monthly
import se.pusslet.ui.resources.repeat_weekly

internal fun CatalogChore.titleRes(): StringResource = when (this) {
    CatalogChore.Vacuum -> Res.string.chore_vacuum
    CatalogChore.MopFloors -> Res.string.chore_mop_floors
    CatalogChore.Dust -> Res.string.chore_dust
    CatalogChore.ChangeSheets -> Res.string.chore_change_sheets
    CatalogChore.CleanStove -> Res.string.chore_clean_stove
    CatalogChore.CleanFridge -> Res.string.chore_clean_fridge
    CatalogChore.CleanOven -> Res.string.chore_clean_oven
    CatalogChore.CleanMicrowave -> Res.string.chore_clean_microwave
    CatalogChore.CleanDishwasher -> Res.string.chore_clean_dishwasher
    CatalogChore.DescaleCoffeeMaker -> Res.string.chore_descale_coffee_maker
    CatalogChore.CleanToilet -> Res.string.chore_clean_toilet
    CatalogChore.CleanShower -> Res.string.chore_clean_shower
    CatalogChore.ChangeTowels -> Res.string.chore_change_towels
    CatalogChore.CleanFloorDrain -> Res.string.chore_clean_floor_drain
    CatalogChore.DoLaundry -> Res.string.chore_do_laundry
    CatalogChore.FoldLaundry -> Res.string.chore_fold_laundry
    CatalogChore.CleanWasher -> Res.string.chore_clean_washer
    CatalogChore.TakeOutTrash -> Res.string.chore_take_out_trash
    CatalogChore.Recycle -> Res.string.chore_recycle
    CatalogChore.ReturnDeposits -> Res.string.chore_return_deposits
    CatalogChore.EmptyBackpacks -> Res.string.chore_empty_backpacks
    CatalogChore.WashGymClothes -> Res.string.chore_wash_gym_clothes
    CatalogChore.CheckSpareClothes -> Res.string.chore_check_spare_clothes
    CatalogChore.SortOutgrownClothes -> Res.string.chore_sort_outgrown_clothes
    CatalogChore.WaterPlants -> Res.string.chore_water_plants
    CatalogChore.MowLawn -> Res.string.chore_mow_lawn
    CatalogChore.PullWeeds -> Res.string.chore_pull_weeds
    CatalogChore.SweepBalcony -> Res.string.chore_sweep_balcony
}

internal fun ChoreGroup.titleRes(): StringResource = when (this) {
    ChoreGroup.Cleaning -> Res.string.chores_group_cleaning
    ChoreGroup.Kitchen -> Res.string.chores_group_kitchen
    ChoreGroup.Bathroom -> Res.string.chores_group_bathroom
    ChoreGroup.Laundry -> Res.string.chores_group_laundry
    ChoreGroup.Recycling -> Res.string.chores_group_recycling
    ChoreGroup.Kids -> Res.string.chores_group_kids
    ChoreGroup.Outdoors -> Res.string.chores_group_outdoors
}

/** "Varje vecka": the catalogue only has the weekly, every other week and monthly repeats. */
internal fun CatalogChore.repeatRes(): StringResource = when (repeat) {
    TaskRepeat.Biweekly -> Res.string.repeat_biweekly
    TaskRepeat.Monthly -> Res.string.repeat_monthly
    TaskRepeat.None, TaskRepeat.Daily, TaskRepeat.Weekly, TaskRepeat.Custom -> Res.string.repeat_weekly
}
