package se.pusslet.core.today

import kotlinx.datetime.LocalTime
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import se.pusslet.core.model.Role

/** Where a block sits on Idag: a row in Morgonkollen (fixed order) or a flat section below it (the user's order). */
enum class Placement { Hero, Section }

/**
 * The building blocks of Idag (UX plan, Steg 2, ADR-027). [id] is stored on the device, so it is ASCII, never renamed and
 * never reused for something else. Problems, requests and the header are not blocks: they can never be hidden.
 *
 * A block added in a later version must be added to [TodayLayouts.defaultFor] as *off* ("nya block kommer avstängda"),
 * so a family's calm Idag stays as it was after an update. Never turn the default of an existing block on.
 */
enum class TodayBlock(val id: String, val placement: Placement) {
    /** "Johan lämnar Simon 07:50 och Ella 08:05". */
    Handoffs("handoffs", Placement.Hero),

    /** "Ska med: gympapåse (Ella)", or "Allt är packat" until the last drop-off. */
    Packing("packing", Placement.Hero),

    /** "Ikväll: Fiskgratäng" with at most one line under it (lunch or clash). Parents only. */
    Dinner("dinner", Placement.Hero),

    /** "☁ 14° · Ella och Simon: tunn jacka" (tomorrow's from 18:00). */
    Weather("weather", Placement.Hero),

    /** "Idag händer": time, name and place. Parents only. */
    Timeline("timeline", Placement.Section),

    /** "Att göra": my open tasks. */
    Tasks("tasks", Placement.Section),

    /** "Kommande": the next days, with reminders (party, study day, squeeze day). */
    Upcoming("upcoming", Placement.Section),

    /** "Inköpslistan": "Veckohandling: 5 kvar" or "Sara handlar nu". */
    Shopping("shopping", Placement.Section),
    ;

