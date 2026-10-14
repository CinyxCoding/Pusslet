package se.pusslet.feature.family

import se.pusslet.core.model.MemberColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ChildColorsTest {
    @Test
    fun firstChildIsOceanNeverTerracotta() {
        val first = nextChildColor(listOf(MemberColor.Sage))
        assertEquals(MemberColor.Ocean, first)
        assertNotEquals(MemberColor.Terracotta, first)
    }

    @Test
    fun secondChildIsPlumThenTheUsualRotation() {
        assertEquals(MemberColor.Plum, nextChildColor(listOf(MemberColor.Sage, MemberColor.Ocean)))
        assertEquals(MemberColor.Terracotta, nextChildColor(listOf(MemberColor.Sage, MemberColor.Ocean, MemberColor.Plum)))
    }

    @Test
    fun skipsOceanWhenAParentAlreadyHasIt() {
        assertEquals(MemberColor.Plum, nextChildColor(listOf(MemberColor.Sage, MemberColor.Ocean, MemberColor.Slate)))
        assertNotEquals(MemberColor.Terracotta, nextChildColor(listOf(MemberColor.Ocean)))
    }
}
