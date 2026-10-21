package se.pusslet.data.premium

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import se.pusslet.core.premium.Entitlement
import se.pusslet.core.premium.Feature

/** What this family may use (BRIEF §7). The screens ask this, never a billing library. */
interface Entitlements {
    val current: StateFlow<Entitlement>
}

/** Every build today: everything is unlocked and nothing asks for payment. */
object AllUnlocked : Entitlements {
    override val current: StateFlow<Entitlement> = MutableStateFlow(Entitlement.Everything).asStateFlow()
}

/** A fixed entitlement, for tests and previews of the free version. */
class FixedEntitlements(entitlement: Entitlement) : Entitlements {
    private val state = MutableStateFlow(entitlement)
    override val current: StateFlow<Entitlement> = state.asStateFlow()

    fun set(entitlement: Entitlement) {
        state.value = entitlement
    }
}

/** A product in the store, as the paywall would show it. */
data class PremiumProduct(val id: String, val title: String, val price: String)

/** How a purchase ended. [Unavailable] covers no Play Store, no network and billing not switched on. */
enum class PurchaseResult { Purchased, Pending, Cancelled, Unavailable }

/**
 * Google Play Billing behind an interface (BRIEF §7). Prepared, not active: the app binds [AllUnlocked], so nothing here
 * runs and no billing library is in the build. docs/MONETIZATION.md lists the steps to switch it on.
 *
 * TODO(billing): implement with Play Billing Library in composeApp (`PlayBillingRepository`: BillingClient with
 *  `enablePendingPurchases`, query [PremiumProducts.ALL], acknowledge purchases within three days, verify the purchase token
 *  in a Cloud Function before trusting it, and listen for refunds/expiry via Real-time developer notifications).
 */
interface BillingRepository {
    /** The product ids this account owns right now (acknowledged, not refunded or expired). */
    val owned: StateFlow<Set<String>>

    suspend fun products(): List<PremiumProduct>

    /** Starts the store's own purchase sheet; the platform side needs the current activity. */
    suspend fun purchase(productId: String): PurchaseResult

    /** Restores purchases made on another phone with the same Google account. */
    suspend fun restore()
}

/** The product ids to create in Play Console (docs/MONETIZATION.md). */
object PremiumProducts {
    const val MONTHLY = "pusslet_premium_monthly"
    const val YEARLY = "pusslet_premium_yearly"
    val ALL = listOf(MONTHLY, YEARLY)
}

/**
 * [Entitlements] from what [BillingRepository] says is owned: either subscription unlocks every [Feature]. Not bound yet;
 * switching payments on means binding this instead of [AllUnlocked] (and keeping [AllUnlocked] for demo and dev).
 */
class BillingEntitlements(billing: BillingRepository, scope: CoroutineScope) : Entitlements {
    override val current: StateFlow<Entitlement> = billing.owned
        .map(::entitlementFor)
        .stateIn(scope, SharingStarted.Eagerly, entitlementFor(billing.owned.value))

    companion object {
        fun entitlementFor(owned: Set<String>): Entitlement =
            if (owned.any { it in PremiumProducts.ALL }) Entitlement.Everything else Entitlement.Free
    }
}
