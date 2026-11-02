package com.mattdixon.jobjar.reminders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.mattdixon.jobjar.JobJarApplication
import com.mattdixon.jobjar.MainActivity
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.DueReminder
import com.mattdixon.jobjar.data.ReminderPreferences
import com.mattdixon.jobjar.data.dueReminders
import com.mattdixon.jobjar.data.isAvailableToDraw
import com.mattdixon.jobjar.data.nextReminderAt
import com.mattdixon.jobjar.util.dueText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZoneId

private const val ACTION_REMIND = "com.mattdixon.jobjar.action.REMIND"
private const val CHANNEL_ID = "reminders"
private const val DUE_NOTIFICATION_ID = 1
private const val NUDGE_NOTIFICATION_ID = 2

/**
 * One alarm a day at the reminder time, while either reminder is on. When it goes off,
 * [ReminderReceiver] posts whichever reminders apply and sets the next one. The alarm is
 * inexact (Android may shift it a little to save battery), which is fine for a nudge and means
 * no special alarm permission.
 */
object ReminderScheduler {
    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (!ReminderPreferences.dueReminders(context) && !ReminderPreferences.dailyNudge(context)) {
            alarms.cancel(pending)
            return
        }
        val next = nextReminderAt(System.currentTimeMillis(), ReminderPreferences.timeMinutes(context), ZoneId.systemDefault())
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
    }

    /** False on Android 13+ until notifications are allowed. */
    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/** The daily alarm, plus re-setting it after a restart, an update or a clock change. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMIND) {
            ReminderScheduler.schedule(context)
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                postReminders(context)
            } finally {
                ReminderScheduler.schedule(context)
                pending.finish()
            }
        }
    }

    private suspend fun postReminders(context: Context) {
        if (!ReminderScheduler.canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val jobs = (context.applicationContext as JobJarApplication).repository.exportJobs()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()

        if (ReminderPreferences.dueReminders(context)) {
            val due = dueReminders(jobs, now, zone)
            if (due.isNotEmpty()) manager.notify(DUE_NOTIFICATION_ID, dueNotification(context, due, now, zone))
        }
        if (ReminderPreferences.dailyNudge(context)) {
            val inJar = jobs.count { it.isAvailableToDraw(now, zone) }
            if (inJar > 0) manager.notify(NUDGE_NOTIFICATION_ID, nudgeNotification(context, inJar))
        }
    }

    /** "Bins is due today", or "3 repeating jobs" with a line each. Tapping opens the app. */
    private fun dueNotification(context: Context, due: List<DueReminder>, now: Long, zone: ZoneId): Notification {
        val lines = due.map { context.getString(R.string.reminder_due_line, it.title, context.dueText(it.daysUntil, now, zone)) }
        val title = if (due.size == 1) lines.single() else context.resources.getQuantityString(R.plurals.reminder_due_title, due.size, due.size)
        val style = Notification.InboxStyle().also { inbox -> lines.forEach { inbox.addLine(it) } }
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (due.size == 1) null else lines.joinToString(", "))
            .setStyle(if (due.size == 1) null else style)
            .setContentIntent(openApp(context, draw = false))
            .setAutoCancel(true)
            .build()
    }

    /** "Got a few minutes? 12 jobs in the jar." Tapping opens the app and draws. */
    private fun nudgeNotification(context: Context, inJar: Int): Notification =
        Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminder_nudge_title))
            .setContentText(context.resources.getQuantityString(R.plurals.reminder_nudge_text, inJar, inJar))
            .setContentIntent(openApp(context, draw = true))
            .setAutoCancel(true)
            .build()

    private fun openApp(context: Context, draw: Boolean): PendingIntent = PendingIntent.getActivity(
        context,
        if (draw) 1 else 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_DRAW, draw),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
