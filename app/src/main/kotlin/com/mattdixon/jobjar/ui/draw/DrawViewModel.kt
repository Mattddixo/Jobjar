package com.mattdixon.jobjar.ui.draw

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.remainingMinutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Extra context about the drawn job shown on its card: is it part of something bigger, or does it have subtasks of its own? */
data class DrawnJobContext(
    val parentTitle: String? = null,
    val subtaskDone: Int = 0,
    val subtaskTotal: Int = 0,
    val remainingMinutes: Int? = null
)

data class DrawUiState(
    val availableMinutes: Int = 30,
    /** When true, ignore [availableMinutes] and draw only from jobs needing 4+ hours - an explicit "give me a big one" instead of "what fits". */
    val longJobsOnly: Boolean = false,
    val selectedCategory: String? = null,
    val categories: List<String> = emptyList(),
    val drawnJob: Job? = null,
    val drawnContext: DrawnJobContext? = null,
    val excludedIds: List<Long> = emptyList(),
    val isDrawing: Boolean = false,
    val noMatchFound: Boolean = false
)

class DrawViewModel(private val repository: JobRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DrawUiState())
    val uiState: StateFlow<DrawUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.categories.collect { categories ->
                _uiState.value = _uiState.value.copy(categories = categories)
            }
        }
    }

    fun setAvailableMinutes(minutes: Int) {
        _uiState.value = _uiState.value.copy(availableMinutes = minutes, longJobsOnly = false)
    }

    fun setLongJobsOnly() {
        _uiState.value = _uiState.value.copy(longJobsOnly = true)
    }

    fun setCategory(category: String?) {
        _uiState.value = _uiState.value.copy(selectedCategory = category)
    }

    /** Draws a random eligible job. Pass [excludeCurrent] = true to redraw without repeating the job on screen. */
    fun draw(excludeCurrent: Boolean = false) {
        val current = _uiState.value
        val excludeIds = if (excludeCurrent && current.drawnJob != null) {
            current.excludedIds + current.drawnJob.id
        } else {
            emptyList()
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDrawing = true, noMatchFound = false)
            val job = repository.drawJob(
                maxMinutes = current.availableMinutes,
                category = current.selectedCategory,
                excludeIds = excludeIds,
                longOnly = current.longJobsOnly
            )
            val context = job?.let { buildContext(it) }
            _uiState.value = _uiState.value.copy(
                drawnJob = job,
                drawnContext = context,
                excludedIds = excludeIds,
                isDrawing = false,
                noMatchFound = job == null
            )
        }
    }

    private suspend fun buildContext(job: Job): DrawnJobContext {
        if (job.parentId != null) {
            val parentTitle = repository.jobById(job.parentId).first()?.title
            return DrawnJobContext(parentTitle = parentTitle)
        }
        val subtasks = repository.subtasksOf(job.id).first()
        if (subtasks.isEmpty()) return DrawnJobContext()
        return DrawnJobContext(
            subtaskDone = subtasks.count { it.isDone },
            subtaskTotal = subtasks.size,
            remainingMinutes = job.remainingMinutes(subtasks)
        )
    }

    fun clearDraw() {
        _uiState.value = _uiState.value.copy(drawnJob = null, drawnContext = null, excludedIds = emptyList(), noMatchFound = false)
    }

    fun completeDrawnJob() {
        val job = _uiState.value.drawnJob ?: return
        viewModelScope.launch {
            repository.toggleDone(job)
            clearDraw()
        }
    }

    class Factory(private val repository: JobRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DrawViewModel(repository) as T
        }
    }
}
