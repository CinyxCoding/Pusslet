package se.pusslet.feature.calendar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Icons only the calendar uses that are not in material-icons-core. Path data from Material Icons (Apache License 2.0). */
internal object CalendarIcons {
    /** The filter sheet ("Filtrera"). */
    val Filter: ImageVector by lazy {
        ImageVector.Builder(
            name = "FilterList",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes("M10,18h4v-2h-4v2zM3,6v2h18L21,6L3,6zM6,13h12v-2L6,11v2z"),
            fill = SolidColor(Color.Black),
        ).build()
    }
}
