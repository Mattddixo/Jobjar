package com.mattdixon.jobjar.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
 * Minutes still "owed" on a parent job: its own estimate minus time already accounted for by
 * completed subtasks. Not clamped to zero - an over-allocated parent (subtasks estimate more
 * time than the parent itself) can go negative, which simply makes it match any time budget,
 * since there's little effort left to decide about.
 */
fun Job.remainingMinutes(subtasks: List<Job>): Int =
    estimatedMinutes - subtasks.filter { it.isDone }.sumOf { it.estimatedMinutes }

