package se.pusslet.android.screenshots

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The audit itself: it must find what TalkBack and a finger would stumble on, so a green screenshot test means something. */
@RunWith(AndroidJUnit4::class)
class AccessibilityAuditTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun findsAnUnnamedTinyControlAndPassesAMaterialOne() {
        compose.setContent {
            Column {
                Box(Modifier.size(24.dp).clickable {})
                Text("Spara", Modifier.size(width = 80.dp, height = 20.dp).clickable {})
                IconButton(onClick = {}) { Icon(Icons.Filled.Add, contentDescription = "Lägg till") }
            }
        }
        val result = AccessibilityAudit.audit(compose.onRoot().fetchSemanticsNode())
        assertEquals(3, result.checked)
        assertEquals(3, result.problems.size, result.problems.joinToString("\n"))
        assertTrue(result.problems.any { it.startsWith("No name") })
        assertEquals(2, result.problems.count { it.startsWith("Smaller than 48 dp") })
    }
}
