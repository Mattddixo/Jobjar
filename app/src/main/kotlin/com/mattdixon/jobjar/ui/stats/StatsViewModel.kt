package com.mattdixon.jobjar.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class CategoryStat(val category: String, val completedCount: Int, val totalMinutes: Int)

data class StatsUiState(
    val activeCount: Int = 0,
    val completedCount: Int = 0,
    val totalMinutesCompleted: Int = 0,
    val categoryStats: List<CategoryStat> = emptyList()
)

class StatsViewModel(repository: JobRepository) : ViewModel() {

    val uiState: StateFlow<StatsUiState> = repository.allJobsFlat
        .map(::toUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    class Factory(private val repository: JobRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StatsViewModel(repository) as T
        }
    }
}

private fun toUiState(allJobs: List<Job>): StatsUiState {
    val activeCount = allJobs.count { it.parentId == null && !it.isDone }

    // A parent that's been broken into subtasks is a container, not a unit of work in its own
    // right - its subtasks already account for that time. Counting the parent too (which
    // happens automatically once every subtask is done, since it then auto-completes) would
    // double up every minute they cover. So: count completed jobs that represent real,
    // granular effort - plain top-level jobs with no subtasks, and subtasks themselves (always
    // leaves) - and skip any parent that has subtasks, whether or not those are all done yet.
    val parentIdsWithSubtasks = allJobs.mapNotNull { it.parentId }.toSet()
    val completedForStats = allJobs.filter {
        it.isDone && (it.parentId != null || it.id !in parentIdsWithSubtasks)
    }

    val categoryStats = completedForStats
        .groupBy { it.category.ifBlank { "Uncategorized" } }
        .map { (category, jobs) ->
            CategoryStat(
                category = category,
                completedCount = jobs.size,
                totalMinutes = jobs.sumOf { it.estimatedMinutes }
            )
        }
        .sortedByDescending { it.totalMinutes }

    return StatsUiState(
        activeCount = activeCount,
        completedCount = completedForStats.size,
        totalMinutesCompleted = completedForStats.sumOf { it.estimatedMinutes },
        categoryStats = categoryStats
    )
}
