package com.mattdixon.jobjar.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

private const val DAY_MILLIS = 24L * 60 * 60 * 1000
private const val MINUTE_MILLIS = 60L * 1000

class JobRepository(private val dao: JobDao, appContext: Context) {

    private val calendarScheduler = CalendarScheduler(appContext)

    /** All rows, parents and subtasks. The single source of truth for stats, the draw candidate pool, and the Jobs list. */
    val allJobsFlat: Flow<List<Job>> = dao.getAllJobsFlow()

    val categories: Flow<List<String>> = dao.getCategories()

    /** Detaches [category] from every job that currently has it (they end up with no category,
     * same as a fresh job before one's typed in) - there's no separate category entity to
     * delete, so this is what "removing" a category actually means. */
    suspend fun removeCategory(category: String) = dao.clearCategory(category)

    fun jobById(id: Long): Flow<Job?> = dao.getJobById(id)

    /** Public entry point for [catchUpAllDueRepeatingCycles] - called once when the Jobs list
     * loads, so a repeating parent's subtasks that are still marked done from a cycle whose
     * nextDueAt has already passed show as fresh without requiring a draw or a direct tap on
     * that specific job first. */
    suspend fun refreshDueRepeatingSubtasks() = catchUpAllDueRepeatingCycles()

    fun subtasksOf(parentId: Long): Flow<List<Job>> = dao.getSubtasks(parentId)

    /** Targeted update for establishing (or clearing) a cross-app link, without touching any
     * other column - used by the "Link to existing" picker flow and the `linked` deep-link
     * return trip. See the "Interop with Home Jobs Tracker" README section. */
    suspend fun setLinkedTrackerJobId(id: Long, linkedTrackerJobId: Long?) = dao.setLinkedTrackerJobId(id, linkedTrackerJobId)

    suspend fun addJob(job: Job): Long {
        val id = dao.insert(job)
        if (job.parentId != null) growParentToFitSubtasks(job.parentId)
        return id
    }

    suspend fun updateJob(job: Job) {
        dao.update(job)
        if (job.parentId != null) growParentToFitSubtasks(job.parentId)
    }

    /**
     * A parent's estimate is a floor, not a ceiling: if its subtasks now add up to more than
     * it does, the parent grows to match. This never shrinks the parent back down (deleting or
     * shrinking a subtask doesn't erase the original estimate), and it's what makes a rough
     * "4+ hours" starting estimate self-correct into an accurate total as the job gets broken
     * into real subtasks, instead of quietly understating how much is left.
     */
    private suspend fun growParentToFitSubtasks(parentId: Long) {
        val parent = dao.getJobById(parentId).first() ?: return
        val subtaskTotal = dao.getSubtasksSnapshot(parentId).sumOf { it.estimatedMinutes }
        if (subtaskTotal > parent.estimatedMinutes) {
            dao.update(parent.copy(estimatedMinutes = subtaskTotal))
        }
    }

    /**
     * Deleting a parent takes its subtasks with it - there's no meaningful orphan state for
     * them. Deleting a single subtask instead clears [Job.dependsOnSubtaskId] on any sibling
     * that pointed at it, so nothing is left depending on a subtask that no longer exists. A
     * deleted job's calendar event (if it had one) is deleted too - nothing should linger on the
     * user's calendar for a job that no longer exists in the app.
     */
    suspend fun deleteJob(job: Job) {
        if (job.parentId == null) {
            dao.getSubtasksSnapshot(job.id).forEach {
                it.calendarEventId?.let { eventId -> calendarScheduler.deleteEvent(eventId) }
                dao.delete(it)
            }
        } else {
            dao.getSubtasksSnapshot(job.parentId)
                .filter { it.dependsOnSubtaskId == job.id }
                .forEach { dao.update(it.copy(dependsOnSubtaskId = null)) }
        }
        job.calendarEventId?.let { calendarScheduler.deleteEvent(it) }
        dao.delete(job)
    }

