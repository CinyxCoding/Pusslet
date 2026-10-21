package se.pusslet.feature.calendar.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.MemberColor
import se.pusslet.data.sources.SubscriptionError
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.ColorBadge
import se.pusslet.ui.components.PersonAvatar
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.relativeDay
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/**
 * Add or edit a subscription: Länk → "Hämta och visa" (count, next 3, or a friendly error; fills in the name) → Namn →
 * Vems kalender? → Färg. "Spara" is the one filled button, fixed at the bottom; "Ta bort" (with Ångra) sits last.
 */
@Composable
fun SubscriptionEditorScreen(
    editor: SubscriptionEditor,
    people: List<FamilyPerson>,
    today: LocalDate?,
    onBack: () -> Unit,
    onUrl: (String) -> Unit,
    onName: (String) -> Unit,
    onTogglePerson: (String) -> Unit,
    onColor: (MemberColor) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val draft = editor.draft
    Scaffold(
        topBar = { PussletTopBar(stringResource(if (editor.isNew) Res.string.subs_add else Res.string.subs_edit), onBack = onBack) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Button(
                    onClick = onSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm)
                        .heightIn(min = Spacing.primaryTouchTarget),
                ) { Text(stringResource(Res.string.action_save)) }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            OutlinedTextField(
                value = draft.url,
                onValueChange = onUrl,
                label = { Text(stringResource(Res.string.subs_url)) },
                isError = editor.urlProblem != null,
                supportingText = { Text(urlHelp(editor.urlProblem)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            val running = editor.test == FeedTest.Running
            OutlinedButton(
                onClick = onTest,
                enabled = !running && draft.url.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
            ) { Text(stringResource(if (running) Res.string.subs_testing else Res.string.subs_test)) }
            TestResult(editor.test, today)
            OutlinedTextField(
                value = draft.name,
                onValueChange = onName,
                label = { Text(stringResource(Res.string.subs_name)) },
                isError = editor.nameMissing,
                supportingText = if (editor.nameMissing) {
                    { Text(stringResource(Res.string.subs_name_required)) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            if (people.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    FieldHeading(stringResource(Res.string.subs_people))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        people.forEach { p ->
                            FilterChip(
                                selected = p.id in draft.personIds,
                                onClick = { onTogglePerson(p.id) },
                                label = { Text(p.name) },
                                leadingIcon = { PersonAvatar(p, size = 20.dp) },
                                colors = SelectionColors.filterChip(),
                            )
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                FieldHeading(stringResource(Res.string.subs_color))
                ColorChoice(draft.color, onColor)
            }
            // "Ta bort" last, like the other editors (UX plan Steg 2, Mer 3); undone with "Ångra" instead of a question.
            if (!editor.isNew) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg).heightIn(min = Spacing.primaryTouchTarget),
                ) { Text(stringResource(Res.string.action_delete)) }
            }
        }
    }
}

@Composable
private fun urlHelp(problem: UrlProblem?): String = stringResource(
    when (problem) {
        null, UrlProblem.Required -> Res.string.subs_url_hint
        UrlProblem.Invalid -> Res.string.subs_url_invalid
        UrlProblem.TooLong -> Res.string.subs_url_too_long
    },
)

/** "12 händelser" and the next three ("Imorgon · 17:30 · Träning"), or why the calendar could not be fetched. */
@Composable
private fun TestResult(test: FeedTest, today: LocalDate?) {
    when (test) {
        FeedTest.Idle, FeedTest.Running -> Unit
        is FeedTest.Failed -> Banner(stringResource(errorText(test.error)), kind = BannerKind.Warning)
        is FeedTest.Found -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            val count = test.preview.eventCount
            Text(
                if (count == 1) stringResource(Res.string.subs_count_one) else stringResource(Res.string.subs_count, count),
                style = MaterialTheme.typography.titleMedium,
            )
            if (test.preview.upcoming.isEmpty()) {
                Text(
                    stringResource(Res.string.subs_nothing_upcoming),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            test.preview.upcoming.forEach { o -> Text(upcomingLine(o, today), style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun upcomingLine(o: EventOccurrence, today: LocalDate?): String {
    val day = if (today != null) relativeDay(o.start.date, today) else formatDateShort(o.start.date)
    return listOfNotNull(day, if (o.allDay) null else formatTime(o.start.time), o.event.title).joinToString(" · ")
}

private fun errorText(error: SubscriptionError) = when (error) {
    SubscriptionError.InvalidUrl -> Res.string.subs_url_invalid
    SubscriptionError.Unreachable -> Res.string.subs_error_unreachable
    SubscriptionError.Http -> Res.string.subs_error_http
    SubscriptionError.NotCalendar -> Res.string.subs_error_not_calendar
}

@Composable
private fun FieldHeading(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The eight member colours as round badges with a ✓ on the chosen one; TalkBack reads "Färg 3". */
@Composable
private fun ColorChoice(selected: MemberColor, onPick: (MemberColor) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        MemberColor.entries.forEachIndexed { i, c ->
            Row(
                Modifier.selectable(selected = c == selected, role = Role.RadioButton) { onPick(c) }.heightIn(min = Spacing.touchTarget),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColorBadge(c, if (c == selected) "✓" else "", stringResource(Res.string.color_label, i + 1), size = 40.dp)
            }
        }
    }
}
