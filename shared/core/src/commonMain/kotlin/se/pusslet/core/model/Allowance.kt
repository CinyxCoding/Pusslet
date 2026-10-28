package se.pusslet.core.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Veckopeng for one child (BRIEF §4.7, ADR-055): a weekly base plus kronor per approved star. [paidWeeks] maps an ISO week key
 * ("2026-W41") to when it was paid out; merged per week so two parents can mark different weeks.
 */
@Serializable
data class Allowance(
    val childId: String,
    val weekly: Int = 0,
    val perStar: Int = 0,
    val paidWeeks: Map<String, Instant> = emptyMap(),
)
