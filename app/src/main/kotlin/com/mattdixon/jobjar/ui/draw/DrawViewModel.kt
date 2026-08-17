package com.mattdixon.jobjar.ui.draw

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mattdixon.jobjar.data.DrawPick
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.isPending
import com.mattdixon.jobjar.data.remainingMinutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Extra context about a drawn job shown on its card: is it part of something bigger, or does it have subtasks of its own? */
data class DrawnJobContext(
    val parentTitle: String? = null,
    val subtaskDone: Int = 0,
    val subtaskTotal: Int = 0,
    val remainingMinutes: Int? = null
)

/**
 * One job in the current draw, paired with its display context and how many minutes it counted
 * against the time budget.
 */
data class DrawnJobEntry(
    val job: Job,
    val context: DrawnJobContext?,
    val minutesUsed: Int
)

/**
 * How many jobs a single draw should try to pack into the time budget. [count] = null means
 * "as many as fit," capped at [MAX_BATCH_JOBS] so a budget full of many tiny jobs can't pull an
 * unbounded number of cards into one draw.
 */
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
    /**
     * When true, ignore availableMinutes and draw only from jobs needing 4+ hours - an explicit
     * "give me a big one" instead of "what fits". Always draws exactly one job regardless of
     * [batchSize]: there's no "remaining budget" left to fill after a single open-ended pick.
     */
    val longJobsOnly: Boolean = false,
    val selectedCategory: String? = null,
    val categories: List<String> = emptyList(),
    val batchSize: DrawBatchSize = DrawBatchSize.ONE,
    val drawnJobs: List<DrawnJobEntry> = emptyList(),
    /** Minutes of the budget left unused after the last draw - lets the UI explain a batch that came up short of the requested count. */
    val remainingMinutesAfterDraw: Int = 0,
    val excludedIds: List<Long> = emptyList(),
    val isDrawing: Boolean = false,
    val noMatchFound: Boolean = false,
    /**
     * How many jobs (parents and subtasks alike) are currently pending - i.e. actually in the
     * jar right now, per [Job.isPending]. This drives the jar glyph's fill level. Deliberately
     * just a count, not a ratio against completed work: that decays toward "always looks full"
     * as your lifetime completed total grows, which stops meaning anything after a while. If you
     * want completion history, that's what the Stats tab is for.
     */
    val pendingCount: Int = 0
)

class DrawViewModel(private val repository: JobRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DrawUiState())
    val uiState: StateFlow<DrawUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.categories, repository.allJobsFlat) { categories, allJobs ->
                categories to allJobs
            }.collect { (categories, allJobs) ->
                _uiState.value = _uiState.value.copy(
                    categories = categories,
                    pendingCount = allJobs.count { it.isPending() }
                )
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

    fun setBatchSize(size: DrawBatchSize) {
        _uiState.value = _uiState.value.copy(batchSize = size)
    }

    /**
     * Draws a fresh batch: greedily picks random eligible jobs against the time budget - draw
     * one, subtract what it needs from the remaining budget, draw another that fits what's
     * left, and so on - stopping once [DrawUiState.batchSize] is reached or nothing eligible
     * fits the remaining budget anymore. "4+ hrs" mode always draws exactly one job regardless
     * of batchSize (see its doc comment).
     *
     * Pass [excludeCurrent] = true (Skip) to redraw the whole batch fresh, excluding every job
     * the current batch already showed - a plain draw has no exclusions and could in principle
     * reshow the same batch by chance, same as the single-job version this replaced.
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
            val excluded = seedExcludeIds.toMutableList()
            val picks = mutableListOf<DrawPick>()
            var remaining = current.availableMinutes

            while (picks.size < maxJobs) {
                val pick = repository.drawJob(
                    maxMinutes = remaining,
                    category = current.selectedCategory,
                    excludeIds = excluded,
                    longOnly = current.longJobsOnly
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

    /** Marks one job in the current batch done and drops it from the visible list - any other jobs in the batch stay put. */
    fun completeJob(jobId: Long) {
        val entry = _uiState.value.drawnJobs.find { it.job.id == jobId } ?: return
        viewModelScope.launch {
            repository.toggleDone(entry.job)
            _uiState.value = _uiState.value.copy(
                drawnJobs = _uiState.value.drawnJobs.filterNot { it.job.id == jobId }
            )
        }
    }

    class Factory(private val repository: JobRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DrawViewModel(repository) as T
        }
    }
}
