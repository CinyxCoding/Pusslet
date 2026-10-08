package se.pusslet.data.usecase

import kotlinx.coroutines.flow.first
import se.pusslet.core.model.Role
import se.pusslet.core.shopping.ShoppingListGenerator
import se.pusslet.core.shopping.ShoppingPlan
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.SessionProvider
import kotlin.time.Clock

/** "Handla till veckan": turns a week's meal plan into lines on a shopping list (re-runnable, see ShoppingListGenerator). */
class MealShopping(
    private val meals: MealRepository,
    private val recipes: RecipeRepository,
    private val shopping: ShoppingRepository,
    private val family: FamilyRepository,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun preview(listId: String, week: IsoWeek): ShoppingPlan {
        val plan = meals.getWeek(week)
        val demands = ShoppingListGenerator.demands(plan.entries.values.toList(), recipes.all().associateBy { it.id }, householdSize())
        val settings = family.settings.first()
        val lines = ShoppingListGenerator.lines(
            demands,
            pantry = if (settings.usePantry) shopping.pantry.first() else emptyList(),
            aisleOverrides = shopping.aisleOverrides.first(),
        )
        return ShoppingListGenerator.plan(
            listId = listId,
            existing = shopping.getItems(listId),
            lines = lines,
            dates = week.monday..week.sunday,
            newId = ids::newId,
            now = clock.now(),
            memberId = sessions.session.value.memberId,
        )
    }

    suspend fun generate(listId: String, week: IsoWeek): ShoppingPlan {
        val plan = preview(listId, week)
        shopping.saveItems(plan.add + plan.update)
        plan.remove.forEach { shopping.deleteItem(it) }
        return plan
    }

    /** Portions when a meal has none set: everyone at home (helpers such as grandparents do not count). */
    suspend fun householdSize(): Int = family.people.first().count { it.role != Role.Caregiver }.coerceAtLeast(1)
}
