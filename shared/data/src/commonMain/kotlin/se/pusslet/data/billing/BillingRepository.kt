package se.pusslet.data.billing

/** A premium subscription offered in the store ("Pusslet Premium, 29 kr/månad"). */
data class PremiumProduct(val id: String, val title: String, val formattedPrice: String, val period: BillingPeriod)

enum class BillingPeriod { Monthly, Yearly }

sealed interface PurchaseResult {
    /** Bought or restored; entitlements follow once the server has verified the purchase. */
    data object Success : PurchaseResult

    data object Cancelled : PurchaseResult

    /** No store on this device or billing not switched on (today, always). */
    data object NotAvailable : PurchaseResult

    data class Failed(val reason: String) : PurchaseResult
}

/**
 * Google Play Billing behind an interface (docs/MONETIZATION.md). Not active: [NoBilling] is bound everywhere and the app
 * unlocks everything through `AllUnlockedEntitlements`.
 *
 * TODO(billing): implement with the Play Billing Library in composeApp (BillingClient, queryProductDetails,
 *  launchBillingFlow, acknowledge) and bind it in the Android module.
 * TODO(billing): verify purchases on the server (Cloud Function against the Play Developer API + Real-time Developer
 *  Notifications) and write `families/{id}.premiumUntil`; never trust the client.
 * TODO(billing): replace AllUnlockedEntitlements with entitlements read from `premiumUntil`, and forbid clients from
 *  writing that field in firestore.rules (with a rules test).
 */
interface BillingRepository {
    val available: Boolean

    suspend fun products(): List<PremiumProduct>

    suspend fun purchase(productId: String): PurchaseResult

    suspend fun restore(): PurchaseResult
}

/** Billing switched off (BRIEF §7: no payment now). */
object NoBilling : BillingRepository {
    override val available: Boolean = false

    override suspend fun products(): List<PremiumProduct> = emptyList()

    override suspend fun purchase(productId: String): PurchaseResult = PurchaseResult.NotAvailable

    override suspend fun restore(): PurchaseResult = PurchaseResult.NotAvailable
}
