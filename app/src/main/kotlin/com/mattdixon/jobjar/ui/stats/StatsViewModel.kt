package com.mattdixon.jobjar.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mattdixon.jobjar.data.JobRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class CategoryStat(val category: String, val completedCount: Int, val totalMinutes: Int)

data class StatsUiState(
    val activeCount: Int = 0,
    val completedCount: Int = 0,
    val totalMinutesCompleted: Int = 0,
    val categoryStats: List<CategoryStat> = emptyList()
)

class StatsViewModel(repository: JobRepository) : ViewModel() {

    val uiState: StateFlow<StatsUiState> = combine(
        repository.activeTopLevelJobs,
        repository.completedJobs
    ) { active, completed ->
        val byCategory = completed
            .groupBy { it.category.ifBlank { "Uncategorized" } }
            .map { (category, jobs) ->
                CategoryStat(
                    category = category,
                    completedCount = jobs.size,
                    totalMinutes = jobs.sumOf { it.estimatedMinutes }
                )
            }
            .sortedByDescending { it.totalMinutes }

        StatsUiState(
            activeCount = active.size,
            completedCount = completed.size,
            totalMinutesCompleted = completed.sumOf { it.estimatedMinutes },
            categoryStats = byCategory
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    class Factory(private val repository: JobRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StatsViewModel(repository) as T
        }
    }
}
