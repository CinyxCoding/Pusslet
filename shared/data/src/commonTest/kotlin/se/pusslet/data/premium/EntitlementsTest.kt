package se.pusslet.data.premium

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.pusslet.core.premium.Entitlement
import se.pusslet.core.premium.Feature
import se.pusslet.core.premium.FreeLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeBilling : BillingRepository {
    override val owned = MutableStateFlow(emptySet<String>())

    override suspend fun products(): List<PremiumProduct> = emptyList()

    override suspend fun purchase(productId: String): PurchaseResult = PurchaseResult.Unavailable

    override suspend fun restore() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class EntitlementsTest {
    @Test
    fun everyBuildTodayHasEverything() {
        val now: StateFlow<Entitlement> = AllUnlocked.current
        assertTrue(now.value.isPremium)
        assertTrue(Feature.entries.all(now.value::has))
        assertTrue(now.value.canAddSubscription(100))
    }

    @Test
    fun theFreeVersionStopsOnlyWhatIsNew() {
        val free = Entitlement.Free
        assertTrue(free.canAddSubscription(FreeLimits.SUBSCRIPTIONS - 1))
        assertFalse(free.canAddSubscription(FreeLimits.SUBSCRIPTIONS))
        assertFalse(free.has(Feature.Meals))
        assertFalse(free.has(Feature.PhoneCalendar))
    }

    @Test
    fun eitherSubscriptionUnlocksEverythingAndARefundTakesItBack() = runTest {
        val billing = FakeBilling()
        val entitlements = BillingEntitlements(billing, backgroundScope)
        assertEquals(Entitlement.Free, entitlements.current.value)

        billing.owned.value = setOf(PremiumProducts.YEARLY)
        runCurrent()
        assertEquals(Entitlement.Everything, entitlements.current.value)

        billing.owned.value = setOf("something_else")
        runCurrent()
        assertEquals(Entitlement.Free, entitlements.current.value)
    }
}
