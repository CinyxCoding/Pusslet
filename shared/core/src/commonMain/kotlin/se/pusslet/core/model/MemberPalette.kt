package se.pusslet.core.model

import kotlin.math.pow

/**
 * Colours that identify family members. Each entry has a light- and a dark-theme pair
 * (container + content). Pairs are verified for WCAG AA (>= 4.5:1) in MemberPaletteTest,
 * and colour is never the only signal: UI always pairs it with the member's initial.
 *
 * Values are ARGB as [Long] so the palette can live in pure Kotlin and be unit tested.
 */
enum class MemberColor(
    val lightContainer: Long,
    val lightContent: Long,
    val darkContainer: Long,
    val darkContent: Long,
) {
    Sage(0xFF3D6649, 0xFFFFFFFF, 0xFFA9D3B2, 0xFF0F2E19),
    Terracotta(0xFF9E4329, 0xFFFFFFFF, 0xFFFFB59E, 0xFF45180A),
    Ocean(0xFF2D5D88, 0xFFFFFFFF, 0xFFA7CBEF, 0xFF0A2942),
    Plum(0xFF7A4A7E, 0xFFFFFFFF, 0xFFE6BAE9, 0xFF371440),
    Ochre(0xFF7D5F0C, 0xFFFFFFFF, 0xFFEBCB7A, 0xFF382900),
    Teal(0xFF1D6767, 0xFFFFFFFF, 0xFF8FD3D0, 0xFF003333),
    Rose(0xFFA23A5B, 0xFFFFFFFF, 0xFFFFB2C5, 0xFF470E22),
    Slate(0xFF4D596A, 0xFFFFFFFF, 0xFFBEC8D8, 0xFF1D2633),
    ;

    companion object {
        /** Stable assignment for new members: the first colour not yet used, wrapping around. */
        fun nextFree(used: Collection<MemberColor>): MemberColor = entries.firstOrNull { it !in used } ?: entries[used.size % entries.size]

        fun fromIndex(index: Int): MemberColor = entries[((index % entries.size) + entries.size) % entries.size]
    }
}

/** WCAG 2.x contrast helpers. */
object Contrast {
    fun relativeLuminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF).toDouble() / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    fun ratio(a: Long, b: Long): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
