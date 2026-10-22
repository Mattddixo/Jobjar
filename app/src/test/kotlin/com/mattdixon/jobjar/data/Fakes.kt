package com.mattdixon.jobjar.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [JobDao] mirroring the SQL in the real one, so [JobRepository]'s rules can be
 * tested on the JVM. Keep each method's behaviour identical to its @Query.
 */
class FakeJobDao : JobDao {
    private val rows = MutableStateFlow<Map<Long, Job>>(emptyMap())
    private var nextId = 1L

    val all: List<Job> get() = rows.value.values.sortedBy { it.id }
    fun row(id: Long): Job = rows.value.getValue(id)

    private fun edit(id: Long, change: (Job) -> Job) {
        val current = rows.value[id] ?: return
        rows.value = rows.value + (id to change(current))
    }

    override fun getAllJobsFlow(): Flow<List<Job>> = rows.map { it.values.sortedBy { job -> job.id } }
    override fun getCategories(): Flow<List<String>> =
        rows.map { map -> map.values.map { it.category }.filter { it != "" }.distinct().sorted() }
    override suspend fun clearCategory(category: String) {
        rows.value = rows.value.mapValues { (_, j) -> if (j.category == category) j.copy(category = "") else j }
    }
    override fun getJobById(id: Long): Flow<Job?> = rows.map { it[id] }
    override suspend fun getJob(id: Long): Job? = rows.value[id]
    override suspend fun getAllJobs(): List<Job> = all
    override suspend fun getCategoriesOfOtherJobs(excludeJobId: Long): List<String> =
        all.filter { it.id != excludeJobId }.map { it.category }.filter { it != "" }.distinct()
    override suspend fun renameCategory(from: String, to: String) {
        rows.value = rows.value.mapValues { (_, j) -> if (j.category == from) j.copy(category = to) else j }
    }
    override fun getSubtasks(parentId: Long): Flow<List<Job>> =
        rows.map { map -> map.values.filter { it.parentId == parentId }.sortedBy { it.createdAt } }
    override suspend fun getSubtasksSnapshot(parentId: Long): List<Job> = all.filter { it.parentId == parentId }
    override suspend fun insert(job: Job): Long {
        val id = if (job.id == 0L) nextId++ else job.id.also { nextId = maxOf(nextId, it + 1) }
        rows.value = rows.value + (id to job.copy(id = id))
        return id
    }
    override suspend fun upsertAll(jobs: List<Job>) {
        jobs.forEach { insert(it) }
    }
    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
    override suspend fun update(job: Job) {
        if (job.id in rows.value) rows.value = rows.value + (job.id to job)
    }
    override suspend fun delete(job: Job) {
        rows.value = rows.value - job.id
    }
    override suspend fun incrementDrawCount(id: Long) = edit(id) { it.copy(timesDrawn = it.timesDrawn + 1) }
    override suspend fun markDone(id: Long, completedAt: Long) =
        edit(id) { it.copy(isDone = true, isInProgress = false, completedAt = completedAt) }
    override suspend fun markNotDone(id: Long) = edit(id) { it.copy(isDone = false, completedAt = null) }
    override suspend fun markInProgress(id: Long) = edit(id) { it.copy(isInProgress = true) }
    override suspend fun markNotInProgress(id: Long) = edit(id) { it.copy(isInProgress = false) }
    override suspend fun setSchedule(id: Long, scheduledDate: Long, calendarEventId: Long?) =
        edit(id) { it.copy(scheduledDate = scheduledDate, calendarEventId = calendarEventId, isInProgress = false) }
    override suspend fun clearSchedule(id: Long) = edit(id) { it.copy(scheduledDate = null, calendarEventId = null) }
    override suspend fun setCalendarEventId(id: Long, calendarEventId: Long?) = edit(id) { it.copy(calendarEventId = calendarEventId) }
    override suspend fun setNextDueAt(id: Long, nextDueAt: Long?) = edit(id) { it.copy(nextDueAt = nextDueAt) }
    override suspend fun cycleRepeating(id: Long, completedAt: Long, nextDueAt: Long) = edit(id) {
        it.copy(completedAt = completedAt, nextDueAt = nextDueAt, completionCount = it.completionCount + 1, isInProgress = false)
    }
    override suspend fun setEstimatedMinutes(id: Long, minutes: Int) = edit(id) { it.copy(estimatedMinutes = minutes) }
    override suspend fun clearDependenciesOn(subtaskId: Long) {
        rows.value = rows.value.mapValues { (_, j) -> if (j.dependsOnSubtaskId == subtaskId) j.copy(dependsOnSubtaskId = null) else j }
    }
}

/** A calendar that tags events with their job id, like the real one does. */
class FakeCalendar : CalendarGateway {
    data class Event(val jobId: Long, val title: String, val start: Long, val end: Long)

    val events = mutableMapOf<Long, Event>()
    private var nextId = 100L
    var permissionGranted = true

    override suspend fun insertEvent(job: Job, startMillis: Long, endMillis: Long): Long? {
        if (!permissionGranted) return null
        val id = nextId++
        events[id] = Event(job.id, job.title, startMillis, endMillis)
        return id
    }

    override suspend fun updateEvent(eventId: Long, owner: Job, title: String, startMillis: Long, endMillis: Long): Boolean {
        if (!ownsEvent(eventId, owner)) return false
        events[eventId] = Event(owner.id, title, startMillis, endMillis)
        return true
    }

    override suspend fun deleteEvent(eventId: Long, owner: Job): Boolean =
        ownsEvent(eventId, owner) && events.remove(eventId) != null

    override suspend fun ownsEvent(eventId: Long, owner: Job): Boolean = events[eventId]?.jobId == owner.id
}

object DirectTransactions : TransactionRunner {
    override suspend fun <R> run(block: suspend () -> R): R = block()
}
