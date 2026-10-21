package se.pusslet.android.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import se.pusslet.android.MainActivity
import se.pusslet.android.notify.AndroidNotifier
import se.pusslet.core.notify.NotificationTarget
import se.pusslet.ui.theme.PussletDarkColorScheme
import se.pusslet.ui.theme.PussletLightColorScheme

/** The app's own colours on the home screen, light and dark with the system (ADR-039). */
private val WidgetColors = ColorProviders(light = PussletLightColorScheme, dark = PussletDarkColorScheme)

@Composable
internal fun PussletGlanceTheme(content: @Composable () -> Unit) = GlanceTheme(colors = WidgetColors, content = content)

/**
 * A widget's card, rounded like the app's cards, on the app's warm off-white (or its dark surface). Not the oat focus
 * colour: two widgets would make two focus surfaces on one screen (DESIGN.md).
 */
@Composable
internal fun GlanceModifier.widgetCard(): GlanceModifier =
    appWidgetBackground().background(GlanceTheme.colors.surface).cornerRadius(CARD_RADIUS)

/** The app's type scale, never under 14 sp (DESIGN.md). */
internal object WidgetText {
    @Composable
    fun heading(
        color: ColorProvider = GlanceTheme.colors.onSurface,
    ) = TextStyle(color = color, fontSize = 16.sp, fontWeight = FontWeight.Medium)

    @Composable
    fun line(color: ColorProvider = GlanceTheme.colors.onSurface) = TextStyle(color = color, fontSize = 15.sp)

    @Composable
    fun detail(color: ColorProvider = GlanceTheme.colors.onSurfaceVariant) = TextStyle(color = color, fontSize = 14.sp)
}

/** Opens the app where a tapped notification would ([MainActivity] reads the target, ADR-036). */
internal fun openApp(context: Context, target: NotificationTarget): Action = actionStartActivity(
    Intent(context, MainActivity::class.java)
        .setAction(AndroidNotifier.ACTION_OPEN)
        .putExtra(AndroidNotifier.EXTRA_TARGET, target.encode()),
)

private val CARD_RADIUS = 16.dp
