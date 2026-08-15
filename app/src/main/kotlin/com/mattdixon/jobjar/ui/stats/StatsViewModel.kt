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

/** One unit of credited work: either a single one-off completion, or a repeating job's tally. */
private data class Contribution(val category: String, val count: Int, val minutes: Int)

private fun toUiState(allJobs: List<Job>): StatsUiState {
    val activeCount = allJobs.count { it.parentId == null && !it.isDone }

    val parentIdsWithSubtasks = allJobs.mapNotNull { it.parentId }.toSet()
    val repeatingParentIds = allJobs.filter { it.parentId == null && it.recurrenceDays != null }
        .map { it.id }
        .toSet()

    // A parent with subtasks is a container, not a unit of work itself - its subtasks already
    // account for that time (see growParentToFitSubtasks), so counting the parent too would
    // double up every minute they cover. So: count completed jobs that represent real,
    // granular effort - plain top-level jobs with no subtasks, and subtasks of a *non-repeating*
    // parent. Subtasks of a *repeating* parent are excluded here even when currently done,
    // because cycleRepeatingJob() resets them back to not-done on every cycle - crediting them
    // individually would make stats swing backward each time that reset happens. Their work is
    // credited once per cycle via the parent's own completionCount instead.
    val oneOffCompleted = allJobs.filter { job ->
        job.isDone && (
            (job.parentId == null && job.id !in parentIdsWithSubtasks) ||
                (job.parentId != null && job.parentId !in repeatingParentIds)
            )
    }

    // A repeating job never stays isDone = true (see Job.kt / JobRepository.cycleRepeatingJob),
    // so its completions would otherwise be invisible to stats entirely. completionCount is the
    // only record of how many times it's actually been done.
    val repeatingCompletions = allJobs.filter { it.recurrenceDays != null && it.completionCount > 0 }

    val contributions = oneOffCompleted.map {
        Contribution(it.category.ifBlank { "Uncategorized" }, count = 1, minutes = it.estimatedMinutes)
    } + repeatingCompletions.map {
        Contribution(
            it.category.ifBlank { "Uncategorized" },
            count = it.completionCount,
            minutes = it.completionCount * it.estimatedMinutes
        )
    }

    val categoryStats = contributions
        .groupBy { it.category }
        .map { (category, items) ->
            CategoryStat(
                category = category,
                completedCount = items.sumOf { it.count },
                totalMinutes = items.sumOf { it.minutes }
            )
        }
        .sortedByDescending { it.totalMinutes }

    return StatsUiState(
        activeCount = activeCount,
        completedCount = contributions.sumOf { it.count },
        totalMinutesCompleted = contributions.sumOf { it.minutes },
        categoryStats = categoryStats
    )
}
