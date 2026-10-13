package se.pusslet.ui.components

import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable

/**
 * "Valt" is sage `primaryContainer` everywhere (ADR-032). Havre (`secondaryContainer`, the Material default for these
 * components) is reserved for the one focus surface per screen.
 */
object SelectionColors {
    @Composable
    fun filterChip(): SelectableChipColors {
        val c = MaterialTheme.colorScheme
        return FilterChipDefaults.filterChipColors(
            selectedContainerColor = c.primaryContainer,
            selectedLabelColor = c.onPrimaryContainer,
            selectedLeadingIconColor = c.onPrimaryContainer,
            selectedTrailingIconColor = c.onPrimaryContainer,
        )
    }

    @Composable
    fun segmentedButton(): SegmentedButtonColors {
        val c = MaterialTheme.colorScheme
        return SegmentedButtonDefaults.colors(activeContainerColor = c.primaryContainer, activeContentColor = c.onPrimaryContainer)
    }

    @Composable
    fun navigationBarItem(): NavigationBarItemColors {
        val c = MaterialTheme.colorScheme
        return NavigationBarItemDefaults.colors(
            indicatorColor = c.primaryContainer,
            selectedIconColor = c.onPrimaryContainer,
            selectedTextColor = c.onSurface,
        )
    }
}
