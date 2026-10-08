package se.pusslet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.MemberColor
import se.pusslet.ui.theme.PussletThemeExtras
import se.pusslet.ui.theme.Spacing

/** Colour + initial (or emoji): colour is never the only signal. */
@Composable
fun PersonAvatar(person: FamilyPerson, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    ColorBadge(person.color, person.initial, person.name, modifier, size)
}

@Composable
fun ColorBadge(color: MemberColor, text: String, description: String, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val colors = PussletThemeExtras.colors.member(color)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.container)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        // Sized from the badge, not the user's font scale: the letter must always fit inside the circle
        // (the name next to it is what grows with large text).
        val fontSize = with(LocalDensity.current) { (size * BADGE_TEXT_RATIO).toSp() }
        Text(
            text,
            color = colors.content,
            fontWeight = FontWeight.SemiBold,
            fontSize = fontSize,
            lineHeight = fontSize,
            maxLines = 1,
        )
    }
}

/** Overlapping avatars for "Ella och Simon". */
@Composable
fun AvatarStack(people: List<FamilyPerson>, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
        people.take(4).forEach { p ->
            PersonAvatar(
                p,
                Modifier.border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
                size,
            )
        }
    }
}

/** Filter/selection chips for people ("Alla · Sara · Ella …"). */
@Composable
fun PersonChips(
    people: List<FamilyPerson>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    allLabel: String? = null,
    onAll: (() -> Unit)? = null,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (allLabel != null && onAll != null) {
            FilterChip(selected = selected.isEmpty(), onClick = onAll, label = { Text(allLabel) })
        }
        people.forEach { p ->
            FilterChip(
                selected = p.id in selected,
                onClick = { onToggle(p.id) },
                label = { Text(p.name) },
                leadingIcon = { PersonAvatar(p, size = 20.dp) },
            )
        }
    }
}

private const val BADGE_TEXT_RATIO = 0.45f
