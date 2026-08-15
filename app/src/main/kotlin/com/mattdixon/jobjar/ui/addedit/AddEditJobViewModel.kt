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
    val isSaved: Boolean = false,
    /** True once we're sure [parentId] reflects reality - false only while an existing job is still loading. */
    val isLoaded: Boolean = false
) {
    val isValid: Boolean
        get() = title.isNotBlank() && estimatedMinutes > 0 && (recurrenceDays == null || recurrenceDays > 0)
}

class AddEditJobViewModel(
    private val repository: JobRepository,
    private val jobId: Long?,
    private val parentId: Long?
) : ViewModel() {

    private val _formState = MutableStateFlow(
        AddEditFormState(id = jobId, parentId = parentId, isLoaded = jobId == null)
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

    fun save() {
        if (!_formState.value.isValid) return
        viewModelScope.launch {
            persist()
            _formState.value = _formState.value.copy(isSaved = true)
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
                    recurrenceDays = state.recurrenceDays
                )
            )
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
                    nextDueAt = if (state.recurrenceDays == null) null else existing.nextDueAt
                )
            )
        }
        return state.id
    }

    class Factory(
        private val repository: JobRepository,
        private val jobId: Long?,
        private val parentId: Long? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AddEditJobViewModel(repository, jobId, parentId) as T
        }
    }
}
