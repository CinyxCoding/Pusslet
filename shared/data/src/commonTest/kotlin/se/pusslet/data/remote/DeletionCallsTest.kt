package se.pusslet.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals

/** The error codes and reasons functions/src/privacy.ts sends, as the Android SDK hands them over. */
class DeletionCallsTest {
    private fun refusal(code: String, message: String? = code, details: Any? = null) = DeletionCalls.refusalFor(code, message, details)

    @Test
    fun theLastParentIsRecognisedFromDetailsOrMessage() {
        assertEquals(
            DeletionRefusal.LastParent,
            refusal("FAILED_PRECONDITION", "last-parent", mapOf("reason" to "last-parent", "familyId" to "f1")),
        )
        assertEquals(DeletionRefusal.LastParent, refusal("FAILED_PRECONDITION", "last-parent"))
        assertEquals(DeletionRefusal.RecentLoginRequired, refusal("FAILED_PRECONDITION", "x", mapOf("reason" to "requires-recent-login")))
        assertEquals(DeletionRefusal.Unknown, refusal("FAILED_PRECONDITION", "something-else"))
    }

    @Test
    fun accessAndConnectionErrors() {
        assertEquals(DeletionRefusal.NotSignedIn, refusal("UNAUTHENTICATED", "not-signed-in"))
        assertEquals(DeletionRefusal.NotAllowed, refusal("PERMISSION_DENIED", "parents-only"))
        assertEquals(DeletionRefusal.NotAllowed, refusal("PERMISSION_DENIED", "not-a-member"))
        assertEquals(DeletionRefusal.Network, refusal("UNAVAILABLE"))
        assertEquals(DeletionRefusal.Network, refusal("DEADLINE_EXCEEDED"))
        assertEquals(DeletionRefusal.Network, refusal("INTERNAL"))
        assertEquals(DeletionRefusal.Unknown, refusal("INVALID_ARGUMENT", "invalid-family-id"))
    }
}
