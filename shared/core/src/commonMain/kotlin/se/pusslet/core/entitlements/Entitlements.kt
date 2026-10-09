package se.pusslet.core.entitlements

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What premium could unlock (docs/MONETIZATION.md). Safety and privacy features are never on this list. */
enum class Feature {
    /** More than [FreeTier.SUBSCRIPTIONS] calendar subscriptions. */
    UnlimitedSubscriptions,

    /** The family's own ICS feed for work calendars. */
    FamilyCalendarFeed,

    /** Sharing custody with the other household (phase 5). */
    SharedCustody,
    NutritionOverview,
    ShoppingWidget,

    /** Pictures shared with the whole family instead of kept on one phone. */
    SharedPictures,
    ExtraThemes,
}

data class EntitlementSet(val isPremium: Boolean, val features: Set<Feature>) {
    fun has(feature: Feature): Boolean = feature in features

    /** Null = no limit. */
    val subscriptionLimit: Int? get() = if (has(Feature.UnlimitedSubscriptions)) null else FreeTier.SUBSCRIPTIONS

    companion object {
        val Free = EntitlementSet(isPremium = false, features = emptySet())
        val Everything = EntitlementSet(isPremium = true, features = Feature.entries.toSet())
    }
}

/** Limits of the free version, used only when premium is switched on. */
object FreeTier {
    const val SUBSCRIPTIONS = 2
}

/**
 * Who may use what. Checked in ViewModels (never in UI code) so a limit can be switched on in one place and tested.
 * No feature may depend on payment working (BRIEF §7).
 */
interface Entitlements {
    val current: StateFlow<EntitlementSet>

    val isPremium: Boolean get() = current.value.isPremium
    val features: Set<Feature> get() = current.value.features

    fun has(feature: Feature): Boolean = current.value.has(feature)
}

/** Today: no payment is active, so everything is unlocked for everyone. */
object AllUnlockedEntitlements : Entitlements {
    override val current: StateFlow<EntitlementSet> = MutableStateFlow(EntitlementSet.Everything).asStateFlow()
}

/** A fixed set, for tests and previews. */
class FixedEntitlements(set: EntitlementSet) : Entitlements {
    override val current: StateFlow<EntitlementSet> = MutableStateFlow(set).asStateFlow()
}
