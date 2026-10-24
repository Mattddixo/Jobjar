package com.mattdixon.jobjar.data

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
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
        val alertMinutes = CalendarPreferences.alertMinutes(context)
        if (alertMinutes != null) values.put(CalendarContract.Events.HAS_ALARM, 1)
        // Some providers reject the custom app columns. An untagged event is better than none.
        val uri = runCatching { resolver.insert(CalendarContract.Events.CONTENT_URI, values.tagged(job)) }.getOrNull()
            ?: runCatching { resolver.insert(CalendarContract.Events.CONTENT_URI, values) }.getOrNull()
        val eventId = uri?.let { ContentUris.parseId(it) } ?: return@withContext null
        if (alertMinutes != null) addAlert(eventId, alertMinutes)
        requestSync(calendarId)
        eventId
    }

    /** The alert from Settings. It stays with the event when it's moved; edit it in the calendar app. */
    private fun addAlert(eventId: Long, minutesBefore: Int) {
        val reminder = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutesBefore)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        // The event is already saved; a calendar that won't take the alert just doesn't get one.
        runCatching { resolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder) }
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
            calendarIdOfEvent(eventId)?.let(::requestSync)
            (updated ?: 0) > 0
        }

    override suspend fun deleteEvent(eventId: Long, owner: Job): Boolean = withContext(Dispatchers.IO) {
        if (!hasPermission() || !isOurs(eventId, owner)) return@withContext false
        val calendarId = calendarIdOfEvent(eventId)
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val deleted = (runCatching { resolver.delete(uri, null, null) }.getOrNull() ?: 0) > 0
        calendarId?.let(::requestSync)
        deleted
    }

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            if (hasPermission()) findWritableCalendarId()?.let(::requestSync)
        }
    }

    /**
     * Asks Android to sync the calendar's account now. Changes we write go into the phone's
     * calendar storage, but calendar apps like Google Calendar mostly show what has synced with
     * their servers, so without this a new or moved event can take a while to appear. Local
     * (phone-only) calendars don't sync and need nothing.
     */
    private fun requestSync(calendarId: Long) {
        val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)
        val projection = arrayOf(CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE)
        val cursor = runCatching { resolver.query(uri, projection, null, null, null) }.getOrNull() ?: return
        val account = cursor.use {
            if (!it.moveToFirst()) return
            Account(it.getString(0) ?: return, it.getString(1) ?: return)
        }
        if (account.type == CalendarContract.ACCOUNT_TYPE_LOCAL) return
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        runCatching { ContentResolver.requestSync(account, CalendarContract.AUTHORITY, extras) }
    }

    private fun calendarIdOfEvent(eventId: Long): Long? {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val cursor = runCatching { resolver.query(uri, arrayOf(CalendarContract.Events.CALENDAR_ID), null, null, null) }.getOrNull()
            ?: return null
        return cursor.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }

    override suspend fun ownsEvent(eventId: Long, owner: Job): Boolean = withContext(Dispatchers.IO) {
        hasPermission() && isOurs(eventId, owner)
    }

    override suspend fun findEvent(eventId: Long, owner: Job): EventLookup = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext EventLookup.Unknown
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.CUSTOM_APP_PACKAGE,
            CalendarContract.Events.CUSTOM_APP_URI,
            CalendarContract.Events.DELETED
        )
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val byId = runCatching { resolver.query(uri, projection, null, null, null) }.getOrNull()
            ?: return@withContext EventLookup.Unknown
        byId.use {
            if (it.moveToFirst() && it.getInt(4) == 0) {
                val pkg = it.getString(2)
                val appUri = it.getString(3)
                if (pkg == null && appUri == null) {
                    // Untagged (from before tagging, or a provider that drops the columns). It only
                    // provably matches while it's untouched; if it was moved, we can't tell it from
                    // someone else's event, so leave the booking alone.
                    val untouched = it.getString(0) == owner.title && !it.isNull(1) && it.getLong(1) == owner.scheduledDate
                    return@withContext if (untouched) EventLookup.Found(eventId, it.getLong(1)) else EventLookup.Unknown
                }
                if (pkg == context.packageName && appUri == jobUri(owner.id)) {
                    return@withContext if (it.isNull(1)) EventLookup.Unknown else EventLookup.Found(eventId, it.getLong(1))
                }
            }
        }
        // Not under that id (deleted, or the id now belongs to someone else's event). It may still
        // exist under a new id, so look for our tag before calling it gone.
        val selection = "${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? AND ${CalendarContract.Events.CUSTOM_APP_URI} = ? AND " +
            "${CalendarContract.Events.DELETED} = 0"
        val byTag = runCatching {
            resolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID, CalendarContract.Events.DTSTART),
                selection,
                arrayOf(context.packageName, jobUri(owner.id)),
                null
            )
        }.getOrNull() ?: return@withContext EventLookup.Unknown
        byTag.use {
            if (it.moveToFirst() && !it.isNull(1)) EventLookup.Found(it.getLong(0), it.getLong(1)) else EventLookup.Missing
        }
    }

    /** The job an event belongs to, from its tag. For opening the job from the calendar app. */
    fun jobIdForEvent(eventId: Long): Long? {
        if (!hasPermission()) return null
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val cursor = runCatching {
            resolver.query(uri, arrayOf(CalendarContract.Events.CUSTOM_APP_PACKAGE, CalendarContract.Events.CUSTOM_APP_URI), null, null, null)
        }.getOrNull() ?: return null
        cursor.use {
            if (!it.moveToFirst() || it.getString(0) != context.packageName) return null
            return jobIdFromUri(it.getString(1))
        }
    }

    /** Calendars new bookings can go to: visible ones you can add events to, for Settings. */
    fun writableCalendars(): List<CalendarOption> {
        if (!hasPermission()) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val cursor = runCatching {
            resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)
        }.getOrNull() ?: return emptyList()
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    if (it.getInt(3) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR || it.getInt(4) == 0) continue
                    add(
                        CalendarOption(
                            id = it.getLong(0),
                            name = it.getString(1) ?: "",
                            account = it.getString(2) ?: "",
                            isPrimary = it.getInt(5) != 0
                        )
                    )
                }
            }
        }
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

    /**
     * Where new events go: the calendar picked in Settings if it's still there, otherwise the
     * phone's main calendar.
     */
    private fun findWritableCalendarId(): Long? {
        val chosen = CalendarPreferences.calendarId(context)
        if (chosen != null && writableCalendars().any { it.id == chosen }) return chosen
        return findDefaultCalendarId()
    }

    /**
     * The phone's main calendar: a visible primary calendar if there is one, then any visible
     * calendar we can write to, then any we can write to at all.
     */
    private fun findDefaultCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )
        val cursor = runCatching {
            resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)
        }.getOrNull() ?: return null
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val primaryIndex = it.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
            val accessIndex = it.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            val visibleIndex = it.getColumnIndex(CalendarContract.Calendars.VISIBLE)
            var visibleFallback: Long? = null
            var anyFallback: Long? = null
            while (it.moveToNext()) {
                if (it.getInt(accessIndex) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                val id = it.getLong(idIndex)
                val isPrimary = primaryIndex >= 0 && it.getInt(primaryIndex) != 0
                val isVisible = visibleIndex < 0 || it.getInt(visibleIndex) != 0
                if (isPrimary && isVisible) return id
                if (isVisible && visibleFallback == null) visibleFallback = id
                if (anyFallback == null) anyFallback = id
            }
            return visibleFallback ?: anyFallback
        }
    }

    companion object {
        fun jobUri(jobId: Long) = "jobjar://job/$jobId"

        /** The job id in a `jobjar://job/<id>` link, or null if it isn't one. */
        fun jobIdFromUri(uri: String?): Long? = uri?.removePrefix("jobjar://job/")?.takeIf { it != uri }?.toLongOrNull()
    }
}

/** A calendar you can pick in Settings. */
data class CalendarOption(val id: Long, val name: String, val account: String, val isPrimary: Boolean)