    /**
     * A repeating job never persists isDone = true: "completing" it instead cycles it forward
     * via [cycleRepeatingJob]. If it's currently resting (not due yet) - shown as "done" in the
     * Jobs list - tapping it again means "make it available right now" instead, via
     * [wakeRepeatingJob]. A one-off job keeps the plain isDone flip; completing a scheduled one
     * clears its schedule first (see [unscheduleJob]) - a finished job has nothing left to keep
     * booked on the calendar for. Completing a top-level job also completes any of its own
     * subtasks that aren't done yet (see [completeOpenSubtasks]) - otherwise force-completing one
     * with open subtasks (the Jobs list's confirm dialog exists for exactly that) would leave it
     * marked done while pieces underneath it were still open. Before any of that, [job] (or its
     * parent, if [job] is itself a subtask) gets a chance to catch up on a repeating cycle that's
     * already due - see [catchUpRepeatingCycle] - so a stale "done" left over from last cycle
     * doesn't get misread as this cycle's completion.
     */
    suspend fun toggleDone(job: Job) {
        val justReset = catchUpRepeatingCycle(job)

        if (job.recurrenceDays != null) {
            if (job.isPending()) cycleRepeatingJob(job) else wakeRepeatingJob(job)
            return
        }
        val effectivelyDone = job.isDone && job.id !in justReset
        if (effectivelyDone) {
            dao.markNotDone(job.id)
            if (job.parentId != null) {
                reopenParentIfDone(job.parentId)
            }
        } else {
            if (job.scheduledDate != null) unscheduleJob(job)
            dao.markDone(job.id, System.currentTimeMillis())
            if (job.parentId != null) {
                autoCompleteParentIfFinished(job.parentId)
            } else {
                completeOpenSubtasks(job.id)
            }
        }
    }

    /**
     * Completes every one of [parentId]'s own subtasks that isn't done yet, same as completing
     * each individually would (unscheduling first if it was booked). Subtasks can't themselves
     * repeat or have subtasks of their own, so this never needs to recurse or worry about the
     * repeating-job cycle. Goes through [dao] directly rather than [toggleDone] per subtask - that
     * would also re-check whether *this* parent should auto-complete on every single one, which
     * is redundant work for a parent this function is only ever called on right after it was
     * already marked done.
     */
    private suspend fun completeOpenSubtasks(parentId: Long) {
        val now = System.currentTimeMillis()
        dao.getSubtasksSnapshot(parentId)
            .filterNot { it.isDone }
            .forEach { subtask ->
                if (subtask.scheduledDate != null) unscheduleJob(subtask)
                dao.markDone(subtask.id, now)
            }
    }

    /**
     * A repeating parent's own subtasks finish the cycle the same way a one-off parent's would -
     * completed, not silently left open or snapped back open the instant the cycle wraps up (see
     * [cycleRepeatingJob]). Resetting them so the *next* cycle starts fresh is a separate, lazy
     * step instead of something [cycleRepeatingJob] does eagerly: it only runs here, the moment
     * something actually touches [job] or its repeating parent, and only once that parent's
     * [Job.nextDueAt] has actually arrived - never the instant the previous cycle's work
     * finished, which is what made completing the last subtask (or force-completing the parent)
     * look like it had just undone itself.
     *
     * Deliberately keyed on [Job.nextDueAt] being a *past, non-null* timestamp, not just
     * [Job.isPending] - `isPending` is also true for the entire stretch the user is still
     * actively checking off this same cycle's subtasks one at a time (before the parent has
     * cycled, `nextDueAt` is null, same as "never completed a cycle yet"), and treating that as
     * "stale" would reset subtask 1 back open the moment subtask 2 was completed. Once a real
     * elapsed `nextDueAt` is found, it's cleared back to null as part of the same cleanup -
     * `isPending` reads null the same as "due," so the parent stays exactly as due as it was,
     * but this cycle's own later subtask completions no longer look like next cycle's leftovers.
     *
     * Returns the ids of any subtask it reset, so a caller mid-toggle can tell its own
     * just-reset target apart from one genuinely still done from earlier in this same cycle.
     */
    private suspend fun catchUpRepeatingCycle(job: Job): Set<Long> {
        val parent = when {
            job.recurrenceDays != null -> job
            job.parentId != null -> dao.getJobById(job.parentId).first() ?: return emptySet()
            else -> return emptySet()
        }
        if (parent.recurrenceDays == null) return emptySet()
        val dueAt = parent.nextDueAt ?: return emptySet()
        if (dueAt > System.currentTimeMillis()) return emptySet()

        val stale = dao.getSubtasksSnapshot(parent.id).filter { it.isDone || it.isInProgress }
        stale.forEach { dao.resetForNewCycle(it.id) }
        dao.update(parent.copy(nextDueAt = null))
        return stale.mapTo(mutableSetOf()) { it.id }
    }

