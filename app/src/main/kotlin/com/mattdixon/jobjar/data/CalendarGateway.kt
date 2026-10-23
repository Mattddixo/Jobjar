package com.mattdixon.jobjar.data

/**
 * What the repository needs from a calendar. An interface so tests can use a fake.
 *
 * Anything that touches an existing event passes the job it should belong to, and the
 * implementation must check that before acting. Event ids are only unique per device, so a
 * restored backup can hold an id that now points at someone's own event.
 */
interface CalendarGateway {
    /** Returns the new event id, or null if it couldn't be written. */
    suspend fun insertEvent(job: Job, startMillis: Long, endMillis: Long): Long?

    /** [owner] is the job as it was before this change. */
    suspend fun updateEvent(eventId: Long, owner: Job, title: String, notes: String, startMillis: Long, endMillis: Long): Boolean

    suspend fun deleteEvent(eventId: Long, owner: Job): Boolean

    suspend fun ownsEvent(eventId: Long, owner: Job): Boolean
}

/** What goes in the event's description: the job's notes, then where the event came from. */
fun eventDescription(notes: String): String {
    val trimmed = notes.trim()
    return if (trimmed.isEmpty()) EVENT_FOOTER else "$trimmed\n\n$EVENT_FOOTER"
}

private const val EVENT_FOOTER = "Scheduled from Job Jar. Mark it complete in the app and this event goes away."
