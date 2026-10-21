package se.pusslet.data.usecase

import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.Role
import se.pusslet.core.shopping.GeneratedLine
import se.pusslet.core.shopping.ShoppingListGenerator
import se.pusslet.core.shopping.ShoppingPlan
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.isoWeek
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.SessionProvider
import kotlin.time.Clock

/**
 * What "Handla till veckan" would do for the chosen [days]: the [plan] to apply (without the [excluded] ingredients), and
 * [all] as it would be with every ingredient ticked, so the sheet can still show what was unticked.
 */
data class DaysShoppingPreview(
    val days: Set<LocalDate>,
    val plan: ShoppingPlan,
    val all: ShoppingPlan,
    /** Every generated line, keyed by [GeneratedLine.key]; [GeneratedLine.askIfAtHome] is "Finns hemma?". */
    val lines: List<GeneratedLine>,
    /** What the pantry covers completely ("Räcker hemma: Ris"). */
    val coveredAtHome: List<String>,
    /** The planned meals on [days] that need shopping (cooked from a recipe). */
    val meals: List<MealEntry>,
    val excluded: Set<String> = emptySet(),
)

/**
 * "Handla till veckan": turns planned meals into lines on a shopping list. Re-runnable for the same days without
 * duplicates (see [ShoppingListGenerator.plan]); items typed by hand are never removed, and the pantry is only read.
 */
class MealShopping(
    private val meals: MealRepository,
    private val recipes: RecipeRepository,
    private val shopping: ShoppingRepository,
    private val family: FamilyRepository,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun preview(listId: String, week: IsoWeek): ShoppingPlan = preview(listId, week.days.toSet()).plan

    suspend fun generate(listId: String, week: IsoWeek): ShoppingPlan = generate(listId, week.days.toSet())

    /**
     * The preview for the chosen [days] (any days, in any weeks). [exclude] are the keys ([GeneratedLine.key]) of
     * ingredients the family unticked: they are not added, and what was generated for them earlier on these days goes.
     */
    suspend fun preview(listId: String, days: Set<LocalDate>, exclude: Set<String> = emptySet()): DaysShoppingPreview {
        val entries = days.map { it.isoWeek }.distinct().flatMap { meals.getWeek(it).entries.values }.filter { it.date in days }
        val demands = ShoppingListGenerator.demands(entries, recipes.all().associateBy { it.id }, householdSize())
        val settings = family.settings.first()
        val generation = ShoppingListGenerator.generate(
            demands,
            pantry = if (settings.usePantry) shopping.pantry.first() else emptyList(),
            aisleOverrides = shopping.aisleOverrides.first(),
        )
        val existing = shopping.getItems(listId)
        val now = clock.now()
        val me = sessions.session.value.memberId

        // Fresh ids are only drawn for the plan that is applied, so its ids stay stable between previews.
        fun planFor(lines: List<GeneratedLine>, newId: () -> String) =
            ShoppingListGenerator.plan(listId, existing, lines, days, newId, now, me)
        val chosen = generation.lines.filter { it.key !in exclude }
        val plan = planFor(chosen, ids::newId)
        val all = if (exclude.isEmpty()) {
            plan
        } else {
            var n = 0
            planFor(generation.lines) { "preview-${n++}" }
        }
        return DaysShoppingPreview(
            days = days,
            plan = plan,
            all = all,
            lines = generation.lines,
            coveredAtHome = generation.coveredAtHome,
            meals = demands.map { it.entry }.distinct().sortedBy { it.date },
            excluded = exclude intersect generation.lines.mapTo(HashSet()) { it.key },
        )
    }

    /** Applies [preview] for [days] without the [exclude]d ingredients. Never touches the pantry. */
    suspend fun generate(listId: String, days: Set<LocalDate>, exclude: Set<String> = emptySet()): ShoppingPlan {
        val plan = preview(listId, days, exclude).plan
        shopping.saveItems(plan.add + plan.update)
        plan.remove.forEach { shopping.deleteItem(it) }
        return plan
    }

    /** Portions when a meal has none set: everyone at home (helpers such as grandparents do not count). */
    suspend fun householdSize(): Int = family.people.first().count { it.role != Role.Caregiver }.coerceAtLeast(1)
}
