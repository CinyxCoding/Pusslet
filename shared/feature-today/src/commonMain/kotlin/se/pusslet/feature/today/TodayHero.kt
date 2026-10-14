package se.pusslet.feature.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.PackingItem
import se.pusslet.core.model.PackingReason
import se.pusslet.core.today.HandoffLine
import se.pusslet.core.today.HandoffStop
import se.pusslet.core.today.LunchGroup
import se.pusslet.core.today.TodayPacking
import se.pusslet.core.today.TodayPlan
import se.pusslet.core.weather.ClothingGrouping
import se.pusslet.ui.components.CheckRow
import se.pusslet.ui.components.HeroCard
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.SentenceRow
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.list_separator
import se.pusslet.ui.resources.state_collapsed
import se.pusslet.ui.resources.state_expanded
import se.pusslet.ui.resources.today_clash
import se.pusslet.ui.resources.today_clothes_for
import se.pusslet.ui.resources.today_dinner_tonight
import se.pusslet.ui.resources.today_handoff_stop
import se.pusslet.ui.resources.today_item_for
import se.pusslet.ui.resources.today_lunch
import se.pusslet.ui.resources.today_nobody_dropoff
import se.pusslet.ui.resources.today_nobody_pickup
import se.pusslet.ui.resources.today_packing_done
import se.pusslet.ui.resources.today_packing_line
import se.pusslet.ui.resources.today_packing_reason_event
import se.pusslet.ui.resources.today_packing_reason_handover
import se.pusslet.ui.resources.today_person_dropoff
import se.pusslet.ui.resources.today_person_pickup
import se.pusslet.ui.resources.today_plan_dinner
import se.pusslet.ui.resources.today_weather_missing
import se.pusslet.ui.resources.today_weather_stale
import se.pusslet.ui.resources.today_weather_temp
import se.pusslet.ui.resources.today_weather_tomorrow
import se.pusslet.ui.resources.today_weather_unavailable
import se.pusslet.ui.resources.today_you_dropoff
import se.pusslet.ui.resources.today_you_pickup
import se.pusslet.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * Morgonkollen: the one focus surface of Idag, without a visible title (UX plan, Idag). The rows are decided by the view
 * model ([TodayUiState.hero]); this only draws them.
 */
@Composable
internal fun MorningCheck(state: TodayUiState, plan: TodayPlan, actions: TodayActions) {
    HeroCard {
        state.hero.forEach { row ->
            when (row) {
                is HeroRow.Handoff -> HandoffRow(row.line, plan.now, actions)
                is HeroRow.Packing -> PackingRow(row.packing, plan.date, actions.onTogglePacking)
                is HeroRow.Dinner -> DinnerRow(row) { actions.onPlanDinner(plan.date) }
                HeroRow.PlanDinner -> SentenceRow(
                    stringResource(Res.string.today_plan_dinner),
                    onClick = { actions.onPlanDinner(plan.date) },
                )
                HeroRow.Weather -> WeatherRow(state, plan)
                HeroRow.ChoosePlace -> SentenceRow(
                    stringResource(Res.string.today_weather_missing),
                    leadingIcon = Icons.Filled.Place,
                    onClick = actions.onOpenSettings,
                )
            }
        }
    }
}

/** "Johan lämnar Simon 07:50 och Ella 08:05", or "Du hämtar …" in bold. A tap opens the next stop's event. */
@Composable
private fun HandoffRow(line: HandoffLine, now: LocalTime, actions: TodayActions) {
    val next = line.stops.firstOrNull { it.time >= now } ?: line.stops.first()
    SentenceRow(
        handoffSentence(line.kind, line.person, line.isMe, stopsText(line.stops)),
        emphasized = line.isMe,
        onClick = { actions.onOpenOccurrence(next.occurrence) },
    )
}

/**
 * One hand-off as a sentence, person first: "Johan lämnar [what]", "Du hämtar [what]"; "Lämning [what]" when the person has
 * left the family.
 */
@Composable
internal fun handoffSentence(kind: HandoffKind, person: FamilyPerson?, isMe: Boolean, what: String): String {
    val pickUp = kind == HandoffKind.PickUp
    return when {
        isMe -> stringResource(if (pickUp) Res.string.today_you_pickup else Res.string.today_you_dropoff, what)
        person != null -> stringResource(if (pickUp) Res.string.today_person_pickup else Res.string.today_person_dropoff, person.name, what)
        else -> stringResource(if (pickUp) Res.string.today_nobody_pickup else Res.string.today_nobody_dropoff, what)
    }
}

/** "Simon 07:50 och Ella 08:05"; children at the same time share it ("Simon och Ella 07:50"). */
@Composable
private fun stopsText(stops: List<HandoffStop>): String {
    val groups = ArrayList<Pair<LocalTime, MutableList<String>>>()
    stops.forEach { stop ->
        val sameTime = groups.lastOrNull()?.takeIf { it.first == stop.time }
        if (sameTime != null) sameTime.second += stop.child.name else groups += stop.time to mutableListOf(stop.child.name)
    }
    return joinNames(groups.map { (time, names) -> stringResource(Res.string.today_handoff_stop, joinNames(names), formatTime(time)) })
}

