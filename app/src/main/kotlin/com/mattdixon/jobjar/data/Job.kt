package com.mattdixon.jobjar.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** The threshold, in minutes, at which a job is treated as "long" - the "4+ hrs" option on both the job form and the draw screen. */
const val LONG_JOB_MINUTES = 240

/**
 * A job can optionally be a subtask of another job via [parentId]. Subtasks are one level
 * deep only - a job that already has a parent cannot itself have subtasks.
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
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val timesDrawn: Int = 0,
    val parentId: Long? = null
) {
    val timeBucket: TimeBucket get() = TimeBucket.fromMinutes(estimatedMinutes)
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

