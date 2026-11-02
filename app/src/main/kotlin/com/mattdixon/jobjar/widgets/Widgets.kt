package com.mattdixon.jobjar.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.mattdixon.jobjar.JobJarApplication
import com.mattdixon.jobjar.MainActivity
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.ComingUpGroup
import com.mattdixon.jobjar.data.DrawPreferences
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.comingUp
import com.mattdixon.jobjar.data.isAvailableToDraw
import com.mattdixon.jobjar.data.remainingMinutes
import com.mattdixon.jobjar.util.dueText
import com.mattdixon.jobjar.util.formatMinutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZoneId

private const val ACTION_DRAW = "com.mattdixon.jobjar.action.WIDGET_DRAW"
private const val PREFS_NAME = "job_jar_settings"
private const val KEY_WIDGET_JOB = "widget_drawn_job"

/** Redraws both widgets, e.g. after the jobs change. Cheap when none are on the home screen. */
object WidgetUpdater {
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        listOf(DrawWidget::class.java, ComingUpWidget::class.java).forEach { provider ->
            val ids = manager.getAppWidgetIds(ComponentName(context, provider))
            if (ids.isNotEmpty()) {
                context.sendBroadcast(
                    Intent(context, provider)
                        .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                )
            }
        }
    }
}

/** Runs [block] off the main thread and keeps the receiver alive until it's done. */
private fun AppWidgetProvider.inBackground(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

private fun openApp(context: Context, requestCode: Int, jobId: Long? = null): PendingIntent = PendingIntent.getActivity(
    context,
    requestCode,
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .apply { if (jobId != null) putExtra(MainActivity.EXTRA_JOB_ID, jobId) },
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
)

/**
 * Draw a job from the home screen, with the Jar tab's settings (time, how long, categories).
 * Shows the drawn job until it's started, finished or booked; tap it to open, or draw again.
 */
class DrawWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        inBackground { render(context, manager, ids) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DRAW) {
            super.onReceive(context, intent)
            return
        }
        inBackground {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getLong(KEY_WIDGET_JOB, 0L)
            val settings = DrawPreferences.load(context)
            val pick = repository(context).drawJob(
                maxMinutes = settings.availableMinutes,
                categories = settings.selectedCategories,
                excludeIds = listOf(current),
                longOnly = settings.longJobsOnly,
                favourFill = DrawPreferences.favourFill(context)
            )
            prefs.edit().putLong(KEY_WIDGET_JOB, pick?.job?.id ?: -1L).apply()
            val manager = AppWidgetManager.getInstance(context)
            render(context, manager, manager.getAppWidgetIds(ComponentName(context, DrawWidget::class.java)))
        }
    }

    private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jobs = repository(context).exportJobs()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        // -1 = the last draw came up empty; anything that's gone or no longer in the jar is cleared.
        val drawnId = prefs.getLong(KEY_WIDGET_JOB, 0L)
        val drawn = jobs.firstOrNull { it.id == drawnId && it.isAvailableToDraw(now, zone) }

        val views = RemoteViews(context.packageName, R.layout.widget_draw)
        when {
            drawn != null -> {
                views.setTextViewText(R.id.draw_title, drawn.title)
                views.setTextViewText(R.id.draw_detail, context.getString(R.string.widget_draw_job_detail, minutesFor(drawn, jobs)))
                views.setTextViewText(R.id.draw_button, context.getString(R.string.widget_draw_again))
                views.setOnClickPendingIntent(R.id.draw_job, openApp(context, 10, drawn.id))
            }
            else -> {
                val inJar = jobs.count { it.isAvailableToDraw(now, zone) }
                views.setTextViewText(
                    R.id.draw_title,
                    context.getString(if (drawnId == -1L) R.string.widget_draw_nothing else R.string.widget_draw_prompt)
                )
                views.setTextViewText(R.id.draw_detail, context.resources.getQuantityString(R.plurals.widget_draw_count, inJar, inJar))
                views.setTextViewText(R.id.draw_button, context.getString(R.string.widget_draw))
                views.setOnClickPendingIntent(R.id.draw_job, openApp(context, 11))
            }
        }
        val draw = PendingIntent.getBroadcast(
            context, 0, Intent(context, DrawWidget::class.java).setAction(ACTION_DRAW),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.draw_button, draw)
        manager.updateAppWidget(ids, views)
    }

    /** What's left of a parent job, or a subtask's own estimate. */
    private fun minutesFor(job: Job, jobs: List<Job>): String = formatMinutes(
        if (job.parentId == null) job.remainingMinutes(jobs.filter { it.parentId == job.id }) else job.estimatedMinutes
    )
}

/** The next few repeating jobs that are overdue, due today or due soon, like the Coming up card. */
class ComingUpWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        inBackground {
            val jobs = repository(context).exportJobs()
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val items = comingUp(jobs, DrawPreferences.comingUpRange(context), now, zone)
                .filter { it.group != ComingUpGroup.LATER }
            val views = RemoteViews(context.packageName, R.layout.widget_coming_up)
            val rows = listOf(R.id.coming_up_row1, R.id.coming_up_row2, R.id.coming_up_row3, R.id.coming_up_row4)
            rows.forEachIndexed { index, row ->
                val item = items.getOrNull(index)
                if (item == null) {
                    views.setViewVisibility(row, View.GONE)
                } else {
                    val due = if (item.job.nextDueAt == null) context.getString(R.string.due_now) else context.dueText(item.daysUntil, now, zone)
                    views.setTextViewText(row, context.getString(R.string.reminder_due_line, item.job.title, due))
                    views.setTextColor(
                        row,
                        context.getColor(if (item.group == ComingUpGroup.OVERDUE) R.color.widget_overdue else R.color.widget_text)
                    )
                    views.setViewVisibility(row, View.VISIBLE)
                }
            }
            views.setViewVisibility(R.id.coming_up_empty, if (items.isEmpty()) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(R.id.widget_root, openApp(context, 12))
            manager.updateAppWidget(ids, views)
        }
    }
}

private fun repository(context: Context) = (context.applicationContext as JobJarApplication).repository
