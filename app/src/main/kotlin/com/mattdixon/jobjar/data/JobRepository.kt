package com.mattdixon.jobjar.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.ZoneId

private const val MINUTE_MILLIS = 60L * 1000

/**
 * Exactly the rows a change touched, as they were just before it - handed back by every
 * undoable action so the UI can offer "Undo" and pass it to [JobRepository.undo].
 */
data class UndoSnapshot(val rows: List<Job>)

/**
 * Every write follows the same three rules:
 *
 * 1. It starts by re-reading the rows it changes ([JobDao.getJob]) instead of trusting the copy a
 *    screen passed in, so an action on a card that's been on screen for a while can't write back
 *    stale fields (e.g. undo an edit made in the meantime).
 * 2. All of its database writes run in one transaction ([TransactionRunner]) - completing a
 *    parent and sweeping its subtasks either all happens or none of it does.
 * 3. Calendar changes ([CalendarGateway]) happen outside that transaction, after it commits: they
 *    are slow IPC calls to another app, and shouldn't hold the database or be "rolled back" half
 *    way. Deletions queued during a write run once it's committed (see [writeThenSync]).
 */
class JobRepository(
    private val dao: JobDao,
    private val calendar: CalendarGateway,
    private val transactions: TransactionRunner,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {

    /** All rows, parents and subtasks. The single source of truth for stats, the draw candidate pool, and the Jobs list. */
    val allJobsFlat: Flow<List<Job>> = dao.getAllJobsFlow()

    val categories: Flow<List<String>> = dao.getCategories()

    fun jobById(id: Long): Flow<Job?> = dao.getJobById(id)
    fun subtasksOf(parentId: Long): Flow<List<Job>> = dao.getSubtasks(parentId)

    /** Detaches [category] from every job that currently has it (they end up with no category,
     * same as a fresh job before one's typed in) - there's no separate category entity to
     * delete, so this is what "removing" a category actually means. */
    suspend fun removeCategory(category: String) = dao.clearCategory(category)

    /** Calendar work to run once the current write has committed. */
    private class CalendarSync {
        val actions = mutableListOf<suspend (CalendarGateway) -> Unit>()
        fun deleteEvent(eventId: Long, owner: Job) {
            actions += { it.deleteEvent(eventId, owner) }
        }
    }

    private suspend fun <R> writeThenSync(block: suspend (CalendarSync) -> R): R {
        val sync = CalendarSync()
        val result = transactions.run { block(sync) }
        sync.actions.forEach { it(calendar) }
        return result
    }

    /**
     * "home" and "Home" are the same category. A typed category adopts the spelling already in
     * use, so the filter and draw lists never show near-duplicates.
     */
    private suspend fun canonicalCategory(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return dao.getCategoriesSnapshot().firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: trimmed
    }

    /** One-time tidy-up for data saved before [canonicalCategory] existed: merges categories that
     * differ only by case into whichever spelling most jobs use. Safe to run on every launch. */
    suspend fun mergeCategoryCase() = transactions.run {
        dao.getAllJobs()
            .filter { it.category.isNotBlank() }
            .groupBy { it.category.trim().lowercase() }
            .values
            .forEach { group ->
                val spellings = group.groupingBy { it.category }.eachCount()
                if (spellings.size > 1) {
                    val winner = spellings.maxBy { it.value }.key
                    spellings.keys.filter { it != winner }.forEach { dao.renameCategory(it, winner) }
                }
            }
    }

    /**
     * A repeating job can't have subtasks: a subtask is completed on its own schedule, while its
     * parent cycles on another, so the two never lined up. The add/edit and detail screens never
     * offer the combination; this is the backstop.
     */
    suspend fun addJob(job: Job): Long = transactions.run {
        val parent = job.parentId?.let { dao.getJob(it) }
        require(job.parentId == null || parent != null) { "Parent job no longer exists" }
        require(parent?.parentId == null) { "Subtasks can't have subtasks" }
        require(parent?.recurrenceDays == null) { "A repeating job can't have subtasks" }
        val repeats = job.parentId == null && job.recurrenceDays != null
        val weekdays = job.repeatWeekdays?.takeIf { repeats && it and EVERY_DAY_MASK != 0 }
        val id = dao.insert(
            job.copy(
                id = 0,
                title = job.title.trim(),
                category = canonicalCategory(job.category),
                recurrenceDays = if (repeats) job.recurrenceDays else null,
                repeatWeekdays = weekdays,
                nextDueAt = if (repeats) initialNextDue(weekdays, clock(), zone()) else null,
                dependsOnSubtaskId = if (job.parentId != null) job.dependsOnSubtaskId else null
            )
        )
        if (job.parentId != null) growParentToFitSubtasks(job.parentId)
        id
    }

    /**
     * Saves the add/edit form's fields onto the job's *current* row - title, notes, estimate,
     * category, priority, repeat settings, dependency - leaving everything else (done/in-progress
     * state, schedule, counts) exactly as it is now.
     *
     * Rules enforced here rather than trusted from the form:
     * - A job with subtasks can't repeat (saved as one-off; the screen disables the switch, this
     *   covers the moment before its subtask list has loaded).
     * - Turning repeat on clears a done flag (a repeating job never persists isDone) and any
     *   booked date (repeating jobs aren't scheduled).
     * - Changing *how* it repeats restarts the cycle; only tweaking an interval's length keeps the
     *   current due date.
     * - A booked job's calendar event follows a new title or estimate.
     */
    suspend fun updateJob(edited: Job) = writeThenSync { sync ->
        val fresh = dao.getJob(edited.id) ?: return@writeThenSync
        val hasSubtasks = dao.getSubtasksSnapshot(fresh.id).isNotEmpty()
        val repeats = edited.recurrenceDays != null && fresh.parentId == null && !hasSubtasks
        val recurrenceDays = if (repeats) edited.recurrenceDays else null
        val weekdays = edited.repeatWeekdays?.takeIf { repeats && it and EVERY_DAY_MASK != 0 }
        val now = clock()
        val nextDueAt = when {
            recurrenceDays == null -> null
            weekdays != fresh.repeatWeekdays -> initialNextDue(weekdays, now, zone())
            fresh.recurrenceDays == null -> null
            else -> fresh.nextDueAt
        }
        var merged = fresh.copy(
            title = edited.title.trim(),
            notes = edited.notes.trim(),
            estimatedMinutes = edited.estimatedMinutes,
            category = canonicalCategory(edited.category),
            priority = edited.priority,
            recurrenceDays = recurrenceDays,
            repeatWeekdays = weekdays,
            nextDueAt = nextDueAt,
            dependsOnSubtaskId = if (fresh.parentId != null) edited.dependsOnSubtaskId?.takeIf { it != fresh.id } else null
        )
        if (recurrenceDays != null) {
            merged = merged.copy(isDone = false)
            if (merged.scheduledDate != null) {
                fresh.calendarEventId?.let { sync.deleteEvent(it, fresh) }
                merged = merged.copy(scheduledDate = null, calendarEventId = null)
            }
        }
        dao.update(merged)
        if (merged.parentId != null) growParentToFitSubtasks(merged.parentId)

        val eventId = merged.calendarEventId
        val start = merged.scheduledDate
        if (eventId != null && start != null && (merged.title != fresh.title || merged.estimatedMinutes != fresh.estimatedMinutes)) {
            val end = start + merged.estimatedMinutes * MINUTE_MILLIS
            sync.actions += { it.updateEvent(eventId, fresh, merged.title, start, end) }
        }
    }

    /**
     * A parent's estimate is a floor, not a ceiling: if its subtasks now add up to more than
     * it does, the parent grows to match. This never shrinks the parent back down (deleting or
     * shrinking a subtask doesn't erase the original estimate), and it's what makes a rough
     * "4+ hours" starting estimate self-correct into an accurate total as the job gets broken
     * into real subtasks, instead of quietly understating how much is left.
     */
    private suspend fun growParentToFitSubtasks(parentId: Long) {
        val parent = dao.getJob(parentId) ?: return
        val subtaskTotal = dao.getSubtasksSnapshot(parentId).sumOf { it.estimatedMinutes }
        if (subtaskTotal > parent.estimatedMinutes) {
            dao.setEstimatedMinutes(parentId, subtaskTotal)
        }
    }

    /**
     * Deleting a parent takes its subtasks with it - there's no meaningful orphan state for
     * them. Deleting a single subtask instead clears [Job.dependsOnSubtaskId] on any sibling
     * that pointed at it, so nothing is left depending on a subtask that no longer exists. A
     * deleted job's calendar event (if it had one) is deleted too. Returns everything needed to
     * put it all back via [undo], or null if the job was already gone.
     */
    suspend fun deleteJob(jobId: Long): UndoSnapshot? = writeThenSync { sync ->
        val job = dao.getJob(jobId) ?: return@writeThenSync null
        val removed = if (job.parentId == null) listOf(job) + dao.getSubtasksSnapshot(job.id) else listOf(job)
        val dependents = if (job.parentId != null) {
            dao.getSubtasksSnapshot(job.parentId).filter { it.dependsOnSubtaskId == job.id }
        } else {
            emptyList()
        }
        dao.clearDependenciesOn(job.id)
        removed.forEach { row ->
            row.calendarEventId?.let { sync.deleteEvent(it, row) }
            dao.delete(row)
        }
        UndoSnapshot(removed + dependents)
    }

    /**
     * Puts every row in [snapshot] back exactly as it was, then re-creates any calendar event the
     * undone change removed (a completed or deleted job that had been booked).
     */
    suspend fun undo(snapshot: UndoSnapshot) {
        transactions.run { dao.upsertAll(snapshot.rows) }
        snapshot.rows.forEach { row ->
            val start = row.scheduledDate ?: return@forEach
            val eventId = row.calendarEventId
            if (eventId == null || !calendar.ownsEvent(eventId, row)) {
                val newId = calendar.insertEvent(row, start, start + row.estimatedMinutes * MINUTE_MILLIS)
                if (newId != eventId) dao.setCalendarEventId(row.id, newId)
            }
        }
    }

    /** The rows [toggleDone] can touch for [job]: itself, plus its subtasks or its parent and siblings. */
    private suspend fun familyOf(job: Job): List<Job> =
        if (job.parentId == null) {
            listOf(job) + dao.getSubtasksSnapshot(job.id)
        } else {
            listOfNotNull(dao.getJob(job.parentId)) + dao.getSubtasksSnapshot(job.parentId)
        }

    /**
     * A repeating job never persists isDone = true: "completing" it instead cycles it forward
     * via [cycleRepeatingJob]. If it's currently resting (not due yet) - shown as "done" in the
     * Jobs list - tapping it again means "make it available right now" instead. A one-off job
     * keeps the plain isDone flip; completing a scheduled one clears its schedule first - a
     * finished job has nothing left to keep booked on the calendar for. Completing a top-level
     * job also completes any of its own subtasks that aren't done yet (see
     * [completeOpenSubtasks]); reopening it undoes exactly that sweep (see
     * [reopenSubtasksCompletedWith]).
     *
     * Returns the rows as they were before, for [undo]; null if the job no longer exists.
     */
    suspend fun toggleDone(jobId: Long): UndoSnapshot? = writeThenSync { sync ->
        val job = dao.getJob(jobId) ?: return@writeThenSync null
        val before = UndoSnapshot(familyOf(job))
        val now = clock()
        when {
            job.recurrenceDays != null ->
                if (job.isPending(now)) cycleRepeatingJob(job, now) else dao.setNextDueAt(job.id, null)
            job.isDone -> {
                dao.markNotDone(job.id)
                if (job.parentId != null) {
                    reopenParentIfDone(job.parentId)
                } else {
                    job.completedAt?.let { reopenSubtasksCompletedWith(job.id, it) }
                }
            }
            else -> {
                clearSchedule(job, sync)
                dao.markDone(job.id, now)
                if (job.parentId != null) {
                    autoCompleteParentIfFinished(job.parentId, now)
                } else {
                    completeOpenSubtasks(job.id, now, sync)
                }
            }
        }
        before
    }

    private suspend fun clearSchedule(job: Job, sync: CalendarSync) {
        if (job.scheduledDate == null && job.calendarEventId == null) return
        job.calendarEventId?.let { sync.deleteEvent(it, job) }
        dao.clearSchedule(job.id)
    }

    /**
     * Completes every one of [parentId]'s own subtasks that isn't done yet, same as completing
     * each individually would (unscheduling first if it was booked). Subtasks can't themselves
     * repeat or have subtasks of their own, so this never needs to recurse.
     *
     * Each swept subtask is stamped with the parent's own [completedAt], exactly. That shared
     * timestamp is how [reopenSubtasksCompletedWith] later tells "closed by this sweep" apart
     * from "finished by hand" without needing a column of its own.
     */
    private suspend fun completeOpenSubtasks(parentId: Long, completedAt: Long, sync: CalendarSync) {
        dao.getSubtasksSnapshot(parentId)
            .filterNot { it.isDone }
            .forEach { subtask ->
                clearSchedule(subtask, sync)
                dao.markDone(subtask.id, completedAt)
            }
    }

    /**
     * Undoes [completeOpenSubtasks] when its parent is reopened: only subtasks carrying the
     * parent's exact [completedAt] were closed by that sweep and go back to open. Ones finished
     * by hand keep their own (earlier) timestamp and stay done - that work really happened. A
     * parent that auto-completed from its last subtask (see [autoCompleteParentIfFinished])
     * never shares a timestamp with any of them, so reopening it leaves every subtask done.
     */
    private suspend fun reopenSubtasksCompletedWith(parentId: Long, completedAt: Long) {
        dao.getSubtasksSnapshot(parentId)
            .filter { it.isDone && it.completedAt == completedAt }
            .forEach { dao.markNotDone(it.id) }
    }

    /**
     * Advances a repeating job to its next occurrence: bumps [Job.completionCount], records
     * [Job.completedAt] as this moment, and sets [Job.nextDueAt] from its schedule (see
     * [nextDueAfterCompletion]). isDone stays false - the job isn't finished, it's cycling - and
     * isInProgress is cleared, since the fresh cycle hasn't been started yet.
     */
    private suspend fun cycleRepeatingJob(job: Job, now: Long) {
        val nextDue = nextDueAfterCompletion(job.recurrenceDays!!, job.repeatWeekdays, now, zone())
        dao.cycleRepeating(job.id, completedAt = now, nextDueAt = nextDue)
    }

    private suspend fun autoCompleteParentIfFinished(parentId: Long, now: Long) {
        val siblings = dao.getSubtasksSnapshot(parentId)
        if (siblings.isEmpty() || !siblings.all { it.isDone }) return
        val parent = dao.getJob(parentId) ?: return
        if (parent.isDone || parent.recurrenceDays != null) return

        // Strictly later than every subtask's own completion, even within the same millisecond
        // as the one just ticked - otherwise reopenSubtasksCompletedWith would mistake that
        // hand-finished subtask for one the parent's completion swept closed.
        val latestSubtask = siblings.maxOf { it.completedAt ?: 0L }
        dao.markDone(parentId, maxOf(now, latestSubtask + 1))
    }

    /**
     * The other half of [autoCompleteParentIfFinished]: reopening one subtask after its parent
     * auto- or force-completed shouldn't leave the parent stuck done with a subtask now open
     * again under it.
     */
    private suspend fun reopenParentIfDone(parentId: Long) {
        val parent = dao.getJob(parentId) ?: return
        if (parent.isDone) dao.markNotDone(parentId)
    }

    /**
     * Flips a job's in-progress flag. Starting doesn't touch [Job.isDone] and is the only place
     * that sets it true; starting a scheduled job clears its schedule first, since you're now
     * actually working it rather than planning to later. Stopping just puts it back in the jar.
     */
    suspend fun toggleInProgress(jobId: Long) = writeThenSync { sync ->
        val job = dao.getJob(jobId) ?: return@writeThenSync
        if (job.isInProgress) {
            dao.markNotInProgress(job.id)
        } else {
            clearSchedule(job, sync)
            dao.markInProgress(job.id)
        }
    }

    /**
     * Books a job for [dateTimeMillis], with a duration matching its own estimate - excluding it
     * from the random draw immediately (see [Job.isAvailableToDraw]) - and writes a matching
     * event to the device calendar. Rescheduling moves that same event rather than creating a
     * second one; if it can't (deleted on the calendar side, or not provably ours), a fresh one
     * is written. Scheduling clears in-progress: booking it for later says you're not working it
     * right now.
     *
     * Returns whether a calendar event was written - the job is booked in the app either way.
     * Repeating jobs can't be scheduled (their own cycle already covers "when").
     */
    suspend fun scheduleJob(jobId: Long, dateTimeMillis: Long): Boolean {
        val job = dao.getJob(jobId) ?: return false
        if (job.recurrenceDays != null) return false
        val end = dateTimeMillis + job.estimatedMinutes * MINUTE_MILLIS
        val existing = job.calendarEventId
        val eventId = if (existing != null && calendar.updateEvent(existing, job, job.title, dateTimeMillis, end)) {
            existing
        } else {
            calendar.insertEvent(job, dateTimeMillis, end)
        }
        val stillExists = transactions.run {
            val current = dao.getJob(jobId)
            if (current != null) dao.setSchedule(jobId, dateTimeMillis, eventId)
            current != null
        }
        // Deleted while the calendar call was in flight - don't leave its event behind.
        if (!stillExists && eventId != null) {
            calendar.deleteEvent(eventId, job.copy(scheduledDate = dateTimeMillis))
        }
        return stillExists && eventId != null
    }

    /** Drops a job's schedule and deletes its calendar event, if it had one - back to a plain
     * pending job, eligible for the random draw again. */
    suspend fun unscheduleJob(jobId: Long) = writeThenSync { sync ->
        val job = dao.getJob(jobId) ?: return@writeThenSync
        clearSchedule(job, sync)
    }

    /**
     * Draws one random eligible job matching (optionally) [categories]. By default this matches
     * jobs that *fit* [maxMinutes] (a ceiling - "what can I do with the time I have"). Pass
     * [longOnly] = true to flip that to a floor instead - only jobs needing [LONG_JOB_MINUTES]
     * or more are eligible, ignoring [maxMinutes] entirely.
     *
     * Eligibility is [Job.isAvailableToDraw]: not done, due if repeating, not in progress, not
     * booked. A subtask is excluded two further ways, both only from this random pool - it stays
     * fully completable by hand at any time regardless: [Job.isUnblocked] = false (waiting on a
     * sibling), or [Job.isParentAvailable] = false (its parent is in progress).
     *
     * A top-level job with subtasks is matched by its *remaining* minutes, so it becomes eligible
     * for shorter draws as its subtasks get knocked out. Returns the pick paired with how many
     * minutes it counts against a time budget ([DrawPick.minutesUsed]).
     */
    suspend fun drawJob(maxMinutes: Int, categories: Set<String>, excludeIds: List<Long>, longOnly: Boolean = false): DrawPick? {
        val now = clock()
        val all = allJobsFlat.first()
        val allById = all.associateBy { it.id }
        val subtasksByParent = all.filter { it.parentId != null }.groupBy { it.parentId }
        val subtasksById = subtasksByParent.values.flatten().associateBy { it.id }

        val candidates = all.filter { job ->
            job.isAvailableToDraw(now) &&
                job.id !in excludeIds &&
                (categories.isEmpty() || job.category in categories) &&
                (job.parentId == null || job.isUnblocked(subtasksById)) &&
                (job.parentId == null || job.isParentAvailable(allById[job.parentId])) &&
                minutesNeeded(job, subtasksByParent).let { needed ->
                    if (longOnly) needed >= LONG_JOB_MINUTES else needed <= maxMinutes
                }
        }

        val picked = candidates.randomOrNull() ?: return null
        dao.incrementDrawCount(picked.id)
        return DrawPick(picked, minutesNeeded(picked, subtasksByParent))
    }

    private fun minutesNeeded(job: Job, subtasksByParent: Map<Long?, List<Job>>): Int =
        if (job.parentId == null) {
            job.remainingMinutes(subtasksByParent[job.id].orEmpty())
        } else {
            job.estimatedMinutes
        }

    /** Every row, for [JobExport]. */
    suspend fun exportJobs(): List<Job> = dao.getAllJobs()

    /**
     * Replaces everything with [jobs] (already validated by [JobExport.parse]), keeping their
     * ids so parent/dependency links stay intact. Calendar events aren't touched: an imported
     * event id is only ever acted on once it's proven to be this job's (see [CalendarGateway]).
     */
    suspend fun replaceAllJobs(jobs: List<Job>) = transactions.run {
        dao.deleteAll()
        dao.upsertAll(jobs)
    }
}

data class DrawPick(val job: Job, val minutesUsed: Int)
