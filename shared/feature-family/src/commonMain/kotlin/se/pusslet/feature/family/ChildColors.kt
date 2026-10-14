package se.pusslet.feature.family

import se.pusslet.core.model.MemberColor

/** Children start on Ocean, then Plum: terracotta means "reminder" in the app, so the first child never gets it. */
private val childStartColors = listOf(MemberColor.Ocean, MemberColor.Plum)

/** Colour for a new child, given the colours already [used] in the family; after Ocean and Plum the usual rotation. */
internal fun nextChildColor(used: Collection<MemberColor>): MemberColor =
    childStartColors.firstOrNull { it !in used } ?: MemberColor.nextFree(used)
