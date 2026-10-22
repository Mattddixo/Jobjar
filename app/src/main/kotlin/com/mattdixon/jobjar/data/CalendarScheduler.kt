package com.mattdixon.jobjar.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * Talks to the device's own Calendar Provider (`CalendarContract`) rather than Google's Calendar
 * API directly - a scheduled job still ends up on the user's real Google Calendar, since the
 * device's own Google account sync adapter picks up anything written here and pushes it to the
 * cloud automatically, but this app never needs a sign-in flow, an API key, or a network call to
 * make that happen. The tradeoff, by design: this is one-directional. Job Jar writes events; it
 * never reads calendar-side changes back.
 *
 * Every provider call is a blocking disk/IPC call, so all of it runs on [Dispatchers.IO]. Every
 * event this app writes is tagged with its package and a per-job URI ([jobUri]), and an existing
 * event is only ever changed or deleted once that tag proves it's ours (see [CalendarGateway]).
 * Events written before tagging existed are recognised by still matching the job's exact title
 * and start time, and get tagged the next time they're updated.
 */
class CalendarScheduler(private val context: Context) : CalendarGateway {

    private val resolver get() = context.contentResolver

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override suspend fun insertEvent(job: Job, startMillis: Long, endMillis: Long): Long? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null
        val calendarId = findWritableCalendarId() ?: return@withContext null
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, job.title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        // Some calendar providers reject the custom-app columns; an untagged event is still far
        // better than no event, and is recognised later by its title and start time instead.
        val uri = runCatching { resolver.insert(CalendarContract.Events.CONTENT_URI, values.tagged(job)) }.getOrNull()
            ?: runCatching { resolver.insert(CalendarContract.Events.CONTENT_URI, values) }.getOrNull()
        uri?.let { ContentUris.parseId(it) }
    }

    override suspend fun updateEvent(eventId: Long, owner: Job, title: String, startMillis: Long, endMillis: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (!hasPermission() || !isOurs(eventId, owner)) return@withContext false
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
            }
            val updated = runCatching { resolver.update(uri, values.tagged(owner), null, null) }.getOrNull()
                ?: runCatching { resolver.update(uri, values, null, null) }.getOrNull()
            (updated ?: 0) > 0
        }

    override suspend fun deleteEvent(eventId: Long, owner: Job): Boolean = withContext(Dispatchers.IO) {
        if (!hasPermission() || !isOurs(eventId, owner)) return@withContext false
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        (runCatching { resolver.delete(uri, null, null) }.getOrNull() ?: 0) > 0
    }

    override suspend fun ownsEvent(eventId: Long, owner: Job): Boolean = withContext(Dispatchers.IO) {
        hasPermission() && isOurs(eventId, owner)
    }

    private fun ContentValues.tagged(job: Job) = ContentValues(this).apply {
        put(CalendarContract.Events.CUSTOM_APP_PACKAGE, context.packageName)
        put(CalendarContract.Events.CUSTOM_APP_URI, jobUri(job.id))
    }

    private fun isOurs(eventId: Long, owner: Job): Boolean {
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.CUSTOM_APP_PACKAGE,
            CalendarContract.Events.CUSTOM_APP_URI,
            CalendarContract.Events.DELETED
        )
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val cursor = runCatching { resolver.query(uri, projection, null, null, null) }.getOrNull() ?: return false
        cursor.use {
            if (!it.moveToFirst()) return false
            if (it.getInt(4) != 0) return false
            val pkg = it.getString(2)
            val appUri = it.getString(3)
            if (pkg != null || appUri != null) {
                return pkg == context.packageName && appUri == jobUri(owner.id)
            }
            // Untagged - either one of the user's own events that happens to share this id
            // (e.g. after a restore onto a new phone) or one this app wrote before tagging
            // existed. Only the latter still looks exactly like what was written.
            return it.getString(0) == owner.title && !it.isNull(1) && it.getLong(1) == owner.scheduledDate
        }
    }

    /**
     * Prefers the device's primary calendar; falls back to the first calendar this app can
     * actually write events to, since not every device has exactly one obvious "the" calendar
     * (work + personal Google accounts, a local-only calendar, etc). CAL_ACCESS_CONTRIBUTOR is
     * the minimum access level the platform documents as sufficient to insert/update/delete
     * events.
     */
    private fun findWritableCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        val cursor = runCatching {
            resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)
        }.getOrNull() ?: return null
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val primaryIndex = it.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
            val accessIndex = it.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            var fallbackId: Long? = null
            while (it.moveToNext()) {
                if (it.getInt(accessIndex) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                val id = it.getLong(idIndex)
                val isPrimary = primaryIndex >= 0 && it.getInt(primaryIndex) != 0
                if (isPrimary) return id
                if (fallbackId == null) fallbackId = id
            }
            return fallbackId
        }
    }

    companion object {
        /** Stored on every event this app writes, identifying the exact job it belongs to. */
        fun jobUri(jobId: Long) = "jobjar://job/$jobId"
    }
}
