package com.mattdixon.jobjar.data

import android.app.UiModeManager
import android.content.Context
import android.os.Build

private const val PREFS_NAME = "job_jar_settings"
private const val KEY_THEME_MODE = "theme_mode"
/** Old on/off dark mode setting, still read so it carries over. */
private const val KEY_LEGACY_DARK_THEME = "dark_theme"

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Light / dark / follow the system. */
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
        applyToSystem(context, mode)
    }

    /**
     * Tells Android 12+ which theme the app uses, so the start-up screen (drawn before any app
     * code runs) is dark when the app is set to Dark even if the phone is in light mode. Older
     * versions have no way to do this, so their start-up screen follows the phone.
     */
    fun applyToSystem(context: Context, mode: ThemeMode = themeMode(context)) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val uiModeManager = context.getSystemService(UiModeManager::class.java) ?: return
        val nightMode = when (mode) {
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        }
        uiModeManager.setApplicationNightMode(nightMode)
    }
}
