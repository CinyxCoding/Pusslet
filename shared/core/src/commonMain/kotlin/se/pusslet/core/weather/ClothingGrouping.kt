package se.pusslet.core.weather

import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.today.ChildClothing

/** Children who should wear the same thing, shown as one row: "Ella och Simon: tunn jacka · reflexväst". */
data class ClothingGroup(val children: List<FamilyPerson>, val advice: List<ClothingAdvice>)

/**
 * Puts children with identical clothing advice (same items in the same order) on one row, so the weather card never
 * repeats itself (UX plan 1.7). Rule based and order preserving: a group sits where its first child sits, children keep
 * their order inside it, and children without any advice are left out.
 */
object ClothingGrouping {
    fun group(clothing: List<ChildClothing>): List<ClothingGroup> = clothing
        .filter { it.advice.isNotEmpty() }
        .groupBy(keySelector = { it.advice }, valueTransform = { it.child })
        .map { (advice, children) -> ClothingGroup(children, advice) }
}
