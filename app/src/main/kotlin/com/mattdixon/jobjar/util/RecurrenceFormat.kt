package com.mattdixon.jobjar.util

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/** Friendly label for a "repeats every N days" interval. */
fun formatRecurrenceInterval(days: Int): String = when (days) {
    1 -> "Daily"
    7 -> "Weekly"
    14 -> "Biweekly"
    30 -> "Monthly"
    else -> "Every $days days"
}

/**
 * "Due now" if [nextDueAt] is null (never completed yet) or already passed, otherwise how far
 * out the next occurrence is. Rounds up so "due tomorrow morning" doesn't read as "in 0 days".
 */
fun formatDueStatus(nextDueAt: Long?, now: Long = System.currentTimeMillis()): String {
    if (nextDueAt == null || nextDueAt <= now) return "Due now"
    val daysAway = ((nextDueAt - now) + DAY_MILLIS - 1) / DAY_MILLIS
    return if (daysAway == 1L) "Next: tomorrow" else "Next: in $daysAway days"
}
