package com.mattdixon.jobjar.data

import android.content.Context

/** Settings > Reminders. Both off unless turned on; 9am unless changed. */
object ReminderPreferences {
    private const val PREFS_NAME = "job_jar_settings"
    private const val KEY_DUE = "reminders_due"
    private const val KEY_NUDGE = "reminders_nudge"
    private const val KEY_TIME = "reminders_time"
    const val DEFAULT_TIME_MINUTES = 9 * 60

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun dueReminders(context: Context): Boolean = prefs(context).getBoolean(KEY_DUE, false)
    fun setDueReminders(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_DUE, on).apply()

    fun dailyNudge(context: Context): Boolean = prefs(context).getBoolean(KEY_NUDGE, false)
    fun setDailyNudge(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_NUDGE, on).apply()

    /** Minutes after midnight. */
    fun timeMinutes(context: Context): Int = prefs(context).getInt(KEY_TIME, DEFAULT_TIME_MINUTES)
    fun setTimeMinutes(context: Context, minutes: Int) = prefs(context).edit().putInt(KEY_TIME, minutes).apply()
}
