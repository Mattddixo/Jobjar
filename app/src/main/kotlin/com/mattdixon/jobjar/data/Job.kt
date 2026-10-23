package com.mattdixon.jobjar.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** "4+ hrs" on the job form and the Jar tab. */
const val LONG_JOB_MINUTES = 240

/**
 * A job, or a subtask of one if [parentId] is set. Subtasks only go one level deep. Repeating
 * jobs can't be subtasks or have subtasks.
 *
 * Repeating jobs never stay done. Completing one moves [nextDueAt] forward and bumps
 * [completionCount] instead. Null [nextDueAt] means it's due now.
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
    /** Being worked on right now. Never true at the same time as [isDone]. */
    val isInProgress: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val timesDrawn: Int = 0,
    val parentId: Long? = null,
    val recurrenceDays: Int? = null,
    /**
     * Set when the job repeats on particular weekdays instead of every N days. Bitmask, Monday is
     * bit 0 (see [weekdayBit]). [recurrenceDays] is just 7 in that case.
     */
    val repeatWeekdays: Int? = null,
    val nextDueAt: Long? = null,
    val completionCount: Int = 0,
    /** Subtasks only: a sibling that has to be done first. */
    val dependsOnSubtaskId: Long? = null,
    /**
     * When the job is booked for. Booked jobs stay out of the jar. Starting or finishing the job
     * clears it. Never set on repeating jobs.
     */
    val scheduledDate: Long? = null,
    /** Calendar event for [scheduledDate], if one was written (permission can be off). */
    val calendarEventId: Long? = null,
    /**
     * What the job costs, in hundredths of the currency (cents). Null = no cost entered. A
     * parent's own cost doesn't include its subtasks; see [totalCostCents].
     */
    val costCents: Long? = null,
    /** Empty = no checklist. Only shown on the job page. */
    val checklist: List<ChecklistItem> = emptyList()
)

/** Due now? For repeating jobs that's about [nextDueAt], not [isDone]. */
fun Job.isPending(now: Long = System.currentTimeMillis()): Boolean =
    if (recurrenceDays != null) {
        nextDueAt == null || nextDueAt <= now
    } else {
        !isDone
    }

/**
 * Could the jar hand this out right now? Pending, and not already started or booked.
 * (The Jobs list still counts started/booked jobs as to-do, hence two functions.)
 */
fun Job.isAvailableToDraw(now: Long = System.currentTimeMillis()): Boolean =
    isPending(now) && !isInProgress && scheduledDate == null

/**
 * Is this subtask's prerequisite done? Only matters for the draw, you can still tick it off
 * by hand. [siblingsById]: the other subtasks of the same parent.
 */
fun Job.isUnblocked(siblingsById: Map<Long, Job>): Boolean {
    val prerequisiteId = dependsOnSubtaskId ?: return true
    return siblingsById[prerequisiteId]?.isDone ?: true
}

/** Subtasks of a job you've already started don't get drawn on their own. */
fun Job.isParentAvailable(parent: Job?): Boolean = parent?.isInProgress != true

/** Siblings the subtask [excludingSelfId] could depend on without making a loop. */
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
 * The job's cost plus its subtasks' costs. Null if none of them has a cost, so jobs without
 * costs don't show "$0" anywhere.
 */
fun totalCostCents(job: Job, subtasks: List<Job>): Long? {
    val costs = (listOf(job) + subtasks).mapNotNull { it.costCents }
    return if (costs.isEmpty()) null else costs.sum()
}

/** Parent estimate minus finished subtasks. Can go negative, which is fine. */
fun remainingMinutesOf(estimatedMinutes: Int, subtasks: List<Job>): Int =
    estimatedMinutes - subtasks.filter { it.isDone }.sumOf { it.estimatedMinutes }

fun Job.remainingMinutes(subtasks: List<Job>): Int = remainingMinutesOf(estimatedMinutes, subtasks)

/**
 * Estimate minus all subtask estimates, done or not. Used while splitting a job up, to show
 * how much time hasn't been handed out yet. Negative means the subtasks add up to more.
 */
fun unallocatedMinutesOf(estimatedMinutes: Int, subtasksTotalMinutes: Int): Int =
    estimatedMinutes - subtasksTotalMinutes

