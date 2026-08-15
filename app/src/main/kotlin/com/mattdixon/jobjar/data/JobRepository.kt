package com.mattdixon.jobjar.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class JobRepository(private val dao: JobDao) {

    /** All rows, parents and subtasks. Used for stats and as the draw candidate pool. */
    val allJobsFlat: Flow<List<Job>> = dao.getAllJobsFlow()

    /** Top-level jobs only - what the main Jobs list shows. */
    val topLevelJobs: Flow<List<Job>> = dao.getTopLevelJobs()
    val activeTopLevelJobs: Flow<List<Job>> = dao.getActiveTopLevelJobs()

    /** Completed jobs across parents and subtasks, so finishing a subtask counts toward stats. */
    val completedJobs: Flow<List<Job>> = dao.getCompletedJobs()

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

    /** Deleting a parent takes its subtasks with it - there's no meaningful orphan state for them. */
    suspend fun deleteJob(job: Job) {
        if (job.parentId == null) {
            dao.getSubtasksSnapshot(job.id).forEach { dao.delete(it) }
        }
        dao.delete(job)
    }

    suspend fun toggleDone(job: Job) {
        if (job.isDone) {
            dao.markNotDone(job.id)
        } else {
            dao.markDone(job.id, System.currentTimeMillis())
            if (job.parentId != null) {
                autoCompleteParentIfFinished(job.parentId)
            }
        }
    }

    private suspend fun autoCompleteParentIfFinished(parentId: Long) {
        val siblings = dao.getSubtasksSnapshot(parentId)
        if (siblings.isNotEmpty() && siblings.all { it.isDone }) {
            dao.markDone(parentId, System.currentTimeMillis())
        }
    }

    /**
     * Draws one random, not-done job matching (optionally) [category]. By default this matches
     * jobs that *fit* [maxMinutes] (a ceiling - "what can I do with the time I have"). Pass
     * [longOnly] = true to flip that to a floor instead - only jobs needing [LONG_JOB_MINUTES]
     * or more are eligible, ignoring [maxMinutes] entirely, for when you deliberately want to
     * pull a big project rather than something that merely fits.
     *
     * A top-level job with subtasks is matched by its *remaining* minutes (its own estimate
     * minus what completed subtasks already accounted for), so it becomes eligible for shorter
     * draws (or drops out of the long-task pool) as its subtasks get knocked out. Subtasks are
     * matched by their own estimate, same as any plain job.
     */
    suspend fun drawJob(maxMinutes: Int, category: String?, excludeIds: List<Long>, longOnly: Boolean = false): Job? {
        val all = allJobsFlat.first()
        val subtasksByParent = all.filter { it.parentId != null }.groupBy { it.parentId }

        val candidates = all.filter { job ->
            !job.isDone &&
                job.id !in excludeIds &&
                (category == null || job.category == category) &&
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
