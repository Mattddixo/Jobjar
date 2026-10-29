package com.mattdixon.jobjar.data

import android.content.Context

private const val PREFS_NAME = "job_jar_settings"
private const val KEY_AVAILABLE_MINUTES = "draw_available_minutes"
private const val KEY_LONG_JOBS_ONLY = "draw_long_jobs_only"
private const val KEY_SELECTED_CATEGORIES = "draw_selected_categories"
private const val KEY_BATCH_SIZE = "draw_batch_size"
private const val KEY_FAVOUR_FILL = "draw_favour_fill"
private const val KEY_SHOW_COMING_UP = "coming_up_show"
private const val KEY_COMING_UP_RANGE = "coming_up_range"

/** Remembers the Jar tab settings between launches. */
object DrawPreferences {
    /** Settings > "Favour jobs that fill my time". On unless turned off. */
    fun favourFill(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_FAVOUR_FILL, true)

    fun setFavourFill(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_FAVOUR_FILL, enabled).apply()
    }

    /** Settings > "Show Coming up on the Jar tab". Off unless turned on. */
    fun showComingUp(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_COMING_UP, false)

    fun setShowComingUp(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOW_COMING_UP, enabled).apply()
    }

    /** How many days ahead Coming up looks. One of [COMING_UP_RANGES]. */
    fun comingUpRange(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_COMING_UP_RANGE, DEFAULT_COMING_UP_RANGE)
            .takeIf { it in COMING_UP_RANGES } ?: DEFAULT_COMING_UP_RANGE

    fun setComingUpRange(context: Context, days: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_COMING_UP_RANGE, days).apply()
    }

    fun load(context: Context): SavedDrawSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SavedDrawSettings(
            availableMinutes = prefs.getInt(KEY_AVAILABLE_MINUTES, 30),
            longJobsOnly = prefs.getBoolean(KEY_LONG_JOBS_ONLY, false),
            // getStringSet returns the backing set, so copy it.
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
    /** DrawBatchSize.name, kept as a string so the data layer doesn't depend on UI types. */
    val batchSizeName: String?
)
