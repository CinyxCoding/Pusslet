package se.pusslet.core.premium

/**
 * What a premium purchase would add (BRIEF §7, docs/MONETIZATION.md says why each one). Nothing is sold yet and every
 * build unlocks everything ([Entitlement.Everything]); the free limits live here so the gates are tested and ready for a
 * launch. A gate only ever stops something new: what the family already has keeps working without premium. Safety, health,
 * privacy and joining a family (also the other home's space) are never premium.
 */
enum class Feature {
    // Built and gated.

    /** More than [FreeLimits.SUBSCRIPTIONS] ICS subscriptions (school, club, TimeEdit …). */
    UnlimitedSubscriptions,

    /** Starting to share the children with the other home (ADR-056); joining a space is always free. */
    OtherHousehold,

    // Built, not gated yet.

    /** Costs shared with the other home (ADR-057), for both homes when one of them has premium. */
    SharedCosts,

    /** A school break planned day by day, and the preschool hours (ADR-051); the breaks themselves show for everyone. */
    BreakPlanning,

    /** The week's nutrition overview from Livsmedelsverket's data; a recipe's own values stay free. */
    NutritionOverview,

    /** Home-screen widgets beyond Idag and Inköpslistan. */
    ExtraWidgets,

    // Not built yet (docs/MONETIZATION.md, "Nytt för premium").

    /** Pictures and receipts kept in the cloud for the family and the other home; today they stay on the phone (ADR-049). */
    CloudPictures,

    /** For two homes: the costs per month and year, days per home and schedule changes, a Swish request when settling up. */
    TwoHomeReports,

    /** The year's sick days and VAB per child and parent, for Försäkringskassan; reporting a sick day stays free. */
    SickDayYear,

    /** A family screen for the kitchen tablet: always on, the day and the week. */
    FamilyScreen,

    /** Weekly menus suggested from the family's own recipes, by rules (ADR-001), with the shopping list. */
    MenuSuggestions,

    /** More than one reminder per event. */
    ExtraReminders,

    /** A calendar link of one's own per person, besides the family's link (ADR-041). */
    PersonalFeeds,

    /** How the to-dos were shared over time, besides the week's distribution view. */
    DistributionHistory,

    /** More than one family on one account (grandparents with several families, bonus families). */
    SeveralFamilies,

    /** Colour themes and icons. */
    Themes,

    /** A temporary link for the babysitter, without an account (the hand-over card, `handoverShares`). */
    SitterLink,
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
