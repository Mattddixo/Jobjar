package com.mattdixon.jobjar.data

import android.content.Context

private const val PREFS_NAME = "job_jar_settings"
private const val KEY_THEME_MODE = "theme_mode"
/** The old on/off dark-mode override, read once so an existing choice carries over. */
private const val KEY_LEGACY_DARK_THEME = "dark_theme"

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Light/dark/follow-the-system, chosen in Settings. A single SharedPreferences value is all
 * that's worth building for that - no need for a DataStore dependency over one setting.
 */
object ThemePreferences {
    fun themeMode(context: Context): ThemeMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_THEME_MODE, null)?.let { saved ->
            return ThemeMode.entries.find { it.name == saved } ?: ThemeMode.SYSTEM
        }
        if (prefs.contains(KEY_LEGACY_DARK_THEME)) {
            return if (prefs.getBoolean(KEY_LEGACY_DARK_THEME, false)) ThemeMode.DARK else ThemeMode.LIGHT
        }
        return ThemeMode.SYSTEM
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode.name)
            .remove(KEY_LEGACY_DARK_THEME)
            .apply()
    }
}
