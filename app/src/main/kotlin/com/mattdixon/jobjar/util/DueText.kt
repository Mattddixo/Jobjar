package com.mattdixon.jobjar.util

import android.content.Context
import com.mattdixon.jobjar.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Due today", "Overdue 2 days", "Due tomorrow", "Due Fri", "Due Mar 3", for places outside the
 * app's screens (reminders, widgets). [daysUntil] counts calendar days from today.
 */
fun Context.dueText(daysUntil: Int, now: Long, zone: ZoneId): String = when {
    daysUntil < 0 -> resources.getQuantityString(R.plurals.overdue_n_days, -daysUntil, -daysUntil)
    daysUntil == 0 -> getString(R.string.due_today)
    daysUntil == 1 -> getString(R.string.due_tomorrow_plain)
    else -> {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(daysUntil.toLong())
        getString(R.string.due_on, date.format(DateTimeFormatter.ofPattern(if (daysUntil < 7) "EEE" else "MMM d")))
    }
}
