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
    suspend fun updateEvent(eventId: Long, owner: Job, title: String, startMillis: Long, endMillis: Long): Boolean

    suspend fun deleteEvent(eventId: Long, owner: Job): Boolean

    suspend fun ownsEvent(eventId: Long, owner: Job): Boolean
}
