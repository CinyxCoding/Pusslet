package se.pusslet.android

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the shared string files: Swedish and English must have the same keys and placeholders, and Android-style
 * escapes (\') must not be used because Compose Multiplatform resources show them literally ("I\'ll take it").
 */
class StringResourcesTest {
    private val dir = File("../shared/ui/src/commonMain/composeResources")

    private fun load(folder: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "$folder/strings.xml"))
        val nodes = doc.documentElement.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to e.textContent
        }
    }

    private fun arrays(folder: String): Map<String, Int> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "$folder/strings.xml"))
        val nodes = doc.documentElement.getElementsByTagName("string-array")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to e.getElementsByTagName("item").length
        }
    }

    private val placeholder = Regex("""%(\d+\$)?[sd]""")

    /** The few Android resources the launcher reads from the manifest (widget picker). */
    @Test
    fun androidWidgetLabelsAreTranslated() {
        fun keys(folder: String): Set<String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$folder/widget_strings.xml"))
            val nodes = doc.documentElement.getElementsByTagName("string")
            return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("name") }.toSet()
        }
        assertEquals(keys("values"), keys("values-en"))
    }

    @Test
    fun swedishAndEnglishHaveTheSameKeys() {
        val sv = load("values")
        val en = load("values-en")
        assertEquals(emptySet(), sv.keys - en.keys, "Missing in English")
        assertEquals(emptySet(), en.keys - sv.keys, "Missing in Swedish")
        assertEquals(arrays("values"), arrays("values-en"))
    }

    @Test
    fun placeholdersMatch() {
        val sv = load("values")
        val en = load("values-en")
        val mismatches = sv.keys.intersect(en.keys).filter { key ->
            placeholder.findAll(sv.getValue(key)).map { it.value }.sorted().toList() !=
                placeholder.findAll(en.getValue(key)).map { it.value }.sorted().toList()
        }
        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun noAndroidStyleEscapes() {
        for (folder in listOf("values", "values-en")) {
            val bad = load(folder).filterValues { "\\'" in it || "\\\"" in it }.keys
            assertTrue(bad.isEmpty(), "$folder uses \\' or \\\" in: $bad")
        }
    }
}
