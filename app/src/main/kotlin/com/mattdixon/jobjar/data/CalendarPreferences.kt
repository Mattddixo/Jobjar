package com.mattdixon.jobjar.data

import android.content.Context

private const val PREFS_NAME = "job_jar_settings"
private const val KEY_CALENDAR_ID = "calendar_id"
private const val KEY_ALERT_MINUTES = "calendar_alert_minutes"
private const val NO_ALERT = -1

/** Alert choices, in minutes before the start, like the calendar apps offer. */
val CALENDAR_ALERT_CHOICES = listOf(0, 5, 10, 15, 30, 60, 1440)

/** Fresh installs remind you 15 minutes before. */
const val DEFAULT_ALERT_MINUTES = 15

/** Settings > Calendar: which calendar new bookings go to, and their alert. */
object CalendarPreferences {
    /** Minutes before the start to alert, 0 = at the start, null = no alert. */
    fun alertMinutes(context: Context): Int? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_ALERT_MINUTES, DEFAULT_ALERT_MINUTES)
            .takeIf { it != NO_ALERT }

    fun setAlertMinutes(context: Context, minutes: Int?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ALERT_MINUTES, minutes ?: NO_ALERT)
            .apply()
    }

    /** Null = the phone's main calendar. */
    fun calendarId(context: Context): Long? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_CALENDAR_ID, -1L).takeIf { it >= 0 }

    fun setCalendarId(context: Context, id: Long?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            if (id == null) remove(KEY_CALENDAR_ID) else putLong(KEY_CALENDAR_ID, id)
        }.apply()
    }
}
