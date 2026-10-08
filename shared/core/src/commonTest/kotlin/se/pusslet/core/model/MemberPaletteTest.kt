package se.pusslet.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemberPaletteTest {
    // Theme surfaces from docs/DESIGN.md
    private val lightSurface = 0xFFFBF8F3
    private val darkSurface = 0xFF1A1C18

    @Test
    fun everyMemberColorMeetsAaForItsInitialInLightAndDark() {
        MemberColor.entries.forEach { color ->
            val light = Contrast.ratio(color.lightContainer, color.lightContent)
            val dark = Contrast.ratio(color.darkContainer, color.darkContent)
            assertTrue(light >= 4.5, "${color.name} light container/content contrast was $light")
            assertTrue(dark >= 4.5, "${color.name} dark container/content contrast was $dark")
        }
    }

    @Test
    fun memberColorsAreVisibleAgainstTheSurfaceAsNonTextElements() {
        // WCAG 1.4.11: graphical objects need 3:1 against adjacent colours.
        MemberColor.entries.forEach { color ->
            val light = Contrast.ratio(color.lightContainer, lightSurface)
            val dark = Contrast.ratio(color.darkContainer, darkSurface)
            assertTrue(light >= 3.0, "${color.name} vs light surface was $light")
            assertTrue(dark >= 3.0, "${color.name} vs dark surface was $dark")
        }
    }

    @Test
    fun contrastOfBlackOnWhiteIs21() {
        assertEquals(21.0, Contrast.ratio(0xFF000000, 0xFFFFFFFF), 0.01)
    }

    @Test
    fun nextFreePicksFirstUnusedThenWraps() {
        assertEquals(MemberColor.Sage, MemberColor.nextFree(emptyList()))
        assertEquals(MemberColor.Ocean, MemberColor.nextFree(listOf(MemberColor.Sage, MemberColor.Terracotta)))
        assertEquals(MemberColor.Sage, MemberColor.nextFree(MemberColor.entries))
        assertEquals(MemberColor.Slate, MemberColor.fromIndex(-1))
    }
}
