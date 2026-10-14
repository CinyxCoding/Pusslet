package se.pusslet.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.pusslet.ui.theme.Spacing

/**
 * A field that CHOOSES a value with a picker (sheet, dialog or menu): it looks like an outlined text field with ▾, the label
 * in the outline and the value inside ("Enhet: Ingen enhet", "Avdelning: Mejeri").
 *
 * Show [placeholder] when [value] is null ("Ingen enhet", "Inte bestämt"); never show "–" as a value. [highlight] marks a
 * value the quick-add parser was unsure about, in terracotta with a heavier outline (never `isError`). The whole field is
 * one TalkBack node with the drop-down list role: "Enhet, Ingen enhet". Use a text field for typing, a [NavRow] for opening
 * another screen and the older [ValueField] only where a flat "label/value" line fits better.
 */
@Composable
fun OutlinedValueField(
    label: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    highlight: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.bodySmall
    val accent = if (highlight) colors.tertiary else colors.onSurfaceVariant
    val outlineColor = if (highlight) colors.tertiary else colors.outline
    val outlineWidth = if (highlight) 2.dp else 1.dp
    val valueColor = when {
        value == null -> colors.onSurfaceVariant
        highlight -> colors.tertiary
        else -> colors.onSurface
    }
    // The outline starts at the vertical middle of the label, as in a Material outlined text field.
    val labelLineHeight = labelStyle.lineHeight.takeIf { it.isSp } ?: LabelLineHeight
    val labelHalfHeight = with(LocalDensity.current) { labelLineHeight.toDp() / 2 }
    var labelWidthPx by remember { mutableFloatStateOf(0f) }
    val interactions = remember { MutableInteractionSource() }
    Box(
        modifier
            .fillMaxWidth()
            .clickable(interactionSource = interactions, indication = null, role = Role.DropdownList, onClick = onClick),
    ) {
        Text(
            label,
            style = labelStyle,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = LabelStart - LabelGap, end = LabelStart)
                .onSizeChanged { labelWidthPx = it.width.toFloat() }
                .padding(horizontal = LabelGap),
        )
        Row(
            Modifier
                .padding(top = labelHalfHeight)
                .fillMaxWidth()
                .heightIn(min = FieldMinHeight)
                .clip(MaterialTheme.shapes.extraSmall)
                .indication(interactions, ripple())
                .outlineWithGap(MaterialTheme.shapes.extraSmall, outlineColor, outlineWidth, LabelStart - LabelGap) { labelWidthPx }
                .padding(start = Spacing.lg, end = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value ?: placeholder.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = valueColor,
                fontWeight = if (highlight && value != null) FontWeight.SemiBold else null,
                modifier = Modifier.weight(1f).padding(vertical = Spacing.md),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = accent)
        }
    }
}

/** Material's outlined text field height. */
private val FieldMinHeight = 56.dp

/** Where the label text starts, aligned with the value below it. */
private val LabelStart = 16.dp

/** Space between the outline and the label on either side of it. */
private val LabelGap = 4.dp

/** `bodySmall` line height (14/20) when a theme leaves it unspecified. */
private val LabelLineHeight = 20.sp

/** Draws [shape] as an outline with a gap of [gapWidthPx] starting [gapStart] from the leading edge (where the label sits). */
private fun Modifier.outlineWithGap(shape: Shape, color: Color, width: Dp, gapStart: Dp, gapWidthPx: () -> Float): Modifier =
    drawBehind {
        val stroke = width.toPx()
        val gap = gapWidthPx()
        val start = gapStart.toPx()
        val left = if (layoutDirection == LayoutDirection.Ltr) start else size.width - start - gap
        clipRect(left = left, top = -stroke, right = left + gap, bottom = stroke * 2, clipOp = ClipOp.Difference) {
            inset(stroke / 2) {
                drawOutline(shape.createOutline(size, layoutDirection, this), color, style = Stroke(stroke))
            }
        }
    }
