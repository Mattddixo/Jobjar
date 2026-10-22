package com.mattdixon.jobjar.data

/**
 * Everything [JobRepository] needs from a calendar, behind an interface so the repository's
 * rules can be unit-tested without a device (see the fakes under `src/test`). The real
 * implementation is [CalendarScheduler].
 *
 * Every call that touches an existing event takes the [Job] it supposedly belongs to and must
 * only act if that event verifiably is that job's. A stored event id on its own is not proof:
 * ids are per-device row numbers, so after a backup is restored onto a new phone the same number
 * can belong to one of the user's own, unrelated events.
 */
interface CalendarGateway {
    /** Writes a new event for [job]. Returns its id, or null if it couldn't be written (no permission, no writable calendar). */
    suspend fun insertEvent(job: Job, startMillis: Long, endMillis: Long): Long?

    /** Moves/renames [eventId] if it belongs to [owner] (as it is *before* this change). False if it doesn't, or is gone. */
    suspend fun updateEvent(eventId: Long, owner: Job, title: String, startMillis: Long, endMillis: Long): Boolean

    /** Deletes [eventId] if it belongs to [owner]. False if it doesn't, or is already gone. */
    suspend fun deleteEvent(eventId: Long, owner: Job): Boolean

    /** Whether [eventId] still exists and belongs to [owner]. */
    suspend fun ownsEvent(eventId: Long, owner: Job): Boolean
}
