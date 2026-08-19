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
    /** Empty = no category narrowing (draw from any category) - same convention as the Jobs list's own multiselect category filter. */
    val selectedCategories: Set<String> = emptySet(),
    val categories: List<String> = emptyList(),
    val batchSize: DrawBatchSize = DrawBatchSize.ONE,
    val drawnJobs: List<DrawnJobEntry> = emptyList(),
    /** Minutes of the budget left unused after the last draw - lets the UI explain a batch that came up short of the requested count. */
    val remainingMinutesAfterDraw: Int = 0,
    val excludedIds: List<Long> = emptyList(),
    val isDrawing: Boolean = false,
    val noMatchFound: Boolean = false,
    /**
     * How many jobs (parents and subtasks alike) could actually be drawn right now, per
     * [Job.isAvailableToDraw] - not done, due if repeating, and not already in progress. This
     * drives the jar glyph's fill level. Deliberately just a count, not a ratio against
     * completed work: that decays toward "always looks full" as your lifetime completed total
     * grows, which stops meaning anything after a while. If you want completion history, that's
     * what the Stats tab is for.
     */
    val pendingCount: Int = 0,
    /** How many jobs are currently in progress - shown alongside [pendingCount] since those jobs have left the jar but aren't done yet either. */
    val inProgressCount: Int = 0
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
                // A category removed elsewhere (Jobs list's "Manage categories") could otherwise
                // stay checked here indefinitely - it can no longer appear in this screen's own
                // dropdown to be unchecked by hand, and every draw would silently match nothing.
                val previousSelection = _uiState.value.selectedCategories
                val stillValidSelection = previousSelection.intersect(categories.toSet())
                _uiState.value = _uiState.value.copy(
                    categories = categories,
                    selectedCategories = stillValidSelection,
                    pendingCount = allJobs.count { it.isAvailableToDraw() },
                    inProgressCount = allJobs.count { it.isInProgress }
                )
                if (stillValidSelection != previousSelection) {
                    saveSettings(_uiState.value)
                }
            }
        }
    }

    /** Persists the control-panel settings a user sets on this screen (time budget, long-jobs
     * toggle, category, batch size) so they're still there on the next launch instead of
     * resetting to defaults every time. Deliberately excludes the actual draw result and
     * exclusion list below - those are this session's output, not a setting to remember. */
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
                    categories = current.selectedCategories,
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

    /**
     * Marks one job in the current batch as started and drops it from the visible list - a
     * drawn job isn't completed right away just by being drawn, so this is the only action a
     * batch card offers. From here it's tracked via the Jobs list' "In Progress" filter (or its
     * own detail page) until you actually mark it done there; any other jobs in the batch stay
     * put.
     */
    fun startJob(jobId: Long) {
        val entry = _uiState.value.drawnJobs.find { it.job.id == jobId } ?: return
        viewModelScope.launch {
            repository.toggleInProgress(entry.job)
            _uiState.value = _uiState.value.copy(
                drawnJobs = _uiState.value.drawnJobs.filterNot { it.job.id == jobId }
            )
        }
    }

    /**
     * Same shape as [startJob], for the batch card's "Schedule" button: sets [dateTimeMillis] as
     * the job's scheduled date (writing the calendar event) and drops it from the visible batch.
     * A drawn job can never already be scheduled - scheduling excludes a job from the draw pool
     * - so there's no unschedule counterpart needed here.
     */
    fun scheduleJob(jobId: Long, dateTimeMillis: Long) {
        val entry = _uiState.value.drawnJobs.find { it.job.id == jobId } ?: return
        viewModelScope.launch {
            repository.scheduleJob(entry.job, dateTimeMillis)
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
