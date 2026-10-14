package se.pusslet.core.weather

import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.MemberColor
import se.pusslet.core.today.ChildClothing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClothingGroupingTest {
    private val ella = FamilyPerson("c-ella", "Ella", MemberColor.Ocean, isChild = true)
    private val simon = FamilyPerson("c-simon", "Simon", MemberColor.Plum, isChild = true)
    private val vera = FamilyPerson("c-vera", "Vera", MemberColor.Teal, isChild = true)

    private val autumn = listOf(ClothingAdvice.LightJacket, ClothingAdvice.Reflective)
    private val rain = listOf(ClothingAdvice.RainGearAndBoots, ClothingAdvice.ExtraClothes, ClothingAdvice.Reflective)

    @Test
    fun childrenWithTheSameAdviceShareOneRow() {
        val groups = ClothingGrouping.group(listOf(ChildClothing(ella, autumn), ChildClothing(simon, autumn)))

        assertEquals(listOf(ClothingGroup(listOf(ella, simon), autumn)), groups)
    }

    @Test
    fun differentAdviceGivesOneRowPerChildInFamilyOrder() {
        val groups = ClothingGrouping.group(listOf(ChildClothing(simon, rain), ChildClothing(ella, autumn)))

        assertEquals(listOf(listOf(simon), listOf(ella)), groups.map { it.children })
        assertEquals(listOf(rain, autumn), groups.map { it.advice })
    }

    @Test
    fun aGroupSitsWhereItsFirstChildSitsAndKeepsTheChildOrder() {
        val groups = ClothingGrouping.group(
            listOf(ChildClothing(ella, autumn), ChildClothing(simon, rain), ChildClothing(vera, autumn)),
        )

        assertEquals(
            listOf(ClothingGroup(listOf(ella, vera), autumn), ClothingGroup(listOf(simon), rain)),
            groups,
        )
    }

    @Test
    fun childrenWithoutAdviceAreLeftOut() {
        val groups = ClothingGrouping.group(
            listOf(ChildClothing(ella, emptyList()), ChildClothing(simon, autumn), ChildClothing(vera, emptyList())),
        )

        assertEquals(listOf(ClothingGroup(listOf(simon), autumn)), groups)
    }

    @Test
    fun theSameItemsInAnotherOrderAreNotIdentical() {
        val groups = ClothingGrouping.group(listOf(ChildClothing(ella, autumn), ChildClothing(simon, autumn.reversed())))

        assertEquals(2, groups.size)
    }

    @Test
    fun noChildrenOrNoAdviceGivesNoRows() {
        assertTrue(ClothingGrouping.group(emptyList()).isEmpty())
        assertTrue(ClothingGrouping.group(listOf(ChildClothing(ella, emptyList()))).isEmpty())
    }
}
