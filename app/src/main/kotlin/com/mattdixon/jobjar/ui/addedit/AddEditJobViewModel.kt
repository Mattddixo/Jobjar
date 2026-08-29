package com.mattdixon.jobjar.ui.addedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

data class AddEditFormState(
    val id: Long? = null,
    /** This job's own parent, once known - null means "not a subtask" (or not yet loaded). */
    val parentId: Long? = null,
    val title: String = "",
    val notes: String = "",
    val estimatedMinutes: Int = 15,
    val category: String = "",
    val priority: Priority = Priority.NORMAL,
    /** Null = one-off job. Non-null = repeats every this many days once completed. */
    val recurrenceDays: Int? = null,
    /** Only meaningful when [parentId] != null: the sibling subtask that must be done first, if any. */
    val dependsOnSubtaskId: Long? = null,
    /** Set only for a new job opened via a jobjar://newjob deep link (e.g. Home Jobs Tracker's
     * "Send to Job Jar" button) - the Tracker job it should remember as its origin once saved. */
    val linkedTrackerJobId: Long? = null,
    /** Set only for a new job opened via a jobjar://newjob deep link that carried a Tracker
     * scheduledDate - defaults to 9 AM local time on that date, since Tracker only tracks a date,
     * not a time. Shown as a removable prefill row rather than a full scheduling UI (see
     * AddEditJobScreen); clearing it here just drops the prefill, it never touches an existing
     * schedule the way [JobRepository.unscheduleJob] does, since there's nothing saved yet. */
    val scheduledDateMillis: Long? = null,
    val isSaved: Boolean = false,
    /** True only when [isSaved] resulted from this save creating a brand-new job, never from
     * editing an already-existing one - gates firing the `hometracker://linked` return callback
     * so it only ever fires once, at actual link-establishment time. */
    val wasJustCreated: Boolean = false,
    /** True once we're sure [parentId] reflects reality - false only while an existing job is still loading. */
    val isLoaded: Boolean = false
) {
    val isValid: Boolean
        get() = title.isNotBlank() && estimatedMinutes > 0 && (recurrenceDays == null || recurrenceDays > 0)
}

