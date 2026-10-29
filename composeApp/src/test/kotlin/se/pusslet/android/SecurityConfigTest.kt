package se.pusslet.android

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the platform settings docs/SECURITY.md relies on, so a later change cannot loosen them unnoticed: HTTPS only in
 * production, only the components that must be reachable from other apps exported, a FileProvider that shares nothing but
 * the attached pictures, and backups without account tokens or pictures.
 */
class SecurityConfigTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(path)).documentElement

    private fun Element.children(tag: String): List<Element> =
        getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    private fun Element.name(): String = getAttributeNS(android, "name")

    private fun Element.excluded(): List<Pair<String, String>> =
        children("exclude").map { it.getAttribute("domain") to it.getAttribute("path") }

    @Test
    fun productionTalksHttpsOnlyAndTrustsOnlyTheSystem() {
        val config = xml("src/main/res/xml/network_security_config.xml")
        assertEquals("false", config.children("base-config").single().getAttribute("cleartextTrafficPermitted"))
        assertTrue(config.children("debug-overrides").isEmpty())
        assertTrue(config.children("certificates").none { it.getAttribute("src") == "user" })
        assertTrue(config.children("domain-config").isEmpty(), "no exceptions to HTTPS-only")
    }

    @Test
    fun onlyTheEntryPointsOtherAppsNeedAreExported() {
        val manifest = xml("src/main/AndroidManifest.xml")
        val application = manifest.children("application").single()
        assertEquals("", application.getAttributeNS(android, "debuggable"))
        assertEquals("", application.getAttributeNS(android, "usesCleartextTraffic"))
        val components = listOf("activity", "service", "receiver", "provider").flatMap { application.children(it) }
        components.forEach { assertTrue(it.hasAttributeNS(android, "exported"), "${it.name()} says exported") }
        val exported = components.filter { it.getAttributeNS(android, "exported") == "true" }.map { it.name() }
        assertEquals(setOf(".MainActivity", ".widget.TodayWidgetReceiver", ".widget.ShoppingWidgetReceiver"), exported.toSet())
        val provider = application.children("provider").single { it.name() == "androidx.core.content.FileProvider" }
        assertEquals("false", provider.getAttributeNS(android, "exported"))
    }

    @Test
    fun theFileProviderSharesOnlyAttachedPictures() {
        val paths = xml("src/main/res/xml/file_paths.xml")
        val shared = (0 until paths.childNodes.length).mapNotNull { paths.childNodes.item(it) as? Element }
        assertEquals(listOf("files-path" to "attachments/"), shared.map { it.tagName to it.getAttribute("path") })
    }

    @Test
    fun backupsLeaveOutAccountTokensAndPictures() {
        val rules = xml("src/main/res/xml/data_extraction_rules.xml")
        val cloud = rules.children("cloud-backup").single().excluded()
        assertTrue("sharedpref" to "." in cloud)
        assertTrue("file" to "attachments/" in cloud)
        assertTrue("sharedpref" in rules.children("device-transfer").single().excluded().map { it.first })
        val legacy = xml("src/main/res/xml/backup_rules.xml").excluded()
        assertTrue("sharedpref" to "." in legacy && "file" to "attachments/" in legacy)
        assertNull(rules.children("include").firstOrNull(), "an include would back up only what it names")
    }
}
