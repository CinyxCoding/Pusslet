package se.pusslet.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.MemberColor
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.color_ocean
import se.pusslet.ui.resources.color_ochre
import se.pusslet.ui.resources.color_plum
import se.pusslet.ui.resources.color_rose
import se.pusslet.ui.resources.color_sage
import se.pusslet.ui.resources.color_slate
import se.pusslet.ui.resources.color_teal
import se.pusslet.ui.resources.color_terracotta
import se.pusslet.ui.theme.Spacing

/**
 * The eight member colours as round badges, with a ✓ on the chosen one. Each is a 48 dp radio button named after its
 * colour ("Salvia, vald" with TalkBack); the ✓ is only drawn, since the selected state is read out already.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPicker(selected: MemberColor, onPick: (MemberColor) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        MemberColor.entries.forEach { c ->
            val name = colorName(c)
            Box(
                Modifier
                    .sizeIn(minWidth = Spacing.touchTarget, minHeight = Spacing.touchTarget)
                    .selectable(selected = c == selected, role = Role.RadioButton) { onPick(c) }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                ColorBadge(c, if (c == selected) "✓" else "", "", Modifier.clearAndSetSemantics {}, size = 40.dp)
            }
        }
    }
}

/** The colour's name ("Salvia", "Terrakotta" …), for TalkBack and wherever a colour is named in text. */
@Composable
fun colorName(color: MemberColor): String = stringResource(colorNames.getValue(color))

private val colorNames: Map<MemberColor, StringResource> = mapOf(
    MemberColor.Sage to Res.string.color_sage,
    MemberColor.Terracotta to Res.string.color_terracotta,
    MemberColor.Ocean to Res.string.color_ocean,
    MemberColor.Plum to Res.string.color_plum,
    MemberColor.Ochre to Res.string.color_ochre,
    MemberColor.Teal to Res.string.color_teal,
    MemberColor.Rose to Res.string.color_rose,
    MemberColor.Slate to Res.string.color_slate,
)
