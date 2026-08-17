package com.mattdixon.jobjar.data

import android.content.Context

private const val PREFS_NAME = "job_jar_settings"
private const val KEY_DARK_THEME = "dark_theme"

/**
 * The one user-facing setting this app has: an explicit light/dark override from the top-right
 * toggle, which should stick across launches rather than reset to the system default every time.
 * A single SharedPreferences boolean is all that's worth building for that - no need for a
 * DataStore dependency over one flag.
 */
object ThemePreferences {
    /** Null means "no explicit choice yet" - caller should fall back to the system setting. */
    fun isDarkTheme(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_DARK_THEME)) prefs.getBoolean(KEY_DARK_THEME, false) else null
    }

    fun setDarkTheme(context: Context, isDark: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_THEME, isDark)
            .apply()
    }
}
