package se.pusslet.feature.today

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.today.ContextCard
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.format.relativeDay
import se.pusslet.ui.resources.*

/** "Dags att OSA till Kalas hos Vera – senast imorgon" on Idag (ADR-054); "Öppna" leads to the party. */
@Composable
fun RsvpCard(card: ContextCard.PartyRsvp, today: LocalDate, onOpen: () -> Unit) {
    SectionCard(null, action = { TextButton(onClick = onOpen) { Text(stringResource(Res.string.card_action_open)) } }) {
        Text(
            stringResource(Res.string.card_party_rsvp, card.occurrence.event.title, relativeDay(card.by, today).lowercase()),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
