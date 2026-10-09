package se.pusslet.android.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.notify.PlannedNotification
import se.pusslet.core.time.isoWeek
import se.pusslet.core.today.TimelineItem
import se.pusslet.core.today.TodayPlan
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.usecase.NotificationScheduler
import se.pusslet.data.usecase.TodayPlans
import se.pusslet.ui.components.Labels
import se.pusslet.ui.format.joinNamesText
import se.pusslet.ui.resources.*
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

private val planJson = Json { ignoreUnknownKeys = true }

/**
 * Planned reminders as one-time WorkManager jobs (no exact alarms needed, Doze may delay a few minutes).
 * Each job is tagged with its time, so re-planning only touches what actually moved.
 */
class WorkManagerNotificationScheduler(private val context: Context, private val clock: Clock) : NotificationScheduler {
    override suspend fun replaceAll(plan: List<PlannedNotification>) {
        val work = WorkManager.getInstance(context)
        val existing = work.getWorkInfosByTagFlow(TAG).first()
            .filter { it.state == WorkInfo.State.ENQUEUED }
            .associate { info -> info.tags.firstOrNull { it.startsWith(KEY_TAG) }.orEmpty().removePrefix(KEY_TAG) to info.tags }
        val wanted = plan.associateBy { it.key }
        (existing.keys - wanted.keys).forEach { work.cancelUniqueWork(uniqueName(it)) }
        for (p in plan) {
            val atTag = "$AT_TAG${p.at.toEpochMilliseconds()}"
            if (existing[p.key]?.contains(atTag) == true) continue
            val delay = (p.at - clock.now()).coerceAtLeast(0.milliseconds)
            val request = OneTimeWorkRequestBuilder<NotificationWorker>()
                .setInitialDelay(delay.toJavaDuration())
                .setInputData(
                    Data.Builder().putString(NotificationWorker.PLAN, planJson.encodeToString(PlannedNotification.serializer(), p)).build(),
                )
                .addTag(TAG)
                .addTag("$KEY_TAG${p.key}")
                .addTag(atTag)
                .build()
            work.enqueueUniqueWork(uniqueName(p.key), ExistingWorkPolicy.REPLACE, request)
        }
    }

    private fun uniqueName(key: String) = "notify:$key"

    private companion object {
        const val TAG = "pusslet-notification"
        const val KEY_TAG = "key:"
        const val AT_TAG = "at:"
    }
}

/** Builds the text when the reminder fires (the event may have been renamed since it was planned). */
class NotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val family: FamilyRepository by inject()
    private val calendar: CalendarRepository by inject()
    private val todayPlans: TodayPlans by inject()
    private val zone: TimeZone by inject()
    private val clock: Clock by inject()

    override suspend fun doWork(): Result {
        val raw = inputData.getString(PLAN) ?: return Result.success()
        val plan = runCatching { planJson.decodeFromString(PlannedNotification.serializer(), raw) }.getOrNull() ?: return Result.success()
        val (channel, content) = when (plan) {
            is PlannedNotification.EventReminder -> Channel.Reminders to NotificationContent(
                title = plan.title,
                text = getString(Res.string.notify_event_starts, formatTime(plan.start.hour, plan.start.minute)),
                eventId = plan.eventId,
                occurrenceDate = plan.occurrenceDate,
            )
            is PlannedNotification.Handoff -> Channel.Handoffs to handoff(plan)
            is PlannedNotification.TaskDue -> Channel.Reminders to NotificationContent(plan.title, getString(Res.string.notify_task_text))
            is PlannedNotification.Morning -> Channel.Morning to morning(plan.date)
        }
        Notifications.show(applicationContext, channel, plan.key.hashCode(), content)
        return Result.success()
    }

    private suspend fun handoff(p: PlannedNotification.Handoff): NotificationContent {
        val people = family.people.first().associateBy { it.id }
        val names = p.childIds.mapNotNull { people[it]?.name }
        val joined = joinNamesText(names)
        val title = when {
            names.isEmpty() && p.kind == HandoffKind.PickUp -> getString(Res.string.notify_pickup_title_plain)
            names.isEmpty() -> getString(Res.string.notify_dropoff_title_plain)
            p.kind == HandoffKind.PickUp -> getString(Res.string.notify_pickup_title, joined)
            else -> getString(Res.string.notify_dropoff_title, joined)
        }
        val event = calendar.event(p.eventId)?.title ?: title
        return NotificationContent(
            title = title,
            text = getString(Res.string.notify_handoff_text, event, formatTime(p.time.hour, p.time.minute)),
            eventId = p.eventId,
            occurrenceDate = p.occurrenceDate,
        )
    }

    /** "Morgonkollen": the day in five lines, built from the same plan as the Today screen. */
    private suspend fun morning(date: LocalDate): NotificationContent {
        val now = clock.now().toLocalDateTime(zone)
        val plan = todayPlans.plan(date, now.time)
        val weekday = getStringArray(Res.array.weekdays)[date.dayOfWeek.isoDayNumber - 1].replaceFirstChar { it.uppercase() }
        val title = getString(Res.string.notify_morning_title, weekday, date.isoWeek.week)
        val lines = morningLines(plan)
        return NotificationContent(title, lines.firstOrNull() ?: getString(Res.string.notify_morning_quiet), lines)
    }

    private suspend fun morningLines(plan: TodayPlan): List<String> = buildList {
        plan.weather?.let { w ->
            val advice = plan.clothing.flatMap { it.advice }.firstOrNull()?.let { getString(Labels.advice(it)) }.orEmpty()
            add(getString(Res.string.notify_morning_weather, w.minTemperature.roundToInt(), w.maxTemperature.roundToInt(), advice).trim())
        }
        val timed = plan.timeline.filter { it.time != null }
        timed.take(MAX_EVENTS).forEach { item ->
            val time = item.time?.let { formatTime(it.hour, it.minute) }.orEmpty()
            val text = when (item) {
                is TimelineItem.EventItem -> item.occurrence.event.title
                is TimelineItem.HandoffItem -> {
                    val who = item.person?.name.orEmpty()
                    val kids = joinNamesText(item.children.map { it.name })
                    getString(if (item.kind == HandoffKind.PickUp) Res.string.today_pickup else Res.string.today_dropoff) +
                        " $kids" + if (who.isNotEmpty()) " – $who" else ""
                }
            }
            add(getString(Res.string.notify_morning_event, time, text))
        }
        if (timed.size > MAX_EVENTS) add(getString(Res.string.notify_morning_more, timed.size - MAX_EVENTS))
        val packing = plan.packing.flatMap { p ->
            p.items.filter { !it.checked }.map { getString(Res.string.notify_item_child, it.text, p.child.name) }
        }
        if (packing.isNotEmpty()) add(getString(Res.string.notify_morning_packing, joinNamesText(packing)))
        plan.dinner?.let { add(getString(Res.string.notify_morning_dinner, it.title)) }
        if (plan.myTasks.isNotEmpty()) add(getString(Res.string.notify_morning_tasks, plan.myTasks.size))
    }

    private fun formatTime(h: Int, m: Int) = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"

    companion object {
        const val PLAN = "plan"
        private const val MAX_EVENTS = 3
    }
}
