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
    val title: String = "",
    val notes: String = "",
    val estimatedMinutes: Int = 15,
    val category: String = "",
    val priority: Priority = Priority.NORMAL,
    val isSaved: Boolean = false
) {
    val isValid: Boolean get() = title.isNotBlank() && estimatedMinutes > 0
}

class AddEditJobViewModel(
    private val repository: JobRepository,
    private val jobId: Long?,
    private val parentId: Long?
) : ViewModel() {

    private val _formState = MutableStateFlow(AddEditFormState(id = jobId))
    val formState: StateFlow<AddEditFormState> = _formState.asStateFlow()

    init {
        if (jobId != null) {
            viewModelScope.launch {
                val job = repository.jobById(jobId).first()
                if (job != null) {
                    _formState.value = AddEditFormState(
                        id = job.id,
                        title = job.title,
                        notes = job.notes,
                        estimatedMinutes = job.estimatedMinutes,
                        category = job.category,
                        priority = job.priority
                    )
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

    fun save() {
        val state = _formState.value
        if (!state.isValid) return
        viewModelScope.launch {
            if (state.id == null) {
                repository.addJob(
                    Job(
                        title = state.title.trim(),
                        notes = state.notes.trim(),
                        estimatedMinutes = state.estimatedMinutes,
                        category = state.category.trim(),
                        priority = state.priority,
                        parentId = parentId
                    )
                )
            } else {
                val existing = repository.jobById(state.id).first()
                if (existing != null) {
                    repository.updateJob(
                        existing.copy(
                            title = state.title.trim(),
                            notes = state.notes.trim(),
                            estimatedMinutes = state.estimatedMinutes,
                            category = state.category.trim(),
                            priority = state.priority
                        )
                    )
                }
            }
            _formState.value = _formState.value.copy(isSaved = true)
        }
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