/** "Ska med: gympapåse (Ella)" or "Allt är packat"; a tap unfolds the check boxes per child in place. */
@Composable
private fun PackingRow(packing: TodayPacking, date: LocalDate, onToggle: (LocalDate, PackingItem, Boolean) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val summary = if (packing.allPacked) {
        stringResource(Res.string.today_packing_done)
    } else {
        val separator = stringResource(Res.string.list_separator)
        val perChild = packing.children.mapNotNull { cp ->
            val left = cp.items.filter { !it.checked }
            if (left.isEmpty()) {
                null
            } else {
                val items = left.joinToString(separator) { item -> item.text.trim().replaceFirstChar { it.lowercase() } }
                stringResource(Res.string.today_item_for, items, cp.child.name)
            }
        }
        stringResource(Res.string.today_packing_line, perChild.joinToString(SEPARATOR))
    }
    val stateText = stringResource(if (expanded) Res.string.state_expanded else Res.string.state_collapsed)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .clickable(role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = stateText }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (expanded) {
        packing.children.forEach { cp ->
            Text(cp.child.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.xs))
            cp.items.forEach { item ->
                val reason = when (val r = item.reason) {
                    is PackingReason.ForEvent -> stringResource(Res.string.today_packing_reason_event, r.eventTitle)
                    PackingReason.Handover -> stringResource(Res.string.today_packing_reason_handover)
                    is PackingReason.Rule -> null
                }
                CheckRow(item.text, item.checked, onCheckedChange = { onToggle(date, item, it) }, supporting = reason)
            }
        }
    }
}

/** "Ikväll: Fiskgratäng" with at most one line under it: a clash with a school lunch wins over the lunch itself. */
@Composable
private fun DinnerRow(row: HeroRow.Dinner, onOpen: () -> Unit) {
    val clash = row.lunches.firstOrNull { it.clash.isNotEmpty() }
    SentenceRow(
        stringResource(Res.string.today_dinner_tonight, row.dinner.title),
        supporting = if (clash == null && row.lunches.isNotEmpty()) lunchText(row.lunches) else null,
        onClick = onOpen,
    )
    if (clash != null) {
        val text = stringResource(Res.string.today_clash, joinNames(clash.children.map { it.name }), clash.clash.joinToString(", "))
        SubLine(text, PussletIcons.Restaurant)
    }
}

/** "Lunch: Köttbullar (Ella, Simon)"; children at another school follow after " · ". */
@Composable
private fun lunchText(lunches: List<LunchGroup>): String {
    val separator = stringResource(Res.string.list_separator)
    val first = lunches.first()
    val rest = lunches.drop(1).map {
        stringResource(Res.string.today_item_for, it.dish, it.children.joinToString(separator) { c -> c.name })
    }
    val head = stringResource(Res.string.today_lunch, first.children.joinToString(separator) { it.name }, first.dish)
    return (listOf(head) + rest).joinToString(SEPARATOR)
}

/** The quiet line under a row, with a terracotta icon: a reminder or an uncertain match, never a problem. */
@Composable
private fun SubLine(text: String, icon: ImageVector) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * "☁ 14° · Ella och Simon: tunn jacka"; from 18:00 tomorrow's: "I morgon 2–4° · …" (ADR-022). The weather word is the
 * emoji's TalkBack description, not text on the screen.
 */
@Composable
private fun WeatherRow(state: TodayUiState, plan: TodayPlan) {
    val summary = plan.weather
    val ready = state.weather as? WeatherState.Ready
    if (summary == null || ready == null) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(PussletIcons.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(Res.string.today_weather_unavailable), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    val condition = stringResource(Labels.weather(summary.condition))
    val temperature = if (plan.weatherForTomorrow) {
        stringResource(Res.string.today_weather_tomorrow, summary.minTemperature.roundToInt(), summary.maxTemperature.roundToInt())
    } else {
        stringResource(Res.string.today_weather_temp, (summary.now?.temperature ?: summary.maxTemperature).roundToInt())
    }
    val groups = remember(plan.clothing) { ClothingGrouping.group(plan.clothing) }
    val clothes = groups.map { group ->
        val advice = group.advice.map { stringResource(Labels.advice(it)) }.joinToString(SEPARATOR)
        stringResource(Res.string.today_clothes_for, joinNames(group.children.map { it.name }), advice)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            Labels.weatherEmoji(summary.condition),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.clearAndSetSemantics { contentDescription = condition },
        )
        Column(Modifier.weight(1f)) {
            Text((listOf(temperature) + clothes).joinToString(SEPARATOR), style = MaterialTheme.typography.bodyLarge)
            if (ready.stale) {
                Text(
                    stringResource(Res.string.today_weather_stale, formatTime(ready.fetchedAt.time)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
