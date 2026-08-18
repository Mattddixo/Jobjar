package com.mattdixon.jobjar.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** The threshold, in minutes, at which a job is treated as "long" - the "4+ hrs" option on both the job form and the draw screen. */
const val LONG_JOB_MINUTES = 240

/**
 * A job can optionally be a subtask of another job via [parentId]. Subtasks are one level
 * deep only - a job that already has a parent cannot itself have subtasks. Only a top-level
 * job (one that isn't itself a subtask) can repeat.
 *
 * A repeating job (non-null [recurrenceDays]) never persists [isDone] = true - completing it
 * instead advances [nextDueAt] and bumps [completionCount] (see [JobRepository]), so the same
 * row just cycles rather than piling up a new "done" row per occurrence. [nextDueAt] is the
 * schedule: null means due right now (true for a fresh repeating job that's never been
 * completed yet, or a due one); once completed it's set to that moment plus [recurrenceDays]
 * days, i.e. the schedule is relative to when you actually did it, not a fixed calendar date.
 */
@Entity(tableName = "jobs", indices = [Index("parentId")])
data class Job(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    val estimatedMinutes: Int,
    val category: String = "",
    val priority: Priority = Priority.NORMAL,
    val isDone: Boolean = false,
    /**
     * Actively being worked on right now. Deliberately a separate flag from [isDone] rather than
     * folding both into one tri-state status column: [isDone]'s state machine (repeating-job
     * cycling, parent auto-complete/reopen cascades) is already carefully correct, and layering
     * in-progress on top as an orthogonal bit avoids touching any of that. The only invariant
     * this flag has to maintain is "never true at the same time as [isDone]" - every path that
     * sets isDone = true also clears this (see [JobDao.markDone], [JobRepository.cycleRepeatingJob]).
     */
    val isInProgress: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val timesDrawn: Int = 0,
    val parentId: Long? = null,
    val recurrenceDays: Int? = null,
    val nextDueAt: Long? = null,
    val completionCount: Int = 0,
    /** Only meaningful when this is itself a subtask: the sibling subtask (same [parentId]) that must be done first, if any. */
    val dependsOnSubtaskId: Long? = null
)

/**
 * Is this job something you could act on right now? For a repeating job that's a due-date
 * check, not [isDone] (which a repeating job never persists as true - see [JobRepository]).
 */
fun Job.isPending(now: Long = System.currentTimeMillis()): Boolean =
    if (recurrenceDays != null) {
        nextDueAt == null || nextDueAt <= now
    } else {
        !isDone
    }

/**
 * Whether this job could actually be pulled from the jar right now: [isPending] (not done, and
 * due if it's a repeating job) but also not already [isInProgress]. Kept separate from
 * [isPending] itself rather than folding the in-progress check into it, because the two mean
 * different things to different callers - the Jobs list's Active/Completed split wants the
 * broader "not done" sense of pending (an in-progress job is still "Active"), while the draw
 * pool and the jar's headline count want this narrower "genuinely available" sense.
 */
fun Job.isAvailableToDraw(now: Long = System.currentTimeMillis()): Boolean =
    isPending(now) && !isInProgress

/**
 * A blocked subtask (prerequisite not yet done) is only excluded from the random draw pool -
 * it's still fully completable by hand at any time, out of order if you want. [siblingsById]
 * should map every subtask sharing this job's [parentId] by id.
 */
fun Job.isUnblocked(siblingsById: Map<Long, Job>): Boolean {
    val prerequisiteId = dependsOnSubtaskId ?: return true
    return siblingsById[prerequisiteId]?.isDone ?: true
}

/**
 * True unless this subtask's own parent is currently in progress - the same "excluded from the
 * random draw, still fully completable by hand" treatment a dependency-blocked subtask already
 * gets (see [isUnblocked]), for the same reason: if you've committed to working through the
 * overall project, the jar shouldn't also hand you a random piece of it as its own separate
 * draw. [parent] is looked up by the caller (typically from a full job map by id); this only
 * answers the question given that lookup, same division of responsibility as [isUnblocked].
 */
fun Job.isParentAvailable(parent: Job?): Boolean = parent?.isInProgress != true

/**
 * Which of [siblings] (subtasks sharing the same parent) [excludingSelfId] could validly depend
 * on, without creating a cycle. A candidate is excluded if it already (transitively) depends on
 * the subtask being edited, since linking to it would close a loop.
 */
fun subtasksAvailableAsDependency(siblings: List<Job>, excludingSelfId: Long?): List<Job> {
    val byId = siblings.associateBy { it.id }
    fun eventuallyDependsOn(startId: Long, targetId: Long): Boolean {
        val seen = mutableSetOf<Long>()
        var current: Long? = startId
        while (current != null && seen.add(current)) {
            if (current == targetId) return true
            current = byId[current]?.dependsOnSubtaskId
        }
        return false
    }
    return siblings.filter { candidate ->
        candidate.id != excludingSelfId &&
            (excludingSelfId == null || !eventuallyDependsOn(candidate.id, excludingSelfId))
    }
}

/**
 * Minutes still "owed" against a parent's estimate: [estimatedMinutes] minus time already
 * accounted for by completed subtasks. Not clamped to zero - a parent whose subtasks were
 * never allowed to exceed it (see [JobRepository]'s grow-on-write invariant) shouldn't hit
 * this, but if it ever does, a negative remainder simply matches any time budget, since
 * there's little effort left to decide about.
 */
fun remainingMinutesOf(estimatedMinutes: Int, subtasks: List<Job>): Int =
    estimatedMinutes - subtasks.filter { it.isDone }.sumOf { it.estimatedMinutes }

fun Job.remainingMinutes(subtasks: List<Job>): Int = remainingMinutesOf(estimatedMinutes, subtasks)

/**
 * How much of [estimatedMinutes] is still unspoken-for while *building* a job's subtask list -
 * [estimatedMinutes] minus every subtask's own estimate, done or not. Deliberately a different
 * question from [remainingMinutesOf] (which only counts unfinished work, for "how much is left
 * to actually do"): a freshly created subtask is never done, so that metric can't tell you
 * anything useful while you're still dividing up the time - this one can, and can go negative,
 * which is the point - it's the cue that the subtasks you've sketched out already add up to more
 * than the total you typed.
 */
fun unallocatedMinutesOf(estimatedMinutes: Int, subtasks: List<Job>): Int =
    estimatedMinutes - subtasks.sumOf { it.estimatedMinutes }

