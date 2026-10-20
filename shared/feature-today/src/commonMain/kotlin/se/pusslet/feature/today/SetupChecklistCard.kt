package se.pusslet.feature.today

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.today.SetupItem
import se.pusslet.core.today.SetupProgress
import se.pusslet.ui.components.HeroCard
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.SentenceRow
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.action_show_all
import se.pusslet.ui.resources.name_possessive
import se.pusslet.ui.resources.name_possessive_after_ending
import se.pusslet.ui.resources.name_possessive_endings
import se.pusslet.ui.resources.today_setup_activity
import se.pusslet.ui.resources.today_setup_dinners
import se.pusslet.ui.resources.today_setup_done_a11y
import se.pusslet.ui.resources.today_setup_handoffs
import se.pusslet.ui.resources.today_setup_hide
import se.pusslet.ui.resources.today_setup_invite
import se.pusslet.ui.resources.today_setup_preschool
import se.pusslet.ui.resources.today_setup_progress
import se.pusslet.ui.resources.today_setup_school
import se.pusslet.ui.resources.today_setup_shopping
import se.pusslet.ui.resources.today_setup_show_fewer
import se.pusslet.ui.resources.today_setup_title
import se.pusslet.ui.theme.Spacing

/**
 * "Kom igång" (UX plan 4.4): the steps a new family has left, ticked off by the data itself. At most three open steps are
 * shown; "Visa alla" adds the rest, the done ones with a check. While it shows it is Idag's focus surface, so Morgonkollen
 * is drawn flat under it. Each open row opens the place where the step is done ([onOpen]); "Dölj" hides it on this phone.
 */
@Composable
fun SetupChecklistCard(progress: SetupProgress, onOpen: (SetupItem) -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val rows = if (showAll) progress.items else progress.next
    HeroCard(modifier) {
        Text(
            stringResource(Res.string.today_setup_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(Res.string.today_setup_progress, progress.done, progress.total),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEach { item -> SetupRow(item, onOpen) }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
            if (progress.hasMore) {
                TextButton(onClick = { showAll = !showAll }) {
                    Text(stringResource(if (showAll) Res.string.today_setup_show_fewer else Res.string.action_show_all))
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onHide) { Text(stringResource(Res.string.today_setup_hide)) }
        }
    }
}

/** An open step opens its place; a done one is a quiet line with a check ("Klart" for TalkBack). */
@Composable
private fun SetupRow(item: SetupItem, onOpen: (SetupItem) -> Unit) {
    val text = setupText(item)
    if (item.done) {
        val done = stringResource(Res.string.today_setup_done_a11y)
        SentenceRow(text, modifier = Modifier.semantics { stateDescription = done }, leadingIcon = Icons.Filled.CheckCircle)
    } else {
        SentenceRow(text, leadingIcon = setupIcon(item), onClick = { onOpen(item) })
    }
}

/** At most four words, with the family's own names: "Lägg till Ellas skola", "Bjud in Johan". */
@Composable
private fun setupText(item: SetupItem): String = when (item) {
    is SetupItem.School -> stringResource(
        if (item.preschool) Res.string.today_setup_preschool else Res.string.today_setup_school,
        possessive(item.child.firstName),
    )
    is SetupItem.Handoffs -> stringResource(Res.string.today_setup_handoffs)
    is SetupItem.Invite -> stringResource(Res.string.today_setup_invite, item.member.displayName.trim())
    is SetupItem.Activity -> stringResource(Res.string.today_setup_activity)
    is SetupItem.Dinners -> stringResource(Res.string.today_setup_dinners)
    is SetupItem.Shopping -> stringResource(Res.string.today_setup_shopping)
}

private fun setupIcon(item: SetupItem): ImageVector = when (item) {
    is SetupItem.School -> PussletIcons.School
    is SetupItem.Handoffs -> PussletIcons.Swap
    is SetupItem.Invite -> Icons.Filled.Person
    is SetupItem.Activity -> PussletIcons.CalendarMonth
    is SetupItem.Dinners -> PussletIcons.Restaurant
    is SetupItem.Shopping -> Icons.Filled.ShoppingCart
}

/**
 * A name's possessive in the app's language: "Ellas" and "Ella's", but "Måns" and "Felix" in Swedish and "James'" in English.
 * The endings that take no extra s are a string, so each language keeps its own rule.
 */
@Composable
internal fun possessive(name: String): String {
    val trimmed = name.trim()
    val endings = stringResource(Res.string.name_possessive_endings)
    val bare = trimmed.lastOrNull()?.lowercaseChar()?.let { it in endings } == true
    return stringResource(if (bare) Res.string.name_possessive_after_ending else Res.string.name_possessive, trimmed)
}

/** Where a tap on an open step of "Kom igång" goes. Done steps are not tappable. */
internal fun TodayActions.openSetup(item: SetupItem, today: LocalDate) {
    when (item) {
        is SetupItem.School -> onOpenChild(item.child.id)
        is SetupItem.Handoffs -> item.next?.let(onOpenOccurrence)
        is SetupItem.Invite -> onInvite(item.member.id)
        is SetupItem.Activity -> onQuickAdd()
        is SetupItem.Dinners -> onPlanDinner(today)
        is SetupItem.Shopping -> onOpenShopping()
    }
}
