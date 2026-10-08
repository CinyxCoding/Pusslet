package se.pusslet.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.MemberColor
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.color_label
import se.pusslet.ui.theme.Spacing

/** The contrast-tested palette as big round swatches; the chosen one carries a check mark (not only colour). */
@Composable
fun ColorPicker(selected: MemberColor, onPick: (MemberColor) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        MemberColor.entries.forEachIndexed { i, c ->
            val label = stringResource(Res.string.color_label, i + 1)
            Row(
                Modifier.selectable(selected = c == selected, role = Role.RadioButton) { onPick(c) }.heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColorBadge(c, if (c == selected) "✓" else "", label, size = 40.dp)
            }
        }
    }
}