    companion object {
        fun fromId(id: String): TodayBlock? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What the user changed on "Anpassa Idag", stored device-locally in `Prefs.TodayLayout` (ADR-012, ADR-027). Only changes
 * are kept: an empty [sectionOrder] means the role's default order and [visible] only lists blocks whose switch differs
 * from the default. `null` (nothing stored) follows the role's default, also after a role change.
 *
 * [version] is always written, so a later format can tell old layouts apart.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StoredTodayLayout(
    @EncodeDefault val version: Int = TodayLayouts.CURRENT_VERSION,
    /** The member the layout belongs to; another member on the same phone starts from the default. */
    val memberId: String,
    /** Section ids in the user's order. Empty = default order. */
    val sectionOrder: List<String> = emptyList(),
    /** Block id -> switch, only where it differs from the default. */
    val visible: Map<String, Boolean> = emptyMap(),
)

/** One row on "Anpassa Idag": a block and its switch. */
data class TodayLayoutItem(val block: TodayBlock, val visible: Boolean)

/**
 * A resolved Idag layout: the Morgonkollen rows in their fixed order and the sections in the user's order, each with
 * its switch. A hidden section keeps its place. Only blocks the role has are listed.
 */
data class TodayLayout(val hero: List<TodayLayoutItem>, val sections: List<TodayLayoutItem>) {
    val items: List<TodayLayoutItem> get() = hero + sections

    operator fun contains(block: TodayBlock): Boolean = items.any { it.block == block }

    /** The block is in the layout and switched on (it may still have nothing to show, see [TodayLayouts.visibleBlocks]). */
    fun isOn(block: TodayBlock): Boolean = items.any { it.block == block && it.visible }
}

/** Defaults per role and the rules for merging, moving and switching blocks. Pure and device independent. */
object TodayLayouts {
    const val CURRENT_VERSION: Int = 1

    private val parentHero = listOf(TodayBlock.Handoffs, TodayBlock.Packing, TodayBlock.Dinner, TodayBlock.Weather)
    private val parentSections = listOf(TodayBlock.Timeline, TodayBlock.Tasks, TodayBlock.Upcoming, TodayBlock.Shopping)

    /** Caregivers (morfar) and children have neither the dinner nor the timeline (UX plan, Steg 3). */
    private val helperHero = listOf(TodayBlock.Handoffs, TodayBlock.Packing, TodayBlock.Weather)
    private val helperSections = listOf(TodayBlock.Tasks, TodayBlock.Upcoming, TodayBlock.Shopping)

    /** Off by default for every role. New blocks of later versions go here too. */
    private val offByDefault = setOf(TodayBlock.Shopping)

    /** The blocks a role can have at all, Morgonkollen first, in default order. */
    fun blocksFor(role: Role): List<TodayBlock> = when (role) {
        Role.Parent -> parentHero + parentSections
        Role.Caregiver, Role.Child -> helperHero + helperSections
    }

    /**
     * Parent: Morgonkollen [handoffs, packing, dinner, weather] and the sections [timeline, tasks, upcoming] on, shopping
     * off. Caregiver and child: [handoffs, packing, weather] and [tasks, upcoming] on, shopping off.
     */
    fun defaultFor(role: Role): TodayLayout {
        val blocks = blocksFor(role)

        fun item(block: TodayBlock) = TodayLayoutItem(block, visible = block !in offByDefault)
        return TodayLayout(
            hero = blocks.filter { it.placement == Placement.Hero }.map(::item),
            sections = blocks.filter { it.placement == Placement.Section }.map(::item),
        )
    }

    /**
     * Merges what is stored with the role's default:
     * - nothing stored, no [memberId] or a layout stored for another member: the role's default;
     * - unknown ids, duplicates and blocks the role does not have are ignored;
     * - a known section missing from the stored order (new in this version) is put at its default position;
     * - a block without a stored switch gets the default one.
     */
    fun resolve(role: Role, memberId: String?, stored: StoredTodayLayout?): TodayLayout {
        val default = defaultFor(role)
        if (stored == null || memberId == null || stored.memberId != memberId) return default
        val defaultOrder = default.sections.map { it.block }
        val known = stored.sectionOrder.mapNotNull(TodayBlock::fromId).filter { it in defaultOrder }.distinct()
        val order = if (known.isEmpty()) {
            defaultOrder
        } else {
            val merged = known.toMutableList()
            defaultOrder.forEachIndexed { index, block ->
                if (block !in merged) merged.add(index.coerceAtMost(merged.size), block)
            }
            merged
        }

        fun switched(item: TodayLayoutItem) = item.copy(visible = stored.visible[item.block.id] ?: item.visible)
        val defaultSections = default.sections.associateBy { it.block }
        return TodayLayout(
            hero = default.hero.map(::switched),
            sections = order.mapNotNull { defaultSections[it] }.map(::switched),
        )
    }

    /**
     * Moves a section one step up or down, past hidden sections too (they keep their place). Morgonkollen rows have a
     * fixed order, so moving them, the top section up or the bottom one down changes nothing.
     */
    fun move(layout: TodayLayout, block: TodayBlock, up: Boolean): TodayLayout {
        val from = layout.sections.indexOfFirst { it.block == block }
        if (from < 0) return layout
        val to = if (up) from - 1 else from + 1
        if (to !in layout.sections.indices) return layout
        val sections = layout.sections.toMutableList()
        sections[from] = layout.sections[to]
        sections[to] = layout.sections[from]
        return layout.copy(sections = sections)
    }

    /** Switches a block on or off; it keeps its place. A block the layout does not have is ignored. */
    fun setVisible(layout: TodayLayout, block: TodayBlock, visible: Boolean): TodayLayout {
        fun List<TodayLayoutItem>.switched() = map { if (it.block == block) it.copy(visible = visible) else it }
        return TodayLayout(hero = layout.hero.switched(), sections = layout.sections.switched())
    }

    /** True when nothing differs from the role's default ("Återställ till standard" is then hidden). */
    fun isDefault(layout: TodayLayout, role: Role): Boolean = layout == defaultFor(role)

    /** What to store: only the changes, or `null` when the layout is the role's default (nothing to keep). */
    fun toStored(layout: TodayLayout, memberId: String, role: Role): StoredTodayLayout? {
        val default = defaultFor(role)
        if (layout == default) return null
        val order = layout.sections.map { it.block }
        val defaultVisible = default.items.associate { it.block to it.visible }
        return StoredTodayLayout(
            memberId = memberId,
            sectionOrder = if (order == default.sections.map { it.block }) emptyList() else order.map { it.id },
            visible = layout.items.filter { defaultVisible[it.block] != it.visible }.associate { it.block.id to it.visible },
        )
    }

    /**
     * The blocks to draw, Morgonkollen rows first (fixed order), then the sections in the user's order: switched on and
     * with something to show ("tomt är tyst"). [now] defaults to the time the plan was built for.
     */
    fun visibleBlocks(layout: TodayLayout, plan: TodayPlan, now: LocalTime = plan.now): List<TodayBlock> =
        layout.items.filter { it.visible && hasContent(it.block, layout, plan, now) }.map { it.block }

    /** "Inget mer idag": no section has anything to show (Morgonkollen does not count). */
    fun isQuiet(layout: TodayLayout, plan: TodayPlan, now: LocalTime = plan.now): Boolean =
        visibleBlocks(layout, plan, now).none { it.placement == Placement.Section }

    /** Whether a block has anything to show; independent of its switch. */
    fun hasContent(block: TodayBlock, layout: TodayLayout, plan: TodayPlan, now: LocalTime = plan.now): Boolean = when (block) {
        TodayBlock.Handoffs -> plan.hero.handoffs.any { line -> line.stops.any { it.time >= now } }
        TodayBlock.Packing -> plan.hero.packing.isShownAt(now)
        TodayBlock.Dinner -> plan.hero.dinner.isShownAt(now)
        TodayBlock.Weather -> plan.hasWeatherPlace
        TodayBlock.Timeline -> plan.timelineFor(layout).isNotEmpty()
        TodayBlock.Tasks -> plan.myTasks.isNotEmpty()
        TodayBlock.Upcoming -> plan.comingUp.isNotEmpty()
        TodayBlock.Shopping -> plan.shopping?.hasContent == true
    }
}
