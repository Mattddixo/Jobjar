package com.mattdixon.jobjar.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Backup file for Settings > Export/Import. Plain JSON with every column, so newer versions can
 * still read old files. The iOS app reads and writes the same format.
 *
 * ```json
 * { "format": "job-jar", "version": 1, "exportedAt": 1761000000000, "jobs": [ { "id": 1, ... } ] }
 * ```
 */
object JobExport {
    private const val FORMAT = "job-jar"
    const val VERSION = 1

    fun write(jobs: List<Job>, exportedAt: Long): String {
        val array = JSONArray()
        jobs.sortedBy { it.id }.forEach { array.put(it.toJson()) }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", exportedAt)
            .put("jobs", array)
            .toString(2)
    }

    sealed interface ParseResult {
        data class Success(val jobs: List<Job>) : ParseResult
        data class Failure(val reason: Reason) : ParseResult
    }

    enum class Reason { NOT_A_BACKUP, NEWER_VERSION, CORRUPT }

    /**
     * Also fixes up anything the app itself would never save, in case the file was edited by hand:
     * missing or nested parents, subtasks under a repeating job, dependencies on non-siblings.
     */
    fun parse(text: String): ParseResult {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return ParseResult.Failure(Reason.NOT_A_BACKUP)
        }
        if (root.optString("format") != FORMAT) return ParseResult.Failure(Reason.NOT_A_BACKUP)
        if (root.optInt("version", 0) > VERSION) return ParseResult.Failure(Reason.NEWER_VERSION)
        val raw = try {
            val array = root.getJSONArray("jobs")
            (0 until array.length()).map { array.getJSONObject(it).toJob() }
        } catch (e: JSONException) {
            return ParseResult.Failure(Reason.CORRUPT)
        }
        if (raw.map { it.id }.toSet().size != raw.size || raw.any { it.id <= 0 }) {
            return ParseResult.Failure(Reason.CORRUPT)
        }
        return ParseResult.Success(sanitize(raw))
    }

    private fun sanitize(jobs: List<Job>): List<Job> {
        val byId = jobs.associateBy { it.id }
        // Parents first: must exist, be top level and not repeat.
        val withValidParents = jobs.map { job ->
            val parent = job.parentId?.let { byId[it] }
            if (job.parentId != null && (parent == null || parent.parentId != null || parent.recurrenceDays != null)) {
                job.copy(parentId = null)
            } else {
                job
            }
        }
        val fixedById = withValidParents.associateBy { it.id }
        return withValidParents.map { job ->
            val dependency = job.dependsOnSubtaskId?.let { fixedById[it] }
            val validDependency = job.parentId != null && dependency != null &&
                dependency.id != job.id && dependency.parentId == job.parentId
            val repeats = job.parentId == null && job.recurrenceDays != null && job.recurrenceDays > 0
            job.copy(
                dependsOnSubtaskId = if (validDependency) job.dependsOnSubtaskId else null,
                recurrenceDays = if (repeats) job.recurrenceDays else null,
                repeatWeekdays = if (repeats) job.repeatWeekdays?.takeIf { it and EVERY_DAY_MASK != 0 } else null,
                nextDueAt = if (repeats) job.nextDueAt else null,
                isDone = if (repeats) false else job.isDone,
                isInProgress = job.isInProgress && !(job.isDone && !repeats),
                scheduledDate = if (repeats) null else job.scheduledDate,
                calendarEventId = if (repeats || job.scheduledDate == null) null else job.calendarEventId,
                estimatedMinutes = job.estimatedMinutes.coerceAtLeast(1),
                title = job.title.ifBlank { "Untitled job" }
            )
        }
    }

    private fun Job.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("notes", notes)
        .put("estimatedMinutes", estimatedMinutes)
        .put("category", category)
        .put("priority", priority.name)
        .put("isDone", isDone)
        .put("isInProgress", isInProgress)
        .put("createdAt", createdAt)
        .put("completedAt", completedAt ?: JSONObject.NULL)
        .put("timesDrawn", timesDrawn)
        .put("parentId", parentId ?: JSONObject.NULL)
        .put("recurrenceDays", recurrenceDays ?: JSONObject.NULL)
        .put("repeatWeekdays", repeatWeekdays ?: JSONObject.NULL)
        .put("nextDueAt", nextDueAt ?: JSONObject.NULL)
        .put("completionCount", completionCount)
        .put("dependsOnSubtaskId", dependsOnSubtaskId ?: JSONObject.NULL)
        .put("scheduledDate", scheduledDate ?: JSONObject.NULL)
        .put("calendarEventId", calendarEventId ?: JSONObject.NULL)

    private fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
    private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null

    private fun JSONObject.toJob(): Job = Job(
        id = getLong("id"),
        title = getString("title"),
        notes = optString("notes", ""),
        estimatedMinutes = getInt("estimatedMinutes"),
        category = optString("category", ""),
        priority = Priority.entries.find { it.name == optString("priority") } ?: Priority.NORMAL,
        isDone = optBoolean("isDone", false),
        isInProgress = optBoolean("isInProgress", false),
        createdAt = optLong("createdAt", 0L),
        completedAt = longOrNull("completedAt"),
        timesDrawn = optInt("timesDrawn", 0),
        parentId = longOrNull("parentId"),
        recurrenceDays = intOrNull("recurrenceDays"),
        repeatWeekdays = intOrNull("repeatWeekdays"),
        nextDueAt = longOrNull("nextDueAt"),
        completionCount = optInt("completionCount", 0),
        dependsOnSubtaskId = longOrNull("dependsOnSubtaskId"),
        scheduledDate = longOrNull("scheduledDate"),
        calendarEventId = longOrNull("calendarEventId")
    )
}
