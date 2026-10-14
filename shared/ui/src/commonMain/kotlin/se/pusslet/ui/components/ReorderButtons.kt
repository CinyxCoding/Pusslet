package se.pusslet.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.action_move_down_named
import se.pusslet.ui.resources.action_move_up_named
import se.pusslet.ui.theme.Spacing

/**
 * Up and down buttons that move one row in a user-ordered list (aisles of a store, sections of Idag). Omordning always works
 * with these buttons, so TalkBack and switch users can do it; drag and drop may come on top, never instead.
 *
 * [name] is the row's name and goes into the TalkBack labels ("Flytta upp Mejeri"). Disable up on the first row and down on
 * the last one. Both buttons are 48 dp.
 */
@Composable
fun ReorderButtons(
    name: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(Spacing.touchTarget)) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(Res.string.action_move_up_named, name))
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(Spacing.touchTarget)) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(Res.string.action_move_down_named, name))
        }
    }
}
