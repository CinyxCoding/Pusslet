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

    /** Idag in the bottom bar (Material "WbSunny"). */
    val Sun: ImageVector by lazy {
        icon(
            "Sun",
            "M6.76,4.84l-1.8,-1.79 -1.41,1.41 1.79,1.79 1.42,-1.41zM4,10.5L1,10.5v2h3v-2zM13,0.55h-2L11,3.5h2L13,0.55z" +
                "M20.45,4.46l-1.41,-1.41 -1.79,1.79 1.41,1.41 1.79,-1.79zM17.24,18.16l1.79,1.8 1.41,-1.41 -1.8,-1.79 -1.4,1.4z" +
                "M20,10.5v2h3v-2h-3zM12,5.5c-3.31,0 -6,2.69 -6,6s2.69,6 6,6 6,-2.69 6,-6 -2.69,-6 -6,-6z" +
                "M11,22.45h2L13,19.5h-2v2.95zM3.55,18.54l1.41,1.41 1.79,-1.8 -1.41,-1.41 -1.79,1.8z",
        )
    }

    /** Mer in the bottom bar. */
    val MoreHoriz: ImageVector by lazy {
        icon(
            "MoreHoriz",
            "M6,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM18,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z" +
                "M12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z",
        )
    }

    /** Party reminders (Material "CardGiftcard"). */
    val Gift: ImageVector by lazy {
        icon(
            "Gift",
            "M20,6h-2.18c0.11,-0.31 0.18,-0.65 0.18,-1 0,-1.66 -1.34,-3 -3,-3 -1.05,0 -1.96,0.54 -2.5,1.35l-0.5,0.67 -0.5,-0.68" +
                "C10.96,2.54 10.05,2 9,2 7.34,2 6,3.34 6,5c0,0.35 0.07,0.69 0.18,1L4,6c-1.11,0 -1.99,0.89 -1.99,2L2,19" +
                "c0,1.11 0.89,2 2,2h16c1.11,0 2,-0.89 2,-2L22,8c0,-1.11 -0.89,-2 -2,-2z" +
                "M15,4c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1z" +
                "M9,4c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1z" +
                "M20,19L4,19v-2h16v2zM20,14L4,14L4,8h5.08L7,10.83 8.62,12 11,8.76l1,-1.36 1,1.36L15.38,12 17,10.83 14.92,8L20,8v6z",
        )
    }

    /** Study days and school breaks. */
    val School: ImageVector by lazy {
        icon("School", "M5,13.18v4L12,21l7,-3.82v-4L12,17l-7,-3.82zM12,3L1,9l11,6 9,-4.91V17h2V9L12,3z")
    }

    /** Problems (the filled attention level). */
    val PriorityHigh: ImageVector by lazy {
        icon("PriorityHigh", "M12,19m-2,0a2,2 0,1 1,4 0a2,2 0,1 1,-4 0zM10,3h4v12h-4z")
    }

    /** Offline or stale data. */
    val CloudOff: ImageVector by lazy {
        icon(
            "CloudOff",
            "M19.35,10.04C18.67,6.59 15.64,4 12,4c-1.48,0 -2.85,0.43 -4.01,1.17l1.46,1.46C10.21,6.23 11.08,6 12,6" +
                "c3.04,0 5.5,2.46 5.5,5.5v0.5H19c1.66,0 3,1.34 3,3 0,1.13 -0.64,2.11 -1.56,2.62l1.45,1.45" +
                "C23.16,18.16 24,16.68 24,15c0,-2.64 -2.05,-4.78 -4.65,-4.96z" +
                "M3,5.27l2.75,2.74C2.56,8.15 0,10.77 0,14c0,3.31 2.69,6 6,6h11.73l2,2L21,20.73 4.27,4 3,5.27z" +
                "M7.73,10l8,8H6c-2.21,0 -4,-1.79 -4,-4s1.79,-4 4,-4h1.73z",
        )
    }

    /** First-time hints. */
    val Lightbulb: ImageVector by lazy {
        icon(
            "Lightbulb",
            "M9,21c0,0.55 0.45,1 1,1h4c0.55,0 1,-0.45 1,-1v-1H9V21z" +
                "M12,2C8.14,2 5,5.14 5,9c0,2.38 1.19,4.47 3,5.74V17c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1v-2.26" +
                "c1.81,-1.27 3,-3.36 3,-5.74C19,5.14 15.86,2 12,2z",
        )
    }

    /** Trailing chevron on rows that open something (›). */
    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z")
    }

    /** "Färre stjärnor" (−). */
    val Remove: ImageVector by lazy { icon("Remove", "M19,13H5v-2h14v2z") }

    /** Medicin och feber (ADR-050). */
    val Medication: ImageVector by lazy {
        icon(
            "Medication",
            "M6,3h12v2H6V3zM17,6H7C5.9,6 5,6.9 5,8v11c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V8C19,6.9 18.1,6 17,6z" +
                "M15.5,15.5h-2v2h-3v-2h-2v-3h2v-2h3v2h2V15.5z",
        )
    }

    /** Delade kostnader. */
    val Payments: ImageVector by lazy {
        icon(
            "Payments",
            "M19,14V6c0,-1.1 -0.9,-2 -2,-2H3c-1.1,0 -2,0.9 -2,2v8c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2z" +
                "M10,13c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3zM23,7v11c0,1.1 -0.9,2 -2,2H4v-2h17V7h2z",
        )
    }

    /** Lov och vistelsetider. */
    val Beach: ImageVector by lazy {
        icon(
            "Beach",
            "M13.13,14.56l1.43,-1.43 6.44,6.44L19.57,21zM17.42,8.83l2.86,-2.86c-3.95,-3.95 -10.35,-3.96 -14.3,-0.02 " +
                "3.93,-1.3 8.31,-0.25 11.44,2.88zM5.95,5.98c-3.94,3.95 -3.93,10.35 0.02,14.3l2.86,-2.86C5.7,14.29 4.65,9.91 5.95,5.98z" +
                "M5.97,5.96l-0.01,0.01c-0.38,3.01 1.17,6.88 4.3,10.02l5.73,-5.73c-3.13,-3.13 -7.01,-4.68 -10.02,-4.3z",
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
