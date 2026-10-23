package com.mattdixon.jobjar.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.isPending
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class CategoryStat(val category: String, val completedCount: Int, val totalMinutes: Int, val spentCents: Long = 0)

data class StatsUiState(
    val activeCount: Int = 0,
    val completedCount: Int = 0,
    val totalMinutesCompleted: Int = 0,
    /** Cost of everything completed. 0 hides it, so people who don't track costs never see it. */
    val spentCents: Long = 0,
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

/** A finished job, or a repeating job's total. */
private data class Contribution(val category: String, val count: Int, val minutes: Int)

private fun toUiState(allJobs: List<Job>): StatsUiState {
    // Every job and subtask, same as the Jar counts.
    val activeCount = allJobs.count { it.isPending() }

    val parentIdsWithSubtasks = allJobs.mapNotNull { it.parentId }.toSet()
    val repeatingParentIds = allJobs.filter { it.parentId == null && it.recurrenceDays != null }
        .map { it.id }
        .toSet()

    // Parents with subtasks aren't counted themselves, their subtasks already cover that time.
    // Subtasks of repeating parents are left out (older data only; that combo isn't allowed now).
    val oneOffCompleted = allJobs.filter { job ->
        job.isDone && (
            (job.parentId == null && job.id !in parentIdsWithSubtasks) ||
                (job.parentId != null && job.parentId !in repeatingParentIds)
            )
    }

    // Repeating jobs never stay done, so count them from completionCount.
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

    // Money is counted separately from time: a parent's own cost counts when the parent is
    // completed, each subtask's when it is, and a repeating job's every time.
    val spending = allJobs.mapNotNull { job ->
        val cost = job.costCents ?: return@mapNotNull null
        val times = when {
            job.recurrenceDays != null -> job.completionCount
            job.isDone && job.parentId !in repeatingParentIds -> 1
            else -> 0
        }
        if (times == 0) null else job.category.ifBlank { "Uncategorized" } to cost * times
    }
    val spentByCategory = spending.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }

    val byCategory = contributions.groupBy { it.category }
    val categoryStats = (byCategory.keys + spentByCategory.keys)
        .map { category ->
            val items = byCategory[category].orEmpty()
            CategoryStat(
                category = category,
                completedCount = items.sumOf { it.count },
                totalMinutes = items.sumOf { it.minutes },
                spentCents = spentByCategory[category] ?: 0
            )
        }
        .sortedByDescending { it.totalMinutes }

    return StatsUiState(
        activeCount = activeCount,
        completedCount = contributions.sumOf { it.count },
        totalMinutesCompleted = contributions.sumOf { it.minutes },
        spentCents = spending.sumOf { it.second },
        categoryStats = categoryStats
    )
}
