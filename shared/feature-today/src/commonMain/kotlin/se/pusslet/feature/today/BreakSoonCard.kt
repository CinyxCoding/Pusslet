package se.pusslet.feature.today

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.breaks.SchoolBreak
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.format.relativeDay
import se.pusslet.ui.resources.*

/** "Höstlov från mån · 3 dagar utan plan · Anmäl lovomsorg senast fre" on Idag (ADR-051), with "Planera". */
@Composable
fun BreakSoonCard(b: SchoolBreak, today: LocalDate, onPlan: () -> Unit) {
    SectionCard(b.title, action = { TextButton(onClick = onPlan) { Text(stringResource(Res.string.breaks_plan)) } }) {
        val parts = buildList {
            add(
                if (b.start <= today) {
                    stringResource(Res.string.breaks_until, relativeDay(b.end, today))
                } else {
                    stringResource(Res.string.breaks_from, relativeDay(b.start, today))
                },
            )
            val open = b.unplanned.count { it >= today }
            if (open > 0) add(stringResource(Res.string.breaks_unplanned, open))
            val deadline = b.plan.deadline
            if (deadline != null && !b.plan.reported && deadline >= today) {
                add(stringResource(Res.string.breaks_book_by, relativeDay(deadline, today)))
            }
        }
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
    }
}