class AddEditJobViewModel(
    private val repository: JobRepository,
    private val jobId: Long?,
    private val parentId: Long?,
    prefillTitle: String? = null,
    prefillCategory: String? = null,
    sourceTrackerJobId: Long? = null,
    prefillEstimatedMinutes: Int? = null,
    prefillScheduledDate: String? = null
) : ViewModel() {

    private val _formState = MutableStateFlow(
        AddEditFormState(
            id = jobId,
            parentId = parentId,
            title = if (jobId == null) prefillTitle.orEmpty() else "",
            estimatedMinutes = if (jobId == null) prefillEstimatedMinutes ?: 15 else 15,
            category = if (jobId == null) prefillCategory.orEmpty() else "",
            linkedTrackerJobId = if (jobId == null) sourceTrackerJobId else null,
            scheduledDateMillis = if (jobId == null) prefillScheduledDate?.toStartOfDayNineAm() else null,
            isLoaded = jobId == null
        )
    )
    val formState: StateFlow<AddEditFormState> = _formState.asStateFlow()

    init {
        if (jobId != null) {
            viewModelScope.launch {
                val job = repository.jobById(jobId).first()
                _formState.value = if (job != null) {
                    AddEditFormState(
                        id = job.id,
                        parentId = job.parentId,
                        title = job.title,
                        notes = job.notes,
                        estimatedMinutes = job.estimatedMinutes,
                        category = job.category,
                        priority = job.priority,
                        recurrenceDays = job.recurrenceDays,
                        dependsOnSubtaskId = job.dependsOnSubtaskId,
                        linkedTrackerJobId = job.linkedTrackerJobId,
                        isLoaded = true
                    )
                } else {
                    _formState.value.copy(isLoaded = true)
                }
            }
        } else if (parentId != null) {
            // Prefill the new subtask's category from its parent, purely as a convenience.
            viewModelScope.launch {
                val parent = repository.jobById(parentId).first()
                if (parent != null && parent.category.isNotBlank()) {
                    _formState.value = _formState.value.copy(category = parent.category)
                }
            }
        }
    }

    fun setTitle(value: String) { _formState.value = _formState.value.copy(title = value) }
    fun setNotes(value: String) { _formState.value = _formState.value.copy(notes = value) }
    fun setEstimatedMinutes(value: Int) { _formState.value = _formState.value.copy(estimatedMinutes = value) }
    fun setCategory(value: String) { _formState.value = _formState.value.copy(category = value) }
    fun setPriority(value: Priority) { _formState.value = _formState.value.copy(priority = value) }

    /** Toggles repeating on/off. Turning it on defaults to weekly unless an interval was already set. */
    fun setRecurring(enabled: Boolean) {
        _formState.value = _formState.value.copy(
            recurrenceDays = if (enabled) _formState.value.recurrenceDays ?: 7 else null
        )
    }

    fun setRecurrenceDays(days: Int) { _formState.value = _formState.value.copy(recurrenceDays = days) }

    fun setDependsOn(id: Long?) { _formState.value = _formState.value.copy(dependsOnSubtaskId = id) }

    /** Drops the incoming Tracker scheduledDate prefill without saving anything - there's no
     * schedule to unschedule yet, this job doesn't even exist in the database. */
    fun clearPrefillSchedule() { _formState.value = _formState.value.copy(scheduledDateMillis = null) }

    fun save() {
        if (!_formState.value.isValid) return
        val wasCreate = _formState.value.id == null
        viewModelScope.launch {
            persist()
            _formState.value = _formState.value.copy(isSaved = true, wasJustCreated = wasCreate)
        }
    }

    /**
     * Ensures this job already exists in the database - inserting it now, using whatever's
     * currently in the form, if it's still an unsaved draft - then invokes [onReady] with its
     * id. This is what lets "Add subtask" work before you've explicitly hit Save: the job
     * behind the form quietly becomes real the moment you need to attach something to it.
     */
    fun ensurePersisted(onReady: (Long) -> Unit) {
        val current = _formState.value
        if (current.id != null) {
            onReady(current.id)
            return
        }
        if (!current.isValid) return
        viewModelScope.launch {
            onReady(persist())
        }
    }

    private suspend fun persist(): Long {
        val state = _formState.value
        if (state.id == null) {
            val newId = repository.addJob(
                Job(
                    title = state.title.trim(),
                    notes = state.notes.trim(),
                    estimatedMinutes = state.estimatedMinutes,
                    category = state.category.trim(),
                    priority = state.priority,
                    parentId = parentId,
                    recurrenceDays = state.recurrenceDays,
                    dependsOnSubtaskId = state.dependsOnSubtaskId,
                    linkedTrackerJobId = state.linkedTrackerJobId
                )
            )
            // Routed through the same repository.scheduleJob a manual Schedule action uses
            // (rather than just setting Job.scheduledDate directly), so a job created from a
            // Tracker date prefill gets a real calendar event too, not just the in-app flag.
            state.scheduledDateMillis?.let { millis ->
                repository.jobById(newId).first()?.let { created -> repository.scheduleJob(created, millis) }
            }
            _formState.value = _formState.value.copy(id = newId)
            return newId
        }
        val existing = repository.jobById(state.id).first()
        if (existing != null) {
            repository.updateJob(
                existing.copy(
                    title = state.title.trim(),
                    notes = state.notes.trim(),
                    estimatedMinutes = state.estimatedMinutes,
                    category = state.category.trim(),
                    priority = state.priority,
                    recurrenceDays = state.recurrenceDays,
                    // Turning repeat off clears the stale schedule; turning it on (or leaving
                    // it on) keeps whatever nextDueAt already existed.
                    nextDueAt = if (state.recurrenceDays == null) null else existing.nextDueAt,
                    dependsOnSubtaskId = state.dependsOnSubtaskId
                )
            )
        }
        return state.id
    }

    class Factory(
        private val repository: JobRepository,
        private val jobId: Long?,
        private val parentId: Long? = null,
        private val prefillTitle: String? = null,
        private val prefillCategory: String? = null,
        private val sourceTrackerJobId: Long? = null,
        private val prefillEstimatedMinutes: Int? = null,
        private val prefillScheduledDate: String? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AddEditJobViewModel(
                repository, jobId, parentId, prefillTitle, prefillCategory, sourceTrackerJobId,
                prefillEstimatedMinutes, prefillScheduledDate
            ) as T
        }
    }
}

/** Parses an ISO "yyyy-MM-dd" date (Tracker's scheduledDate format, which has no time
 * component) into an epoch-millis instant at 9 AM local time - a reasonable default work-start
 * time for a job that only specified a date, not a time. Malformed input is treated as absent
 * rather than crashing the screen. */
private fun String.toStartOfDayNineAm(): Long? = try {
    LocalDate.parse(this).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
} catch (e: DateTimeParseException) {
    null
}
