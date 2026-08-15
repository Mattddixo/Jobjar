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

    @Query("SELECT * FROM jobs WHERE parentId IS NULL ORDER BY createdAt DESC")
    fun getTopLevelJobs(): Flow<List<Job>>

    @Query("SELECT DISTINCT category FROM jobs WHERE category != '' ORDER BY category ASC")
    fun getCategories(): Flow<List<String>>

    @Query("SELECT * FROM jobs WHERE id = :id")
    fun getJobById(id: Long): Flow<Job?>

    @Query("SELECT * FROM jobs WHERE parentId = :parentId ORDER BY createdAt ASC")
    fun getSubtasks(parentId: Long): Flow<List<Job>>

    @Query("SELECT * FROM jobs WHERE parentId = :parentId")
    suspend fun getSubtasksSnapshot(parentId: Long): List<Job>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: Job): Long

    @Update
    suspend fun update(job: Job)

    @Delete
    suspend fun delete(job: Job)

    @Query("UPDATE jobs SET timesDrawn = timesDrawn + 1 WHERE id = :id")
    suspend fun incrementDrawCount(id: Long)

    @Query("UPDATE jobs SET isDone = 1, completedAt = :completedAt WHERE id = :id")
    suspend fun markDone(id: Long, completedAt: Long)

    @Query("UPDATE jobs SET isDone = 0, completedAt = NULL WHERE id = :id")
    suspend fun markNotDone(id: Long)
}
