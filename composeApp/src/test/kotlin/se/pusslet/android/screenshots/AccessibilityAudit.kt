package se.pusslet.android.screenshots

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize

/**
 * What TalkBack and a finger meet on a screen, checked on the merged semantics tree: every control that can be tapped,
 * toggled or typed into has a name, and is at least 48 × 48 dp (Material and WCAG 2.5.8). Google's Accessibility Test
 * Framework does not run under Robolectric, so the screenshot tests use this instead; colour contrast is checked on the
 * theme itself (`ThemeContrastTest`).
 */
object AccessibilityAudit {
    private val MIN_TOUCH = 48.dp

    /** [checked] controls, and one line per problem with enough of the node to find it in the screenshot. */
    class Result(val checked: Int, val problems: List<String>)

    fun audit(root: SemanticsNode): Result {
        val out = ArrayList<String>()
        val checked = visit(root, out)
        return Result(checked, out)
    }

    private fun visit(node: SemanticsNode, out: MutableList<String>): Int {
        var checked = 0
        val config = node.config
        val hidden = config.contains(SemanticsProperties.HideFromAccessibility) || config.contains(SemanticsProperties.Disabled)
        val actionable = config.contains(SemanticsActions.OnClick) || config.contains(SemanticsProperties.ToggleableState) ||
            config.contains(SemanticsActions.SetText)
        if (actionable && !hidden && node.layoutInfo.isPlaced) {
            checked++
            val name = name(node)
            val where = describe(node, name)
            if (name.isBlank()) out += "No name: $where"
            val density = node.layoutInfo.density
            val min = with(density) { MIN_TOUCH.toPx() } - 1f
            // The control's own size, as Accessibility Scanner measures it: Compose widens a small touch area to 48 dp by
            // itself, but only where nothing else is in the way, and the control still looks too small to aim for.
            // A row cut by the screen edge counts with its full size.
            val size = IntSize(node.layoutInfo.width, node.layoutInfo.height).toSize()
            if (size.width < min || size.height < min) {
                val dpW = with(density) { size.width.toDp().value.toInt() }
                val dpH = with(density) { size.height.toDp().value.toInt() }
                out += "Smaller than 48 dp ($dpW×$dpH dp): $where"
            }
        }
        return checked + node.children.sumOf { visit(it, out) }
    }

    private fun name(node: SemanticsNode): String {
        val c = node.config
        return listOfNotNull(
            c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
            c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
            c.getOrNull(SemanticsProperties.EditableText)?.text,
        ).joinToString(" ").trim()
    }

    private fun describe(node: SemanticsNode, name: String): String {
        val role = node.config.getOrNull(SemanticsProperties.Role)
        val b = node.boundsInRoot
        return "\"${name.take(60)}\" role=${role ?: "-"} at [${b.left.toInt()},${b.top.toInt()}][${b.right.toInt()},${b.bottom.toInt()}]"
    }
}
