package se.pusslet.feature.meals

import kotlinx.datetime.LocalDate
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealSlot
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import se.pusslet.data.repository.MealRepository

/** One meal a change to the plan touched: what it was ([before], null = nothing planned) and what the change made it ([after]). */
data class MealChange(val date: LocalDate, val slot: MealSlot, val before: MealEntry?, val after: MealEntry?)

/**
 * Everything one change to the meal plan touched (planning, removing, moving, a favourite week, "Kopiera förra veckan"), so
 * "Ångra" can put the earlier meals back. Leftovers that "Gör dubbelt" planned count as part of the change.
 */
data class PlanUndo(val meals: List<MealChange>)

/** Records what a change does to the meal plan and takes it back (UX plan, Mat 5: undo everywhere). */
internal class MealPlanHistory(private val meals: MealRepository) {
    /**
     * Runs [change] and returns what it did to the meals around [dates], or null when nothing changed. The two days after each
     * date are watched too: "Gör dubbelt" puts leftovers on the next day, and restoring one may plan them again.
     */
    suspend fun record(dates: Collection<LocalDate>, change: suspend () -> Unit): PlanUndo? {
        val weeks = dates.flatMapTo(mutableSetOf()) { date -> (0..2).map { date.plusDays(it).isoWeek } }
        val before = entriesOf(weeks)
        change()
        val after = entriesOf(weeks)
        val targets = LinkedHashMap<String, Pair<LocalDate, MealSlot>>()
        (before.values + after.values)
            .filter { before[it.key] != after[it.key] }
            .forEach { targets[it.key] = it.date to it.slot }
        if (targets.isEmpty()) return null
        // Putting back a "Gör dubbelt" dinner plans its leftovers again when the next day is empty, so that day is restored too.
        before.values.filter { it.key in targets && it.cookDouble }.forEach { e ->
            val next = e.date.plusDays(1)
            targets.getOrPut(MealEntry.key(next, MealSlot.Dinner)) { next to MealSlot.Dinner }
        }
        return PlanUndo(targets.map { (key, at) -> MealChange(at.first, at.second, before[key], after[key]) })
    }

    /**
     * Puts the earlier meals back: those planned first, then the days that were empty (a restored "Gör dubbelt" may just have
     * planned leftovers there). A meal someone changed again since is left alone.
     */
    suspend fun undo(undo: PlanUndo) {
        val weeks = undo.meals.mapTo(mutableSetOf()) { it.date.isoWeek }
        val now = entriesOf(weeks)
        val untouched = undo.meals.filter { now[MealEntry.key(it.date, it.slot)] == it.after }
        untouched.forEach { m -> if (m.before != null && m.before != m.after) meals.setEntry(m.before) }
        val planned = entriesOf(weeks)
        untouched
            .filter { it.before == null && MealEntry.key(it.date, it.slot) in planned }
            .forEach { meals.clearEntry(it.date, it.slot) }
    }

    private suspend fun entriesOf(weeks: Set<IsoWeek>): Map<String, MealEntry> =
        weeks.flatMap { meals.getWeek(it).entries.values }.associateBy { it.key }
}
