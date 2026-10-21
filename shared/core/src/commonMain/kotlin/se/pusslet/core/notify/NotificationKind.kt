package se.pusslet.core.notify

/**
 * How loudly a channel speaks by default. The Android side maps it to `NotificationManager.IMPORTANCE_*`; the user can
 * change it per channel in the system settings, so this is only the starting point.
 */
enum class NotificationImportance {
    /** Shown in the shade without sound or vibration (`IMPORTANCE_LOW`). */
    Low,

    /** Sound, no heads-up (`IMPORTANCE_DEFAULT`). */
    Default,

    /** Sound and heads-up (`IMPORTANCE_HIGH`): something to do soon. */
    High,
}

/**
 * One notification channel per kind (BRIEF §4.10), so the user can switch each of them off or quiet them in the system
 * settings. [channelId] is stable: Android keeps a channel's settings by its id, so it must never change once shipped.
 */
enum class NotificationKind(val channelId: String, val importance: NotificationImportance) {
    /** "Tandläkare Simon om 30 min": the reminders set on events, and to-dos on their day. */
    Reminder("pusslet.reminders", NotificationImportance.High),

    /** "Du hämtar Ella 15:45 · Björkängsskolan", [ReminderPlanner.HANDOFF_LEAD] before my own drop-off or pick-up. */
    HandoffSoon("pusslet.handoffs", NotificationImportance.High),

    /** Morgonkollen at the time chosen in Inställningar, with the greeting (ADR-026). */
    Morning("pusslet.morning", NotificationImportance.Low),

    /** Kvällskollen: "Packa ikväll: gympapåse (Ella)" at [ReminderPlanner.EVENING_TIME]. */
    Evening("pusslet.evening", NotificationImportance.Default),

    /** "Kan du hämta Ella?", and the answer to it: push from another phone (ADR-042). */
    Requests("pusslet.requests", NotificationImportance.High),

    /** "Sara lade till mjölk" while I shop from that list (ADR-018): push from another phone (ADR-042). */
    Shopping("pusslet.shopping", NotificationImportance.Default),

    /** Something in the family's plan clashes – arrives with FCM or a later local rule. */
    Conflicts("pusslet.conflicts", NotificationImportance.Default),
    ;

    /** The Android notification group, so the system can stack a kind's notifications. */
    val groupKey: String get() = channelId
}
