package se.pusslet.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackendConfigTest {
    @Test
    fun thePrivacyPolicyIsOnTheLinkHostOnlyWithAServer() {
        assertEquals("https://pusslet.web.app/privacy/", config(BackendMode.Firebase).privacyPolicyUrl)
        assertEquals("https://familj.example.se/privacy/", config(BackendMode.Firebase, "familj.example.se").privacyPolicyUrl)
        assertNull(config(BackendMode.Emulator).privacyPolicyUrl)
        assertNull(config(BackendMode.LocalOnly).privacyPolicyUrl)
    }

    @Test
    fun theTermsAreNextToThePrivacyPolicy() {
        assertEquals("https://familj.example.se/villkor/", config(BackendMode.Firebase, "familj.example.se").termsUrl)
        assertNull(config(BackendMode.Emulator).termsUrl)
        assertNull(config(BackendMode.LocalOnly).termsUrl)
    }

    private fun config(mode: BackendMode, linkHost: String = "pusslet.web.app") =
        BackendConfig(mode, seedDemoData = false, versionName = "1", linkHost = linkHost)
}
