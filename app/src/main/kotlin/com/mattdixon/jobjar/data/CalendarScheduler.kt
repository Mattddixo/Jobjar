package com.mattdixon.jobjar.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.TimeZone

/**
 * Talks to the device's own Calendar Provider (`CalendarContract`) rather than Google's Calendar
 * API directly - a scheduled job still ends up on the user's real Google Calendar, since the
 * device's own Google account sync adapter picks up anything written here and pushes it to the
 * cloud automatically, but this app never needs a sign-in flow, an API key, or a network call to
 * make that happen. The tradeoff, by design: this is one-directional. Job Jar writes events; it
 * never reads calendar-side changes back.
 */
class CalendarScheduler(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /**
     * Inserts one event on the device's primary (or first writable) calendar. Returns its event
     * id - callers store this on the [Job] so a later reschedule/unschedule touches this exact
     * event instead of leaving orphaned duplicates - or null if permission isn't granted or the
     * device has no writable calendar to insert into.
     */
    fun insertEvent(title: String, startMillis: Long, endMillis: Long): Long? {
        if (!hasPermission()) return null
        val calendarId = findWritableCalendarId() ?: return null
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
        return ContentUris.parseId(uri)
    }

    /** Updates an existing event's time/title in place. Returns false (rather than throwing) if
     * the event no longer exists - e.g. the user deleted it from Google Calendar directly -
     * so the caller can fall back to inserting a fresh one instead. */
    fun updateEvent(eventId: Long, title: String, startMillis: Long, endMillis: Long): Boolean {
        if (!hasPermission()) return false
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
        }
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        return context.contentResolver.update(uri, values, null, null) > 0
    }

    fun deleteEvent(eventId: Long): Boolean {
        if (!hasPermission()) return false
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        return context.contentResolver.delete(uri, null, null) > 0
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
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val primaryIndex = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
            val accessIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            var fallbackId: Long? = null
            while (cursor.moveToNext()) {
                if (cursor.getInt(accessIndex) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                val id = cursor.getLong(idIndex)
                val isPrimary = primaryIndex >= 0 && cursor.getInt(primaryIndex) != 0
                if (isPrimary) return id
                if (fallbackId == null) fallbackId = id
            }
            return fallbackId
        }
        return null
    }
}
