package se.pusslet.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** − 4 + : big buttons for portions and amounts. The value is announced when it changes. */
@Composable
fun Stepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    decreaseLabel: String,
    increaseLabel: String,
    modifier: Modifier = Modifier,
    range: IntRange = 1..99,
    valueLabel: String = value.toString(),
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { onValueChange(value - 1) }, enabled = value > range.first) {
            Text(
                "−",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.clearAndSetSemantics { contentDescription = decreaseLabel },
            )
        }
        Text(
            valueLabel,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 48.dp).clearAndSetSemantics {
                contentDescription = valueLabel
                liveRegion = LiveRegionMode.Polite
            },
        )
        FilledTonalIconButton(onClick = { onValueChange(value + 1) }, enabled = value < range.last) {
            Icon(Icons.Filled.Add, contentDescription = increaseLabel)
        }
    }
}
