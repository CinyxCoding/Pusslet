package se.pusslet.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import se.pusslet.ui.theme.Spacing

/**
 * The bottom sheets' drag handle (`ModalBottomSheet(dragHandle = { SheetDragHandle() })`): Material's own bar, in a
 * 48 × 48 dp area. Material's handle is 32 dp wide, and the sheet puts its expand and close actions for TalkBack on it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetDragHandle() {
    Box(Modifier.sizeIn(minWidth = Spacing.touchTarget, minHeight = Spacing.touchTarget), contentAlignment = Alignment.Center) {
        BottomSheetDefaults.DragHandle()
    }
}
