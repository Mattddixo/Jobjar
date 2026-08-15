package com.mattdixon.jobjar.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

class JobRepository(private val dao: JobDao) {

    /** All rows, parents and subtasks. The single source of truth for stats and the draw candidate pool. */
    val allJobsFlat: Flow<List<Job>> = dao.getAllJobsFlow()

    /** Top-level jobs only - what the main Jobs list shows. */
    val topLevelJobs: Flow<List<Job>> = dao.getTopLevelJobs()

    val categories: Flow<List<String>> = dao.getCategories()

    fun jobById(id: Long): Flow<Job?> = dao.getJobById(id)
    fun subtasksOf(parentId: Long): Flow<List<Job>> = dao.getSubtasks(parentId)

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
     * that pointed at it, so nothing is left depending on a subtask that no longer exists.
     */
    suspend fun deleteJob(job: Job) {
        if (job.parentId == null) {
            dao.getSubtasksSnapshot(job.id).forEach { dao.delete(it) }
        } else {
            dao.getSubtasksSnapshot(job.parentId)
                .filter { it.dependsOnSubtaskId == job.id }
                .forEach { dao.update(it.copy(dependsOnSubtaskId = null)) }
        }
        dao.delete(job)
    }

    /**
     * A repeating job never persists isDone = true: "completing" it instead cycles it forward
     * via [cycleRepeatingJob]. If it's currently resting (not due yet) - shown as "done" in the
     * Jobs list - tapping it again means "make it available right now" instead, via
     * [wakeRepeatingJob]. A one-off job keeps the plain isDone flip.
     */
    suspend fun toggleDone(job: Job) {
        if (job.recurrenceDays != null) {
            if (job.isPending()) cycleRepeatingJob(job) else wakeRepeatingJob(job)
            return
        }
        if (job.isDone) {
            dao.markNotDone(job.id)
        } else {
            dao.markDone(job.id, System.currentTimeMillis())
            if (job.parentId != null) {
                autoCompleteParentIfFinished(job.parentId)
            }
        }
    }

    /** Clears a resting repeating job's schedule so it's immediately due again. Doesn't touch completionCount - the past completion still happened. */
    private suspend fun wakeRepeatingJob(job: Job) {
        dao.update(job.copy(nextDueAt = null))
    }

    /**
     * Advances a repeating job to its next occurrence: bumps [Job.completionCount], records
     * [Job.completedAt] as this moment, and schedules [Job.nextDueAt] this many days out from
     * now (not from any fixed calendar date - completing late just shifts the next one later
     * too). isDone is left false throughout, since the job isn't "finished," it's cycling.
     * Its own subtasks (if any) reset to not-done for the fresh cycle.
     */
    private suspend fun cycleRepeatingJob(job: Job) {
        val now = System.currentTimeMillis()
        dao.update(
            job.copy(
                completedAt = now,
                nextDueAt = now + job.recurrenceDays!! * DAY_MILLIS,
                completionCount = job.completionCount + 1
            )
        )
        dao.getSubtasksSnapshot(job.id)
            .filter { it.isDone }
            .forEach { dao.markNotDone(it.id) }
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
     * Draws one random eligible job matching (optionally) [category]. By default this matches
     * jobs that *fit* [maxMinutes] (a ceiling - "what can I do with the time I have"). Pass
     * [longOnly] = true to flip that to a floor instead - only jobs needing [LONG_JOB_MINUTES]
     * or more are eligible, ignoring [maxMinutes] entirely, for when you deliberately want to
     * pull a big project rather than something that merely fits.
     *
     * Eligibility is [Job.isPending], not just "not done": a repeating job that isn't due yet
     * is excluded even though it's technically not marked done, so the jar doesn't hand you a
     * chore ahead of its schedule. A subtask that's [Job.isUnblocked] = false (waiting on a
     * linked sibling subtask) is excluded here too - that's the only place the link matters,
     * since it's still fully completable by hand at any time.
     *
     * A top-level job with subtasks is matched by its *remaining* minutes (its own estimate
     * minus what completed subtasks already accounted for), so it becomes eligible for shorter
     * draws (or drops out of the long-task pool) as its subtasks get knocked out. Subtasks are
     * matched by their own estimate, same as any plain job.
     */
    suspend fun drawJob(maxMinutes: Int, category: String?, excludeIds: List<Long>, longOnly: Boolean = false): Job? {
        val all = allJobsFlat.first()
        val subtasksByParent = all.filter { it.parentId != null }.groupBy { it.parentId }
        val subtasksById = subtasksByParent.values.flatten().associateBy { it.id }

        val candidates = all.filter { job ->
            job.isPending() &&
                job.id !in excludeIds &&
                (category == null || job.category == category) &&
                (job.parentId == null || job.isUnblocked(subtasksById)) &&
                minutesNeeded(job, subtasksByParent).let { needed ->
                    if (longOnly) needed >= LONG_JOB_MINUTES else needed <= maxMinutes
                }
        }

        val picked = candidates.randomOrNull()
        picked?.let { dao.incrementDrawCount(it.id) }
        return picked
    }

    private fun minutesNeeded(job: Job, subtasksByParent: Map<Long?, List<Job>>): Int =
        if (job.parentId == null) {
            job.remainingMinutes(subtasksByParent[job.id].orEmpty())
        } else {
            job.estimatedMinutes
        }
}