    /** [catchUpRepeatingCycle] for every repeating parent that's currently due, not just the one
     * job being directly interacted with - used by [drawJob] so the draw pool reflects a fresh
     * cycle's subtasks even for a job nobody has touched yet this cycle. */
    private suspend fun catchUpAllDueRepeatingCycles() {
        allJobsFlat.first()
            .filter { it.recurrenceDays != null && it.isPending() }
            .forEach { catchUpRepeatingCycle(it) }
    }

    /** Clears a resting repeating job's schedule so it's immediately due again, resetting its own
     * subtasks for this fresh cycle the same way the lazy catch-up would once nextDueAt elapsed
     * naturally (see [catchUpRepeatingCycle]) - waking it early by hand is the same "a new cycle
     * starts now" event, just user-triggered instead of time-triggered, and [catchUpRepeatingCycle]
     * itself can't catch this one: it runs *before* this function, while nextDueAt is still in
     * the future, so it correctly sees nothing stale yet. Doesn't touch completionCount - the
     * past completion still happened. */
    private suspend fun wakeRepeatingJob(job: Job) {
        dao.update(job.copy(nextDueAt = null))
        dao.getSubtasksSnapshot(job.id)
            .filter { it.isDone || it.isInProgress }
            .forEach { dao.resetForNewCycle(it.id) }
    }

    /**
     * Advances a repeating job to its next occurrence: bumps [Job.completionCount], records
     * [Job.completedAt] as this moment, and schedules [Job.nextDueAt] this many days out from
     * now (not from any fixed calendar date - completing late just shifts the next one later
     * too). isDone is left false throughout, since the job isn't "finished," it's cycling.
     * isInProgress is cleared too - the fresh cycle hasn't been started yet, regardless of
     * whether this occurrence was. Its own subtasks get the same "parent completed" treatment a
     * one-off parent's would (see [completeOpenSubtasks]) - finished, not left open and not
     * reset back open either. They only reset for the *next* cycle lazily, once it's actually
     * due (see [catchUpRepeatingCycle]).
     */
    private suspend fun cycleRepeatingJob(job: Job) {
        val now = System.currentTimeMillis()
        dao.update(
            job.copy(
                completedAt = now,
                nextDueAt = now + job.recurrenceDays!! * DAY_MILLIS,
                completionCount = job.completionCount + 1,
                isInProgress = false
            )
        )
        completeOpenSubtasks(job.id)
    }

    private suspend fun autoCompleteParentIfFinished(parentId: Long) {
        val siblings = dao.getSubtasksSnapshot(parentId)
        if (siblings.isEmpty() || !siblings.all { it.isDone }) return

        val parent = dao.getJobById(parentId).first() ?: return
        if (parent.recurrenceDays != null) {
            cycleRepeatingJob(parent)
        } else {
            dao.markDone(parentId, System.currentTimeMillis())
        }
    }

    /**
     * The other half of [autoCompleteParentIfFinished]: reopening one subtask after its parent
     * auto- or force-completed shouldn't leave the parent stuck done with a subtask now open
     * again under it. A repeating parent never persists isDone = true in the first place (see
     * [toggleDone]), so this is a no-op for it - only a plain parent can actually be reopened.
     */
    private suspend fun reopenParentIfDone(parentId: Long) {
        val parent = dao.getJobById(parentId).first() ?: return
        if (parent.isDone) {
            dao.markNotDone(parentId)
        }
    }

    /**
     * Flips a job's in-progress flag. Starting (false -> true) doesn't touch [Job.isDone] - a
     * job you're actively working on obviously isn't done yet - and is the only place that sets
     * it true; starting a scheduled job clears its schedule first (see [unscheduleJob]), since
     * you're now actually working it rather than planning to later. Reverting (true -> false)
     * just puts it back in the jar's draw pool, same as pausing; nothing else about the job
     * changes.
     */
    suspend fun toggleInProgress(job: Job) {
        if (job.isInProgress) {
            dao.markNotInProgress(job.id)
        } else {
            if (job.scheduledDate != null) unscheduleJob(job)
            dao.markInProgress(job.id)
        }
    }

