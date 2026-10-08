package se.pusslet.feature.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.empty_today

@Composable
fun TodayScreen(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(message = stringResource(Res.string.empty_today))
    }
}
