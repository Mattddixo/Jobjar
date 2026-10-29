package com.mattdixon.jobjar.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.ZoneId

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
    /**
     * Unit for [recurrenceDays] (which counts units, despite the name). Rows from before units
     * existed are DAY. See [repeatRule] for how these columns read together.
     */
    val repeatUnit: RepeatUnit = RepeatUnit.DAY,
    val repeatAnchor: RepeatAnchor = RepeatAnchor.COMPLETION,
    /** Monthly/yearly on a schedule: day of the month, or [LAST_DAY_OF_MONTH]. */
    val repeatMonthDay: Int? = null,
    /** Yearly on a schedule: 1..12. */
    val repeatMonth: Int? = null,
    /** Back in the jar this many days before [nextDueAt]. */
    val leadDays: Int = 0,
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

/**
 * Due now? For repeating jobs that's about [nextDueAt], not [isDone], and a job with a lead window
 * counts from [leadDays] before it's due.
 */
fun Job.isPending(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Boolean =
    if (recurrenceDays != null) {
        nextDueAt == null || now >= showFrom(nextDueAt, leadDays, zone)
    } else {
        !isDone
    }

/** When the booked slot ends: start plus the job's estimate. Null if it isn't booked. */
fun Job.bookingEnd(): Long? = scheduledDate?.let { it + estimatedMinutes * 60_000L }

/** The booked slot has finished (whether or not the job got done). */
fun Job.bookingIsOver(now: Long = System.currentTimeMillis()): Boolean = bookingEnd()?.let { it < now } ?: false

/**
 * The booked slot came and went with the job still to do. A missed job is back in the jar and
 * shows a "Missed" note until it's started, finished or booked again.
 */
fun Job.isMissed(now: Long = System.currentTimeMillis()): Boolean = isPending(now) && bookingIsOver(now)

/** Booked for a slot that hasn't finished yet. Booked jobs stay out of the jar. */
fun Job.isBooked(now: Long = System.currentTimeMillis()): Boolean = scheduledDate != null && !bookingIsOver(now)

/**
 * Could the jar hand this out right now? Pending, and not already started or booked. A missed
 * booking counts as not booked. (The Jobs list still counts started/booked jobs as to-do, hence
 * two functions.)
 */
fun Job.isAvailableToDraw(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Boolean =
    isPending(now, zone) && !isInProgress && !isBooked(now)

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