    /**
     * Books [job] for [dateTimeMillis], with a duration matching its own [Job.estimatedMinutes] -
     * excluding it from the random draw immediately (see [Job.isAvailableToDraw]), not just once
     * that date arrives, since committing it to a day is itself the reason to stop handing it out
     * at random. Also writes a matching event to the device's Calendar Provider (see
     * [CalendarScheduler]), which the OS's own account sync pushes to the user's real calendar
     * with no further action from this app. Rescheduling an already-scheduled job updates that
     * same calendar event in place rather than creating a second one; if the update fails (e.g.
     * the event was deleted on the calendar side), this falls back to inserting a fresh one so
     * the job still ends up booked either way. Scheduling a job that's currently in progress
     * clears that flag - the mirror image of [toggleInProgress] clearing a schedule when a job is
     * started - since booking it for later is itself saying you're not working it right now; done
     * silently, without a confirmation dialog, same as that other direction.
     */
    suspend fun scheduleJob(job: Job, dateTimeMillis: Long) {
        val endMillis = dateTimeMillis + job.estimatedMinutes * MINUTE_MILLIS
        val existingEventId = job.calendarEventId
        val eventId = if (existingEventId != null && calendarScheduler.updateEvent(existingEventId, job.title, dateTimeMillis, endMillis)) {
            existingEventId
        } else {
            calendarScheduler.insertEvent(job.title, dateTimeMillis, endMillis)
        }
        dao.update(job.copy(scheduledDate = dateTimeMillis, calendarEventId = eventId, isInProgress = false))
    }

    /** Drops [job]'s schedule and deletes its calendar event, if it had one - back to a plain
     * pending job, eligible for the random draw again. */
    suspend fun unscheduleJob(job: Job) {
        job.calendarEventId?.let { calendarScheduler.deleteEvent(it) }
        dao.update(job.copy(scheduledDate = null, calendarEventId = null))
    }

    /**
     * Draws one random eligible job matching (optionally) [category]. By default this matches
     * jobs that *fit* [maxMinutes] (a ceiling - "what can I do with the time I have"). Pass
     * [longOnly] = true to flip that to a floor instead - only jobs needing [LONG_JOB_MINUTES]
     * or more are eligible, ignoring [maxMinutes] entirely, for when you deliberately want to
     * pull a big project rather than something that merely fits.
     *
     * Eligibility is [Job.isAvailableToDraw], not just "not done": a repeating job that isn't
     * due yet is excluded even though it's technically not marked done, so the jar doesn't hand
     * you a chore ahead of its schedule; a job that's already [Job.isInProgress] is excluded too,
     * so drawing again can't hand you something you're already working on. A subtask is excluded
     * two further ways, both only from this random pool - it stays fully completable by hand at
     * any time regardless of either: [Job.isUnblocked] = false (waiting on a linked sibling
     * subtask), or [Job.isParentAvailable] = false (its own parent is in progress - if you've
     * committed to the whole project, the jar shouldn't hand you a random piece of it too).
     *
     * A top-level job with subtasks is matched by its *remaining* minutes (its own estimate
     * minus what completed subtasks already accounted for), so it becomes eligible for shorter
     * draws (or drops out of the long-task pool) as its subtasks get knocked out. Subtasks are
     * matched by their own estimate, same as any plain job.
     *
     * Returns the pick paired with how many minutes it counts against a time budget
     * ([DrawPick.minutesUsed]) - the caller (multi-job batch draws) needs that to decrement a
     * running budget between picks without duplicating the parent-vs-subtask minutes logic.
     */
    suspend fun drawJob(maxMinutes: Int, categories: Set<String>, excludeIds: List<Long>, longOnly: Boolean = false): DrawPick? {
        catchUpAllDueRepeatingCycles()
        val all = allJobsFlat.first()
        val allById = all.associateBy { it.id }
        val subtasksByParent = all.filter { it.parentId != null }.groupBy { it.parentId }
        val subtasksById = subtasksByParent.values.flatten().associateBy { it.id }

        val candidates = all.filter { job ->
            job.isAvailableToDraw() &&
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
}

data class DrawPick(val job: Job, val minutesUsed: Int)
