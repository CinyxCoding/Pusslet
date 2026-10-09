package se.pusslet.feature.calendar

import se.pusslet.core.entitlements.AllUnlockedEntitlements
import se.pusslet.core.entitlements.EntitlementSet
import se.pusslet.core.entitlements.Entitlements
import se.pusslet.core.entitlements.FixedEntitlements
import se.pusslet.core.entitlements.FreeTier
import se.pusslet.core.ics.IcsParser
import se.pusslet.core.model.CalendarSubscription
import se.pusslet.data.sources.CachedFetcher
import se.pusslet.data.sources.SubscriptionSync
import se.pusslet.feature.calendar.subscriptions.SubscriptionsViewModel
import se.pusslet.testing.FakeCacheDao
import se.pusslet.testing.TestGraph
import se.pusslet.testing.collectInBackground
import se.pusslet.testing.viewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SubscriptionLimitTest {
    private fun vm(g: TestGraph, entitlements: Entitlements) =
        SubscriptionsViewModel(
            g.calendar,
            g.family,
            SubscriptionSync(CachedFetcher(g.http, FakeCacheDao(), g.clock), IcsParser(), g.zone, g.clock),
            g.sessions,
            g.ids,
            entitlements,
        )

    private suspend fun TestGraph.twoSubscriptions() {
        calendar.saveSubscription(CalendarSubscription("s1", "Skolan", "https://example.se/skolan.ics"))
        calendar.saveSubscription(CalendarSubscription("s2", "Fotbollen", "https://example.se/fotboll.ics"))
    }

    @Test
    fun everythingIsUnlockedToday() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.twoSubscriptions()
        val vm = vm(g, AllUnlockedEntitlements)
        collectInBackground(vm.state)
        vm.startNew()
        assertNotNull(vm.state.value.draft, "a third subscription can be added")
        assertNull(vm.state.value.limitReached)
    }

    @Test
    fun theFreeTierLimitIsCheckedInTheViewModel() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.twoSubscriptions()
        val vm = vm(g, FixedEntitlements(EntitlementSet.Free))
        collectInBackground(vm.state)
        vm.startNew("webcal://example.se/handboll.ics")
        assertNull(vm.state.value.draft)
        assertEquals(FreeTier.SUBSCRIPTIONS, vm.state.value.limitReached)
        vm.dismissLimit()
        assertNull(vm.state.value.limitReached)
    }
}
