package com.mattdixon.jobjar.ui.draw

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mattdixon.jobjar.data.DrawPick
import com.mattdixon.jobjar.data.DrawPreferences
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.SavedDrawSettings
import com.mattdixon.jobjar.data.isAvailableToDraw
import com.mattdixon.jobjar.data.remainingMinutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Parent title or subtask progress, for the card. */
data class DrawnJobContext(
    val parentTitle: String? = null,
    val subtaskDone: Int = 0,
    val subtaskTotal: Int = 0,
    val remainingMinutes: Int? = null
)

data class DrawnJobEntry(
    val job: Job,
    val context: DrawnJobContext?,
    val minutesUsed: Int
)

/** Jobs per draw. null = as many as fit, up to [MAX_BATCH_JOBS]. */
enum class DrawBatchSize(val label: String, val count: Int?) {
    ONE("1", 1),
    TWO("2", 2),
    THREE("3", 3),
    FOUR("4", 4),
    ALL("All", null)
}

const val MAX_BATCH_JOBS = 10

data class DrawUiState(
    val availableMinutes: Int = 30,
    /** 4+ hrs mode: ignores the time and always draws a single job. */
    val longJobsOnly: Boolean = false,
    /** Empty = any category. */
    val selectedCategories: Set<String> = emptySet(),
    val categories: List<String> = emptyList(),
    val batchSize: DrawBatchSize = DrawBatchSize.ONE,
    val drawnJobs: List<DrawnJobEntry> = emptyList(),
    /** Unused minutes after the last draw. */
    val remainingMinutesAfterDraw: Int = 0,
    val excludedIds: List<Long> = emptyList(),
    val isDrawing: Boolean = false,
    val noMatchFound: Boolean = false,
    /** Jobs that could be drawn right now. Drives the jar picture. */
    val pendingCount: Int = 0,
    val inProgressCount: Int = 0,
    val isLoaded: Boolean = false
)

class DrawViewModel(
    private val repository: JobRepository,
    private val appContext: Context
) : ViewModel() {
    private val _uiState: MutableStateFlow<DrawUiState>
    val uiState: StateFlow<DrawUiState>

    init {
        val saved = DrawPreferences.load(appContext)
        _uiState = MutableStateFlow(
            DrawUiState(
                availableMinutes = saved.availableMinutes,
                longJobsOnly = saved.longJobsOnly,
                selectedCategories = saved.selectedCategories,
                batchSize = DrawBatchSize.entries.find { it.name == saved.batchSizeName } ?: DrawBatchSize.ONE
            )
        )
        uiState = _uiState.asStateFlow()

        viewModelScope.launch {
            combine(repository.categories, repository.allJobsFlat) { categories, allJobs ->
                categories to allJobs
            }.collect { (categories, allJobs) ->
                // Drop categories that have been removed, or every draw would come up empty.
                val previousSelection = _uiState.value.selectedCategories
                val stillValidSelection = previousSelection.intersect(categories.toSet())
                _uiState.value = _uiState.value.copy(
                    categories = categories,
                    selectedCategories = stillValidSelection,
                    pendingCount = allJobs.count { it.isAvailableToDraw() },
                    inProgressCount = allJobs.count { it.isInProgress },
                    isLoaded = true
                )
                if (stillValidSelection != previousSelection) {
                    saveSettings(_uiState.value)
                }
            }
        }
    }

    /** Saves the panel settings (not the draw itself). */
    private fun saveSettings(state: DrawUiState) {
        DrawPreferences.save(
            appContext,
            SavedDrawSettings(
                availableMinutes = state.availableMinutes,
                longJobsOnly = state.longJobsOnly,
                selectedCategories = state.selectedCategories,
                batchSizeName = state.batchSize.name
            )
        )
    }

    fun setAvailableMinutes(minutes: Int) {
        _uiState.value = _uiState.value.copy(availableMinutes = minutes, longJobsOnly = false)
        saveSettings(_uiState.value)
    }

    fun setLongJobsOnly() {
        _uiState.value = _uiState.value.copy(longJobsOnly = true)
        saveSettings(_uiState.value)
    }

    fun toggleCategory(category: String) {
        _uiState.value = _uiState.value.copy(
            selectedCategories = _uiState.value.selectedCategories.let {
                if (category in it) it - category else it + category
            }
        )
        saveSettings(_uiState.value)
    }

    fun setBatchSize(size: DrawBatchSize) {
        _uiState.value = _uiState.value.copy(batchSize = size)
        saveSettings(_uiState.value)
    }

    /**
     * Draws a batch: keep picking random jobs that fit what's left of the time until we have
     * enough or nothing fits. 4+ hrs mode draws one.
     *
     * [excludeCurrent] (Draw again) leaves out everything already shown.
     */
    fun draw(excludeCurrent: Boolean = false) {
        val current = _uiState.value
        val seedExcludeIds = if (excludeCurrent) {
            current.excludedIds + current.drawnJobs.map { it.job.id }
        } else {
            emptyList()
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDrawing = true, noMatchFound = false)

            val maxJobs = if (current.longJobsOnly) 1 else (current.batchSize.count ?: MAX_BATCH_JOBS)
            // Read each time so flipping it in Settings applies to the next draw.
            val favourFill = DrawPreferences.favourFill(appContext)
            val excluded = seedExcludeIds.toMutableList()
            val picks = mutableListOf<DrawPick>()
            var remaining = current.availableMinutes

            while (picks.size < maxJobs) {
                val pick = repository.drawJob(
                    maxMinutes = remaining,
                    categories = current.selectedCategories,
                    excludeIds = excluded,
                    longOnly = current.longJobsOnly,
                    // "All" has no fixed count, so each pick aims at whatever time is left.
                    picksLeft = if (current.longJobsOnly) 1 else current.batchSize.count?.let { it - picks.size },
                    favourFill = favourFill
                ) ?: break
                picks += pick
                excluded += pick.job.id
                remaining -= pick.minutesUsed
            }

            val entries = picks.map { pick -> DrawnJobEntry(pick.job, buildContext(pick.job), pick.minutesUsed) }

            _uiState.value = _uiState.value.copy(
                drawnJobs = entries,
                remainingMinutesAfterDraw = remaining,
                excludedIds = seedExcludeIds,
                isDrawing = false,
                noMatchFound = entries.isEmpty()
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
        _uiState.value = _uiState.value.copy(drawnJobs = emptyList(), excludedIds = emptyList(), noMatchFound = false)
    }

    /** Starts a drawn job and takes its card off the list. */
    fun startJob(jobId: Long) {
        val entry = _uiState.value.drawnJobs.find { it.job.id == jobId } ?: return
        viewModelScope.launch {
            repository.toggleInProgress(entry.job.id)
            _uiState.value = _uiState.value.copy(
                drawnJobs = _uiState.value.drawnJobs.filterNot { it.job.id == jobId }
            )
        }
    }

    /** Schedules a drawn job and takes its card off the list. */
    fun scheduleJob(jobId: Long, dateTimeMillis: Long) {
        val entry = _uiState.value.drawnJobs.find { it.job.id == jobId } ?: return
        viewModelScope.launch {
            repository.scheduleJob(entry.job.id, dateTimeMillis)
            _uiState.value = _uiState.value.copy(
                drawnJobs = _uiState.value.drawnJobs.filterNot { it.job.id == jobId }
            )
        }
    }

    class Factory(private val repository: JobRepository, private val appContext: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DrawViewModel(repository, appContext) as T
        }
    }
}
