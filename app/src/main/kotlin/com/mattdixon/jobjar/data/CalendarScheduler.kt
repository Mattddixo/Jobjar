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
 * Writes scheduled jobs to the phone's calendar through CalendarContract. The phone's own sync
 * takes them to Google Calendar, so there's no sign-in or network code here. One-way only:
 * changes made in the calendar app aren't read back.
 *
 * Events are tagged with our package name and a per-job URI, and we only edit or delete an event
 * after checking the tag. Older untagged events are matched on title + start time instead.
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
            put(CalendarContract.Events.DESCRIPTION, eventDescription(job.notes))
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        // Some providers reject the custom app columns. An untagged event is better than none.
        val uri = runCatching { resolver.insert(CalendarContract.Events.CONTENT_URI, values.tagged(job)) }.getOrNull()
            ?: runCatching { resolver.insert(CalendarContract.Events.CONTENT_URI, values) }.getOrNull()
        uri?.let { ContentUris.parseId(it) }
    }

    override suspend fun updateEvent(eventId: Long, owner: Job, title: String, notes: String, startMillis: Long, endMillis: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (!hasPermission() || !isOurs(eventId, owner)) return@withContext false
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, eventDescription(notes))
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
            // No tag: either someone else's event that happens to have this id, or one of ours from
            // before tagging. Only ours would still match exactly.
            return it.getString(0) == owner.title && !it.isNull(1) && it.getLong(1) == owner.scheduledDate
        }
    }

    /** Primary calendar if there is one, otherwise the first one we're allowed to write to. */
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
        fun jobUri(jobId: Long) = "jobjar://job/$jobId"
    }
}
