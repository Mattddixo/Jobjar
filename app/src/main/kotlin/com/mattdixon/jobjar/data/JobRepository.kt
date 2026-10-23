package com.mattdixon.jobjar.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.ZoneId

private const val MINUTE_MILLIS = 60L * 1000

/** The rows a change touched, as they were before it. Pass to [JobRepository.undo]. */
data class UndoSnapshot(val rows: List<Job>)

/**
 * All the job rules live here.
 *
 * Writes always re-read the rows from the database rather than trusting whatever copy the
 * screen passed in, and each write is one transaction. Calendar calls happen after the
 * transaction commits (see [writeThenSync]) since they're slow and can't be rolled back.
 */
class JobRepository(
    private val dao: JobDao,
    private val calendar: CalendarGateway,
    private val transactions: TransactionRunner,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {
    val allJobsFlat: Flow<List<Job>> = dao.getAllJobsFlow()

    val categories: Flow<List<String>> = dao.getCategories()

    fun jobById(id: Long): Flow<Job?> = dao.getJobById(id)
    fun subtasksOf(parentId: Long): Flow<List<Job>> = dao.getSubtasks(parentId)

    suspend fun removeCategory(category: String) = dao.clearCategory(category)

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

    /** Match the spelling other jobs already use ("home" -> "Home"). Ignores [jobId] itself. */
    private suspend fun canonicalCategory(raw: String, jobId: Long = 0): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return dao.getCategoriesOfOtherJobs(jobId).firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: trimmed
    }

    /** Merges categories that only differ by case. Runs on every launch, cheap. */
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

    /** Repeating jobs can't have subtasks. The UI never offers it; this is just a backstop. */
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
     * Saves the edit form onto the current row. Only the form's fields change; done/started/booked
     * state is left alone.
     *
     * - Jobs with subtasks can't repeat.
     * - Turning repeat on clears done and any booking.
     * - Switching repeat type restarts the cycle. Changing the number of days doesn't.
     * - If it's booked, the calendar event gets the new title, length and notes.
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
            category = canonicalCategory(edited.category, fresh.id),
            priority = edited.priority,
            costCents = edited.costCents,
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
        val eventChanged = merged.title != fresh.title || merged.estimatedMinutes != fresh.estimatedMinutes || merged.notes != fresh.notes
        if (eventId != null && start != null && eventChanged) {
            val end = start + merged.estimatedMinutes * MINUTE_MILLIS
            sync.actions += { it.updateEvent(eventId, fresh, merged.title, merged.notes, start, end) }
        }
    }

    /** Parent estimate grows to cover its subtasks. Never shrinks. */
    private suspend fun growParentToFitSubtasks(parentId: Long) {
        val parent = dao.getJob(parentId) ?: return
        val subtaskTotal = dao.getSubtasksSnapshot(parentId).sumOf { it.estimatedMinutes }
        if (subtaskTotal > parent.estimatedMinutes) {
            dao.setEstimatedMinutes(parentId, subtaskTotal)
        }
    }

    /**
     * Deletes the job (and its subtasks, if it's a parent) plus any calendar events. Siblings that
     * depended on a deleted subtask lose the dependency. Returns an undo snapshot, or null if the
     * job was already gone.
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

    /** Puts the rows back and re-adds any calendar events the change removed. */
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

    /** Rows [toggleDone] might change: the job plus its subtasks, or its parent and siblings. */
    private suspend fun familyOf(job: Job): List<Job> =
        if (job.parentId == null) {
            listOf(job) + dao.getSubtasksSnapshot(job.id)
        } else {
            listOfNotNull(dao.getJob(job.parentId)) + dao.getSubtasksSnapshot(job.parentId)
        }

    /**
     * Done/not done.
     *
     * - Repeating: completes the cycle, or if it's resting, makes it due now.
     * - Otherwise flips isDone. Completing clears any booking, and a parent takes its open subtasks
     *   with it. Reopening the parent reopens just those (see [reopenSubtasksCompletedWith]).
     *
     * Returns an undo snapshot, or null if the job doesn't exist.
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
     * Ticks off the parent's open subtasks. They get the parent's exact completedAt, which is how
     * [reopenSubtasksCompletedWith] knows which ones to put back.
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
     * Reopens the subtasks that were ticked off by the parent (same completedAt). Ones you'd
     * finished yourself stay done.
     */
    private suspend fun reopenSubtasksCompletedWith(parentId: Long, completedAt: Long) {
        dao.getSubtasksSnapshot(parentId)
            .filter { it.isDone && it.completedAt == completedAt }
            .forEach { dao.markNotDone(it.id) }
    }

    /** Next cycle: bump the count, set the next due date, clear in-progress and the checklist ticks. */
    private suspend fun cycleRepeatingJob(job: Job, now: Long) {
        val nextDue = nextDueAfterCompletion(job.recurrenceDays!!, job.repeatWeekdays, now, zone())
        dao.cycleRepeating(job.id, completedAt = now, nextDueAt = nextDue)
        if (job.checklist.any { it.checked }) {
            dao.setChecklist(job.id, job.checklist.map { it.copy(checked = false) })
        }
    }

    /** Saves the job's checklist as given (trimmed, blank lines dropped). Empty removes it. */
    suspend fun setChecklist(jobId: Long, items: List<ChecklistItem>) = updateChecklist(jobId) { items }

    /**
     * Applies [change] to the checklist as it is in the database right now, so quick edits in a
     * row (adding items one after another) can't overwrite each other.
     */
    suspend fun updateChecklist(jobId: Long, change: (List<ChecklistItem>) -> List<ChecklistItem>) = transactions.run {
        val job = dao.getJob(jobId) ?: return@run
        dao.setChecklist(jobId, cleanChecklist(change(job.checklist)))
    }

    /** Removes the whole checklist. Returns an undo snapshot, or null if there wasn't one. */
    suspend fun deleteChecklist(jobId: Long): UndoSnapshot? = transactions.run {
        val job = dao.getJob(jobId)?.takeIf { it.checklist.isNotEmpty() } ?: return@run null
        dao.setChecklist(jobId, emptyList())
        UndoSnapshot(listOf(job))
    }

    private suspend fun autoCompleteParentIfFinished(parentId: Long, now: Long) {
        val siblings = dao.getSubtasksSnapshot(parentId)
        if (siblings.isEmpty() || !siblings.all { it.isDone }) return
        val parent = dao.getJob(parentId) ?: return
        if (parent.isDone || parent.recurrenceDays != null) return

        // +1ms past the last subtask, so the parent never shares a timestamp with one you ticked
        // yourself (see reopenSubtasksCompletedWith).
        val latestSubtask = siblings.maxOf { it.completedAt ?: 0L }
        dao.markDone(parentId, maxOf(now, latestSubtask + 1))
    }

    /** Reopening a subtask reopens its parent. */
    private suspend fun reopenParentIfDone(parentId: Long) {
        val parent = dao.getJob(parentId) ?: return
        if (parent.isDone) dao.markNotDone(parentId)
    }

    /** Start/stop. Starting a booked job unbooks it. */
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
     * Books a job and writes a calendar event for it (moving the existing event if there is one).
     * Booked jobs stay out of the jar and aren't in progress. Repeating jobs can't be booked.
     *
     * Returns whether a calendar event was written. The booking is saved either way.
     */
    suspend fun scheduleJob(jobId: Long, dateTimeMillis: Long): Boolean {
        val job = dao.getJob(jobId) ?: return false
        if (job.recurrenceDays != null) return false
        val end = dateTimeMillis + job.estimatedMinutes * MINUTE_MILLIS
        val existing = job.calendarEventId
        val eventId = if (existing != null && calendar.updateEvent(existing, job, job.title, job.notes, dateTimeMillis, end)) {
            existing
        } else {
            calendar.insertEvent(job, dateTimeMillis, end)
        }
        val stillExists = transactions.run {
            val current = dao.getJob(jobId)
            if (current != null) dao.setSchedule(jobId, dateTimeMillis, eventId)
            current != null
        }
        // Deleted while we were talking to the calendar, so clean up the event.
        if (!stillExists && eventId != null) {
            calendar.deleteEvent(eventId, job.copy(scheduledDate = dateTimeMillis))
        }
        return stillExists && eventId != null
    }

    suspend fun unscheduleJob(jobId: Long) = writeThenSync { sync ->
        val job = dao.getJob(jobId) ?: return@writeThenSync
        clearSchedule(job, sync)
    }

    /**
     * Picks a random job that fits [maxMinutes], or with [longOnly] a random job of
     * [LONG_JOB_MINUTES] or more.
     *
     * Skips anything done, not due, started or booked, plus subtasks whose prerequisite isn't done
     * or whose parent has been started. Parents count by their remaining minutes, so a big job gets
     * drawable for shorter slots as you chip away at it.
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

    suspend fun exportJobs(): List<Job> = dao.getAllJobs()

    /**
     * Import: replaces everything, keeping the ids so links survive. Then the calendar is brought
     * in line: events for jobs that are gone (or no longer booked) are deleted, and upcoming
     * bookings that don't have an event on this phone get one. Calendar ids in the file are only
     * ever used after the ownership check.
     */
    suspend fun replaceAllJobs(jobs: List<Job>) {
        val old = transactions.run {
            val previous = dao.getAllJobs()
            dao.deleteAll()
            dao.upsertAll(jobs)
            previous
        }
        val keptEvents = jobs.mapNotNullTo(mutableSetOf()) { job ->
            job.calendarEventId?.takeIf { job.scheduledDate != null }?.let { job.id to it }
        }
        old.forEach { row ->
            val eventId = row.calendarEventId ?: return@forEach
            if ((row.id to eventId) !in keptEvents) calendar.deleteEvent(eventId, row)
        }
        val now = clock()
        jobs.forEach { row ->
            val start = row.scheduledDate?.takeIf { it > now } ?: return@forEach
            val eventId = row.calendarEventId
            if (eventId == null || !calendar.ownsEvent(eventId, row)) {
                val newId = calendar.insertEvent(row, start, start + row.estimatedMinutes * MINUTE_MILLIS)
                if (newId != eventId) dao.setCalendarEventId(row.id, newId)
            }
        }
    }
}

data class DrawPick(val job: Job, val minutesUsed: Int)
