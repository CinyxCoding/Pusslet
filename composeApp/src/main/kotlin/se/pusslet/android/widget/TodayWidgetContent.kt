package se.pusslet.android.widget

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.getString
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.MemberColor
import se.pusslet.core.time.isoWeek
import se.pusslet.core.today.TodayGlance
import se.pusslet.core.today.TodayGlancer
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PackingRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.TodayPlans
import se.pusslet.ui.format.dateLongText
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.joinNamesText
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.notify_item_child
import se.pusslet.ui.resources.today_dropoff
import se.pusslet.ui.resources.today_handoff_nobody
import se.pusslet.ui.resources.today_pickup
import se.pusslet.ui.resources.tomorrow
import se.pusslet.ui.resources.week_short
import se.pusslet.ui.resources.widget_all_day
import se.pusslet.ui.resources.widget_bring
import se.pusslet.ui.resources.widget_handoff
import se.pusslet.ui.resources.widget_handoff_plain
import se.pusslet.ui.resources.widget_more_today
import se.pusslet.ui.resources.widget_not_set_up
import se.pusslet.ui.resources.widget_nothing_more
import se.pusslet.ui.resources.widget_now
import se.pusslet.ui.resources.widget_quiet
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** One line of the Today widget: "16:30 · Hämtning Simon – Sara". */
data class WidgetLine(
    val label: String,
    val text: String,
    val colors: List<MemberColor> = emptyList(),
    /** Something nobody has taken yet ("Ingen är satt än"). */
    val warning: Boolean = false,
    /** Tapping the line opens this occurrence. */
    val eventId: String? = null,
    val date: LocalDate? = null,
)

/** Display-ready content: every string is resolved before Glance composes (it cannot read compose resources). */
data class TodayWidgetUi(
    val title: String,
    val week: String = "",
    /** Shown above the lines: not set up yet, a quiet day, or nothing more today. */
    val message: String? = null,
    val lines: List<WidgetLine> = emptyList(),
    val setUp: Boolean = true,
)

data class TodayWidgetState(val ui: TodayWidgetUi, val refreshAt: Instant?)

/** Builds the Today widget from the same plan as the Today screen (without the weather: a widget never waits on the network). */
class TodayWidgetContent(
    private val plans: TodayPlans,
    private val calendar: CalendarRepository,
    private val packing: PackingRepository,
    private val family: FamilyRepository,
    private val sessions: SessionProvider,
    private val clock: Clock,
    private val zone: TimeZone,
) {
    /**
     * New content whenever the family's data changes, and every minute, while Glance keeps the widget's session open.
     * (An update during an open session only recomposes, so the content has to come from a flow.)
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun updates(): Flow<TodayWidgetState> =
        combine(calendar.events, packing.rules, packing.checks, family.people, everyMinute()) { _, _, _, _, _ -> }
            .mapLatest { load() }
            .distinctUntilChanged()

    suspend fun load(): TodayWidgetState {
        sessions.loaded.first { it }
        if (sessions.session.value.familyId == null) {
            return TodayWidgetState(TodayWidgetUi(title = getString(Res.string.widget_not_set_up), setUp = false), refreshAt = null)
        }
        val now = clock.now().toLocalDateTime(zone)
        val glance = TodayGlancer.from(plans.plan(now.date, now.time, withWeather = false), now.time)
        val ui = TodayWidgetUi(
            title = dateLongText(now.date),
            week = getString(Res.string.week_short, now.date.isoWeek.week),
            message = message(glance),
            lines = lines(glance),
        )
        return TodayWidgetState(ui, glance.refreshAt.toInstant(zone))
    }

    private fun everyMinute(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(1.minutes)
        }
    }

    private suspend fun message(g: TodayGlance): String? = when {
        g.isEmpty && g.tomorrow == null -> getString(Res.string.widget_quiet)
        g.next == null && g.handoffs.isEmpty() -> getString(Res.string.widget_nothing_more)
        else -> null
    }

    private suspend fun lines(g: TodayGlance): List<WidgetLine> = buildList {
        if (g.allDay.isNotEmpty()) {
            add(WidgetLine(getString(Res.string.widget_all_day), g.allDay.joinToString(", ") { it.event.title }))
        }
        g.next?.let { next ->
            val o = next.occurrence
            val label = if (g.nextIsNow) getString(Res.string.widget_now) else formatTime(o.start.time)
            add(WidgetLine(label, o.event.title, next.people.map { it.color }, eventId = o.event.id, date = o.occurrenceDate))
        }
        if (g.laterToday > 0) add(WidgetLine("", getString(Res.string.widget_more_today, g.laterToday)))
        g.handoffs.forEach { h ->
            val kind = getString(if (h.kind == HandoffKind.PickUp) Res.string.today_pickup else Res.string.today_dropoff)
            val who = h.person?.name ?: getString(Res.string.today_handoff_nobody)
            val kids = joinNamesText(h.children.map { it.name })
            val text = if (kids.isEmpty()) {
                getString(Res.string.widget_handoff_plain, kind, who)
            } else {
                getString(Res.string.widget_handoff, kind, kids, who)
            }
            add(
                WidgetLine(
                    label = formatTime(h.time),
                    text = text,
                    colors = listOfNotNull(h.person?.color),
                    warning = h.person == null,
                    eventId = h.occurrence.event.id,
                    date = h.occurrence.occurrenceDate,
                ),
            )
        }
        if (g.packing.isNotEmpty()) {
            val many = g.packing.size > 1
            val items = g.packing.flatMap { p ->
                p.items.map { if (many) getString(Res.string.notify_item_child, it.text, p.child.name) else it.text }
            }
            add(WidgetLine(getString(Res.string.widget_bring), items.joinToString(", ")))
        }
        g.tomorrow?.let { t ->
            val text = if (t.allDay) t.event.title else "${formatTime(t.start.time)} ${t.event.title}"
            add(WidgetLine(getString(Res.string.tomorrow), text, eventId = t.event.id, date = t.occurrenceDate))
        }
    }
}
