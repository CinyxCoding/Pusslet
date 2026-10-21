package se.pusslet.core.premium

/**
 * What a premium purchase would add (BRIEF §7, docs/MONETIZATION.md). Nothing is sold yet and every build unlocks
 * everything ([Entitlement.Everything]); the free limits live here so the gates are tested and ready for a launch. A gate
 * only ever stops something new: what the family already has keeps working without premium.
 */
enum class Feature {
    /** More than [FreeLimits.SUBSCRIPTIONS] ICS subscriptions (school, club, TimeEdit …). */
    UnlimitedSubscriptions,

    /** Custody schedule and hand-overs shared with the other household (BRIEF V2, flerhushållsdelning). */
    OtherHousehold,

    /** The week's nutrition overview from Livsmedelsverket's data. */
    NutritionOverview,

    /** Home-screen widgets beyond Idag and Inköpslistan. */
    ExtraWidgets,
}

/** What this family may use now; [isPremium] for a "Premium" label, [features] for the gates. */
data class Entitlement(val isPremium: Boolean, val features: Set<Feature>) {
    fun has(feature: Feature): Boolean = feature in features

    /** Whether one more subscription may be added to [count] existing ones. */
    fun canAddSubscription(count: Int): Boolean = has(Feature.UnlimitedSubscriptions) || count < FreeLimits.SUBSCRIPTIONS

    companion object {
        /** Every build until payments are switched on (BRIEF §7: no feature may depend on billing working). */
        val Everything = Entitlement(isPremium = true, features = Feature.entries.toSet())

        /** The free version as it would be after a launch with premium. */
        val Free = Entitlement(isPremium = false, features = emptySet())
    }
}

/** The free version's limits (docs/MONETIZATION.md says why these numbers). */
object FreeLimits {
    const val SUBSCRIPTIONS = 3
}
