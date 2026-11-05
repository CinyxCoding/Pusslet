package se.pusslet.core.tasks

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import se.pusslet.core.model.ChoreRotation
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.TaskCategory
import se.pusslet.core.model.TaskKind
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import kotlin.time.Instant

/** The groups of "Vanliga sysslor" (ADR-065), in the order they are shown. */
enum class ChoreGroup(val category: TaskCategory = TaskCategory.Household) {
    Cleaning,
    Kitchen,
    Bathroom,
    Laundry,
    Recycling,
    Kids(TaskCategory.Kids),
    Outdoors,
}

/**
 * The chores a family can pick instead of typing them (ADR-065). The names are string resources: a picked chore stores its
 * name as written, like any task, so a later change in this list never touches what a family already has.
 */
enum class CatalogChore(val group: ChoreGroup, val repeat: TaskRepeat) {
    Vacuum(ChoreGroup.Cleaning, TaskRepeat.Weekly),
    MopFloors(ChoreGroup.Cleaning, TaskRepeat.Biweekly),
    Dust(ChoreGroup.Cleaning, TaskRepeat.Biweekly),
    ChangeSheets(ChoreGroup.Cleaning, TaskRepeat.Biweekly),

    CleanStove(ChoreGroup.Kitchen, TaskRepeat.Weekly),
    CleanFridge(ChoreGroup.Kitchen, TaskRepeat.Monthly),
    CleanOven(ChoreGroup.Kitchen, TaskRepeat.Monthly),
    CleanMicrowave(ChoreGroup.Kitchen, TaskRepeat.Monthly),
    CleanDishwasher(ChoreGroup.Kitchen, TaskRepeat.Monthly),
    DescaleCoffeeMaker(ChoreGroup.Kitchen, TaskRepeat.Monthly),

    CleanToilet(ChoreGroup.Bathroom, TaskRepeat.Weekly),
    CleanShower(ChoreGroup.Bathroom, TaskRepeat.Weekly),
    ChangeTowels(ChoreGroup.Bathroom, TaskRepeat.Weekly),
    CleanFloorDrain(ChoreGroup.Bathroom, TaskRepeat.Monthly),

    DoLaundry(ChoreGroup.Laundry, TaskRepeat.Weekly),
    FoldLaundry(ChoreGroup.Laundry, TaskRepeat.Weekly),
    CleanWasher(ChoreGroup.Laundry, TaskRepeat.Monthly),

    TakeOutTrash(ChoreGroup.Recycling, TaskRepeat.Weekly),
    Recycle(ChoreGroup.Recycling, TaskRepeat.Biweekly),
    ReturnDeposits(ChoreGroup.Recycling, TaskRepeat.Monthly),

    EmptyBackpacks(ChoreGroup.Kids, TaskRepeat.Weekly),
    WashGymClothes(ChoreGroup.Kids, TaskRepeat.Weekly),
    CheckSpareClothes(ChoreGroup.Kids, TaskRepeat.Monthly),
    SortOutgrownClothes(ChoreGroup.Kids, TaskRepeat.Monthly),

    WaterPlants(ChoreGroup.Outdoors, TaskRepeat.Weekly),
    MowLawn(ChoreGroup.Outdoors, TaskRepeat.Weekly),
    PullWeeds(ChoreGroup.Outdoors, TaskRepeat.Biweekly),
    SweepBalcony(ChoreGroup.Outdoors, TaskRepeat.Monthly),
}

/** Who takes the chores picked in "Vanliga sysslor". */
sealed interface ChoreOwner {
    data object Nobody : ChoreOwner

    data class Person(val memberId: String) : ChoreOwner

    data class TakeTurns(val memberIds: List<String>) : ChoreOwner
}

/** One picked chore: its new task id and its name in the app's language. */
data class PickedChore(val id: String, val title: String, val chore: CatalogChore)

/** Turns picked chores into ordinary tasks, spread so that the first Saturday is not all of them at once. */
object ChoreCatalog {
    /** Every month has this day, so a monthly chore keeps its date. */
    private const val LAST_DAY_IN_EVERY_MONTH = 28
    private const val DAYS_PER_WEEK = 7

    fun tasks(
        picked: List<PickedChore>,
        owner: ChoreOwner,
        today: LocalDate,
        createdBy: String?,
        createdAt: Instant,
    ): List<FamilyTask> {
        val saturday = comingSaturday(today)
        val sameRepeat = mutableMapOf<TaskRepeat, Int>()
        return picked.mapIndexed { index, p ->
            val repeat = p.chore.repeat
            val nth = sameRepeat.getOrElse(repeat) { 0 }
            sameRepeat[repeat] = nth + 1
            val due = firstDay(saturday, repeat, nth)
            FamilyTask(
                id = p.id,
                title = p.title.trim(),
                kind = TaskKind.Chore,
                category = p.chore.group.category,
                assigneeId = (owner as? ChoreOwner.Person)?.memberId,
                due = due,
                rrule = TaskRepeats.ruleFor(repeat, due),
                rotation = (owner as? ChoreOwner.TakeTurns)?.let { rotation(it.memberIds, index, repeat, today) },
                createdBy = createdBy,
                createdAt = createdAt,
            )
        }
    }

    /** Today when it is a Saturday, so nothing starts out late. */
    fun comingSaturday(today: LocalDate): LocalDate =
        today.plusDays((DayOfWeek.SATURDAY.isoDayNumber - today.dayOfWeek.isoDayNumber + DAYS_PER_WEEK) % DAYS_PER_WEEK)

    /** Chores with the same repeat take the Saturdays of their cycle in turn: of four monthly ones, one each weekend. */
    private fun firstDay(saturday: LocalDate, repeat: TaskRepeat, nth: Int): LocalDate {
        val day = saturday.plusDays(DAYS_PER_WEEK * (nth % weeksPerRound(repeat)))
        return if (repeat == TaskRepeat.Monthly && day.day > LAST_DAY_IN_EVERY_MONTH) day.plusDays(DAYS_PER_WEEK) else day
    }

    /**
     * The turn changes with the chore's own repeat: a turn per week would give a chore done every other week to the same
     * person each time. Each chore starts one step further along, so one person does not get them all the same week.
     */
    private fun rotation(memberIds: List<String>, index: Int, repeat: TaskRepeat, today: LocalDate): ChoreRotation? {
        if (memberIds.isEmpty()) return null
        val start = index % memberIds.size
        return ChoreRotation(memberIds.drop(start) + memberIds.take(start), weeksPerRound(repeat), today.isoWeek)
    }

    private fun weeksPerRound(repeat: TaskRepeat): Int = when (repeat) {
        TaskRepeat.Biweekly -> 2
        TaskRepeat.Monthly -> 4
        TaskRepeat.None, TaskRepeat.Daily, TaskRepeat.Weekly, TaskRepeat.Custom -> 1
    }
}
