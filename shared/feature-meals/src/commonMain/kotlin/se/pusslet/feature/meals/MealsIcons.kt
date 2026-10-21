package se.pusslet.feature.meals

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Icons only Mat uses that are not in material-icons-core. Path data from Material Icons (Apache License 2.0). */
internal object MealsIcons {
    /** The recipe's source page ("ica.se"). */
    val Link: ImageVector by lazy {
        icon(
            "Link",
            "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7c-1.71,0 -3.1,-1.39 -3.1,-3.1z" +
                "M8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z",
        )
    }

    /** "Hämtmat" (Material "Takeout Dining"). */
    val Takeout: ImageVector by lazy {
        icon(
            "Takeout",
            "M5.26,11h13.48l-0.67,9H5.93L5.26,11zM9.02,4h5.95L19,7.38l1.59,-1.59L22,7.21L19.21,10H4.79L2,7.21l1.41,-1.41L5,7.38L9.02,4z",
        )
    }

    /** "Ingen matlagning" (Material "No Meals"). */
    val NoCooking: ImageVector by lazy {
        icon(
            "NoCooking",
            "M16,14V6c0,-1.76 2.24,-4 5,-4v16.17l-2,-2V14H16zM20.49,23.31L10.02,12.85C9.69,12.94 9.36,13 9,13v9H7v-9" +
                "c-2.21,0 -4,-1.79 -4,-4V5.83L0.69,3.51L2.1,2.1l19.8,19.8L20.49,23.31zM6.17,9L5,7.83V9H6.17zM9,2H7v2.17l2,2V2z" +
                "M13,9V2h-2v6.17l1.85,1.85C12.94,9.69 13,9.36 13,9z",
        )
    }

    /** Fewer portions (−). */
    val Remove: ImageVector by lazy { icon("Remove", "M19,13H5v-2h14v2z") }

    private fun icon(name: String, path: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()
}
