package se.pusslet.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import org.jetbrains.compose.resources.stringResource
import se.pusslet.ui.platform.AppPermission
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.settings_test_notification
import se.pusslet.ui.resources.settings_test_notification_blocked
import se.pusslet.ui.resources.settings_test_notification_open
import se.pusslet.ui.resources.settings_test_notification_sent
import se.pusslet.ui.theme.Spacing

/**
 * "Skicka testnotis" (ADR-065): a notification now, or what stops it. It asks for the permission first, and when the phone
 * says no the way forward is the phone's own settings: Android asks only once or twice, and then only answers no.
 */
@Composable
fun TestNotification(modifier: Modifier = Modifier) {
    val platform = LocalPlatformActions.current
    var shown by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val spoken = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedButton(
            onClick = {
                shown = null
                platform.requestPermission(AppPermission.Notifications) { granted ->
                    if (granted) platform.sendTestNotification { shown = it } else shown = false
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget),
        ) { Text(stringResource(Res.string.settings_test_notification)) }
        when (shown) {
            true -> Banner(stringResource(Res.string.settings_test_notification_sent), spoken, BannerKind.Info)
            false -> Banner(stringResource(Res.string.settings_test_notification_blocked), spoken, BannerKind.Problem, actions = {
                TextButton(onClick = platform::openNotificationSettings) {
                    Text(stringResource(Res.string.settings_test_notification_open))
                }
            })
            null -> Unit
        }
    }
}
