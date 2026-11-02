package com.mattdixon.jobjar.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One time a job got done, for the history. Kept even if the job is deleted later, so it
 * copies the title, length and category at the time.
 *
 * Logged when you tick something off (and when a parent finishes because its last subtask did).
 * Subtasks ticked off along with their parent aren't logged separately.
 */
@Entity(tableName = "completions", indices = [Index("completedAt"), Index("jobId")])
data class Completion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val title: String,
    val estimatedMinutes: Int,
    val category: String = "",
    val completedAt: Long
)

@Dao
interface CompletionDao {
    @Insert
    suspend fun insert(completion: Completion): Long

    @Insert
    suspend fun insertAll(completions: List<Completion>)

    @Query("DELETE FROM completions WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    /** Unticking a job takes back the completion it logged. */
    @Query("DELETE FROM completions WHERE jobId = :jobId AND completedAt = :completedAt")
    suspend fun deleteFor(jobId: Long, completedAt: Long)

    @Query("DELETE FROM completions")
    suspend fun deleteAll()

    @Query("SELECT * FROM completions ORDER BY completedAt ASC")
    suspend fun getAll(): List<Completion>

    /** Newest first, from [from] (inclusive) to [to] (exclusive). */
    @Query("SELECT * FROM completions WHERE completedAt >= :from AND completedAt < :to ORDER BY completedAt DESC")
    fun between(from: Long, to: Long): Flow<List<Completion>>

    @Query("SELECT * FROM completions WHERE jobId = :jobId ORDER BY completedAt DESC LIMIT :limit")
    fun recentFor(jobId: Long, limit: Int): Flow<List<Completion>>
}
