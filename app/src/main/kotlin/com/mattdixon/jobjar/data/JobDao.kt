package com.mattdixon.jobjar.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface JobDao {

    /** Every row, parents and subtasks alike. Used internally by the repository (draws, stats). */
    @Query("SELECT * FROM jobs")
    fun getAllJobsFlow(): Flow<List<Job>>

    @Query("SELECT DISTINCT category FROM jobs WHERE category != '' ORDER BY category ASC")
    fun getCategories(): Flow<List<String>>

    /** Category is a free-text field on Job, not its own table - "removing" one just clears it back to blank on every job that has it. */
    @Query("UPDATE jobs SET category = '' WHERE category = :category")
    suspend fun clearCategory(category: String)

    @Query("SELECT * FROM jobs WHERE id = :id")
    fun getJobById(id: Long): Flow<Job?>

    /** One-shot read of the current row - what every write in [JobRepository] starts from, so it
     * never writes back a stale copy a screen happened to be holding. */
    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun getJob(id: Long): Job?

    @Query("SELECT * FROM jobs")
    suspend fun getAllJobs(): List<Job>

    @Query("SELECT DISTINCT category FROM jobs WHERE category != ''")
    suspend fun getCategoriesSnapshot(): List<String>

    @Query("UPDATE jobs SET category = :to WHERE category = :from")
    suspend fun renameCategory(from: String, to: String)

    @Query("SELECT * FROM jobs WHERE parentId = :parentId ORDER BY createdAt ASC")
    fun getSubtasks(parentId: Long): Flow<List<Job>>

    @Query("SELECT * FROM jobs WHERE parentId = :parentId")
    suspend fun getSubtasksSnapshot(parentId: Long): List<Job>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: Job): Long

    /** Writes rows with their existing ids - undo and import put rows back exactly as they were. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(jobs: List<Job>)

    @Query("DELETE FROM jobs")
    suspend fun deleteAll()

    @Update
    suspend fun update(job: Job)

    @Delete
    suspend fun delete(job: Job)

    @Query("UPDATE jobs SET timesDrawn = timesDrawn + 1 WHERE id = :id")
    suspend fun incrementDrawCount(id: Long)

    // isInProgress is cleared here too - a job can't be done and in-progress at once, and this
    // is the one query every "mark done" path (including auto-complete-parent) routes through.
    @Query("UPDATE jobs SET isDone = 1, isInProgress = 0, completedAt = :completedAt WHERE id = :id")
    suspend fun markDone(id: Long, completedAt: Long)

    @Query("UPDATE jobs SET isDone = 0, completedAt = NULL WHERE id = :id")
    suspend fun markNotDone(id: Long)

    @Query("UPDATE jobs SET isInProgress = 1 WHERE id = :id")
    suspend fun markInProgress(id: Long)

    @Query("UPDATE jobs SET isInProgress = 0 WHERE id = :id")
    suspend fun markNotInProgress(id: Long)

    /** Booking a job for later also means it isn't being worked on right now. */
    @Query("UPDATE jobs SET scheduledDate = :scheduledDate, calendarEventId = :calendarEventId, isInProgress = 0 WHERE id = :id")
    suspend fun setSchedule(id: Long, scheduledDate: Long, calendarEventId: Long?)

    @Query("UPDATE jobs SET scheduledDate = NULL, calendarEventId = NULL WHERE id = :id")
    suspend fun clearSchedule(id: Long)

    @Query("UPDATE jobs SET calendarEventId = :calendarEventId WHERE id = :id")
    suspend fun setCalendarEventId(id: Long, calendarEventId: Long?)

    @Query("UPDATE jobs SET nextDueAt = :nextDueAt WHERE id = :id")
    suspend fun setNextDueAt(id: Long, nextDueAt: Long?)

    @Query("UPDATE jobs SET completedAt = :completedAt, nextDueAt = :nextDueAt, completionCount = completionCount + 1, isInProgress = 0 WHERE id = :id")
    suspend fun cycleRepeating(id: Long, completedAt: Long, nextDueAt: Long)

    @Query("UPDATE jobs SET estimatedMinutes = :minutes WHERE id = :id")
    suspend fun setEstimatedMinutes(id: Long, minutes: Int)

    @Query("UPDATE jobs SET dependsOnSubtaskId = NULL WHERE dependsOnSubtaskId = :subtaskId")
    suspend fun clearDependenciesOn(subtaskId: Long)
}
