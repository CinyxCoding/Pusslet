package se.pusslet.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Icons that are not part of material-icons-core. Path data from Material Icons (Apache License 2.0).
 * Kept small on purpose instead of pulling in the multi-megabyte extended icon set.
 */
object PussletIcons {
    val Restaurant: ImageVector by lazy {
        icon(
            "Restaurant",
            "M11,9H9V2H7v7H5V2H3v7c0,2.12 1.66,3.84 3.75,3.97V22h2.5v-9.03C11.34,12.84 13,11.12 13,9V2h-2V9z" +
                "M16,6v8h2.5v8H21V2C18.24,2 16,4.24 16,6z",
        )
    }

    val CalendarMonth: ImageVector by lazy {
        icon(
            "CalendarMonth",
            "M19,4h-1V2h-2v2H8V2H6v2H5C3.89,4 3.01,4.9 3.01,6L3,20c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6C21,4.9 20.1,4 19,4z" +
                "M19,20H5V10h14V20zM9,14H7v-2h2V14zM13,14h-2v-2h2V14zM17,14h-2v-2h2V14zM9,18H7v-2h2V18z" +
                "M13,18h-2v-2h2V18zM17,18h-2v-2h2V18z",
        )
    }

    val Today: ImageVector by lazy {
        icon(
            "Today",
            "M19,3h-1V1h-2v2H8V1H6v2H5C3.89,3 3.01,3.9 3.01,5L3,19c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3z" +
                "M19,19H5V8h14V19zM7,10h5v5H7V10z",
        )
    }

    val Backpack: ImageVector by lazy {
        icon(
            "Backpack",
            "M20,8v12c0,1.1 -0.9,2 -2,2H6c-1.1,0 -2,-0.9 -2,-2V8c0,-1.86 1.28,-3.41 3,-3.86V2h3v2h4V2h3v2.14" +
                "C18.72,4.59 20,6.14 20,8zM6,12v2h10v2h2v-4H6z",
        )
    }

    val Umbrella: ImageVector by lazy {
        icon(
            "Umbrella",
            "M14.5,6.92L13,5.77V3.4c0,-0.26 0.22,-0.48 0.5,-0.48c0.28,0 0.5,0.21 0.5,0.48V4h2V3.4C16,2.07 14.88,1 13.5,1" +
                "C12.12,1 11,2.07 11,3.4v2.37L9.5,6.92L6,6.07l5.05,15.25C11.2,21.77 11.6,22 12,22s0.8,-0.23 0.95,-0.69L18,6.07" +
                "L14.5,6.92z",
        )
    }

    val Swap: ImageVector by lazy {
        icon("Swap", "M6.99,11L3,15l3.99,4v-3H14v-2H6.99V11zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z")
    }

    val QrCode: ImageVector by lazy {
        icon(
            "QrCode",
            "M3,11h8V3H3V11zM5,5h4v4H5V5z M3,21h8v-8H3V21zM5,15h4v4H5V15z M13,3v8h8V3H13zM19,9h-4V5h4V9z" +
                "M19,19h2v2h-2V19z M13,13h2v2h-2V13z M15,15h2v2h-2V15z M13,17h2v2h-2V17z M15,19h2v2h-2V19z" +
                "M17,17h2v2h-2V17z M17,13h2v2h-2V13z M19,15h2v2h-2V15z",
        )
    }

    val Barcode: ImageVector by lazy {
        icon(
            "Barcode",
            "M2,6h2v12H2V6zM5,6h1v12H5V6zM7,6h3v12H7V6zM11,6h1v12h-1V6zM14,6h2v12h-2V6zM17,6h1v12h-1V6zM19,6h3v12h-3V6z",
        )
    }

    val Task: ImageVector by lazy {
        icon(
            "Task",
            "M19,3h-4.18C14.4,1.84 13.3,1 12,1S9.6,1.84 9.18,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5" +
                "C21,3.9 20.1,3 19,3zM12,3c0.55,0 1,0.45 1,1s-0.45,1 -1,1s-1,-0.45 -1,-1S11.45,3 12,3zM10,17l-4,-4l1.41,-1.41" +
                "L10,14.17l6.59,-6.59L18,9L10,17z",
        )
    }

    val Family: ImageVector by lazy {
        icon(
            "Family",
            "M16,4c0,-1.11 0.89,-2 2,-2s2,0.89 2,2s-0.89,2 -2,2S16,5.11 16,4zM20,22v-6h2.5l-2.54,-7.63C19.68,7.55 18.92,7 18.06,7" +
                "h-0.12c-0.86,0 -1.63,0.55 -1.9,1.37l-0.86,2.58C16.26,11.55 17,12.68 17,14v8H20zM12.5,11.5c0.83,0 1.5,-0.67 1.5,-1.5" +
                "s-0.67,-1.5 -1.5,-1.5S11,9.17 11,10S11.67,11.5 12.5,11.5zM5.5,6c1.11,0 2,-0.89 2,-2s-0.89,-2 -2,-2s-2,0.89 -2,2" +
                "S4.39,6 5.5,6zM7.5,22v-7H9V9c0,-1.1 -0.9,-2 -2,-2H4C2.9,7 2,7.9 2,9v6h1.5v7H7.5z" +
                "M14,22v-4h1v-4c0,-0.82 -0.68,-1.5 -1.5,-1.5h-2c-0.82,0 -1.5,0.68 -1.5,1.5v4h1v4H14z",
        )
    }

    private fun icon(name: String, path: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()
}
