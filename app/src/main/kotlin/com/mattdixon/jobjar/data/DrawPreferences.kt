package com.mattdixon.jobjar.data

import android.content.Context

private const val PREFS_NAME = "job_jar_settings"
private const val KEY_AVAILABLE_MINUTES = "draw_available_minutes"
private const val KEY_LONG_JOBS_ONLY = "draw_long_jobs_only"
private const val KEY_SELECTED_CATEGORIES = "draw_selected_categories"
private const val KEY_BATCH_SIZE = "draw_batch_size"

/**
 * The Jar tab's own control panel (time budget, long-jobs toggle, category, batch size) used to
 * reset to defaults every time the app restarted, which meant re-entering the same settings on
 * every launch for anyone who'd settled on values that fit their routine. Same
 * SharedPreferences-over-DataStore call as [ThemePreferences] - a handful of scalars, not worth
 * a dependency. Kept to plain primitives here rather than importing the ui.draw enum type, so
 * the data layer doesn't reach up into a UI-layer type just to persist its name.
 */
object DrawPreferences {
    fun load(context: Context): SavedDrawSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SavedDrawSettings(
            availableMinutes = prefs.getInt(KEY_AVAILABLE_MINUTES, 30),
            longJobsOnly = prefs.getBoolean(KEY_LONG_JOBS_ONLY, false),
            // Defensively copied: SharedPreferences.getStringSet() returns the exact instance
            // backing the store, which must never be mutated by the caller.
            selectedCategories = prefs.getStringSet(KEY_SELECTED_CATEGORIES, null)?.toSet() ?: emptySet(),
            batchSizeName = prefs.getString(KEY_BATCH_SIZE, null)
        )
    }

    fun save(context: Context, settings: SavedDrawSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_AVAILABLE_MINUTES, settings.availableMinutes)
            .putBoolean(KEY_LONG_JOBS_ONLY, settings.longJobsOnly)
            .putStringSet(KEY_SELECTED_CATEGORIES, HashSet(settings.selectedCategories))
            .putString(KEY_BATCH_SIZE, settings.batchSizeName)
            .apply()
    }
}

data class SavedDrawSettings(
    val availableMinutes: Int,
    val longJobsOnly: Boolean,
    val selectedCategories: Set<String>,
    /** The enum name of the caller's DrawBatchSize choice - kept as a raw string so this file has no dependency on that ui-layer type. */
    val batchSizeName: String?
)
