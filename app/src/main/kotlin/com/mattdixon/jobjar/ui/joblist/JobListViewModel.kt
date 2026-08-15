package com.mattdixon.jobjar.ui.joblist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.isPending
import com.mattdixon.jobjar.data.remainingMinutes
import com.mattdixon.jobjar.util.formatDueStatus
import com.mattdixon.jobjar.util.formatRecurrenceInterval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SortOrder(val label: String) {
    TIME_ASC("Shortest first"),
    TIME_DESC("Longest first"),
    PRIORITY("Priority"),
    NEWEST("Newest"),
    CATEGORY("Category")
}

/** A top-level job plus how many of its subtasks (if any) are done, for the list row's badge. */
data class JobListItem(
    val job: Job,
    val displayMinutes: Int,
    val subtaskDone: Int,
    val subtaskTotal: Int,
    /** "Weekly" etc, or null if this job doesn't repeat. */
    val recurrenceLabel: String?,
    /** "Due now" / "Next: in 3 days", or null if this job doesn't repeat. */
    val dueStatus: String?
)

data class JobListUiState(
    val items: List<JobListItem> = emptyList(),
    val categories: List<String> = emptyList(),
    val showCompleted: Boolean = false,
    val selectedCategory: String? = null,
    val showRepeatingOnly: Boolean = false,
    val sortOrder: SortOrder = SortOrder.NEWEST
)

private data class ListFilters(
    val showCompleted: Boolean,
    val category: String?,
    val repeatingOnly: Boolean,
    val sort: SortOrder
)

class JobListViewModel(private val repository: JobRepository) : ViewModel() {

    private val showCompleted = MutableStateFlow(false)
    private val selectedCategory = MutableStateFlow<String?>(null)
    private val showRepeatingOnly = MutableStateFlow(false)
    private val sortOrder = MutableStateFlow(SortOrder.NEWEST)

    private val filters = combine(
        showCompleted,
        selectedCategory,
        showRepeatingOnly,
        sortOrder
    ) { showDone, category, repeatingOnly, sort ->
        ListFilters(showDone, category, repeatingOnly, sort)
    }

    val uiState: StateFlow<JobListUiState> = combine(
        filters,
        repository.topLevelJobs,
        repository.allJobsFlat,
        repository.categories
    ) { currentFilters, topLevel, allFlat, categories ->
        val subtasksByParent = allFlat.filter { it.parentId != null }.groupBy { it.parentId }

        val items = topLevel.map { job ->
            val subtasks = subtasksByParent[job.id].orEmpty()
            JobListItem(
                job = job,
                displayMinutes = if (subtasks.isEmpty()) job.estimatedMinutes else job.remainingMinutes(subtasks),
                subtaskDone = subtasks.count { it.isDone },
                subtaskTotal = subtasks.size,
                recurrenceLabel = job.recurrenceDays?.let { formatRecurrenceInterval(it) },
                dueStatus = job.recurrenceDays?.let { formatDueStatus(job.nextDueAt) }
            )
        }

        // A repeating job never persists isDone, so "completed" for it means "resting until its
        // next cycle" - isPending() (not isDone) is what decides Active vs Completed for it, and
        // for everything else it's equivalent to !isDone. When filtering to repeating jobs only,
        // the Active/Completed split is bypassed entirely and both due and resting ones show
        // together, so this is the one place you can see the full set regardless of state.
        val filtered = items
            .filter { currentFilters.category == null || it.job.category == currentFilters.category }
            .filter { !currentFilters.repeatingOnly || it.job.recurrenceDays != null }
            .filter { currentFilters.repeatingOnly || it.job.isPending() != currentFilters.showCompleted }

        val sorted = when (currentFilters.sort) {
            SortOrder.TIME_ASC -> filtered.sortedBy { it.displayMinutes }
            SortOrder.TIME_DESC -> filtered.sortedByDescending { it.displayMinutes }
            SortOrder.PRIORITY -> filtered.sortedByDescending { it.job.priority.ordinal }
            SortOrder.NEWEST -> filtered.sortedByDescending { it.job.createdAt }
            SortOrder.CATEGORY -> filtered.sortedBy { it.job.category }
        }

        JobListUiState(
            items = sorted,
            categories = categories,
            showCompleted = currentFilters.showCompleted,
            selectedCategory = currentFilters.category,
            showRepeatingOnly = currentFilters.repeatingOnly,
            sortOrder = currentFilters.sort
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JobListUiState())

    fun setShowCompleted(value: Boolean) { showCompleted.value = value }
    fun setCategory(value: String?) { selectedCategory.value = value }
    fun setShowRepeatingOnly(value: Boolean) { showRepeatingOnly.value = value }
    fun setSortOrder(value: SortOrder) { sortOrder.value = value }

    fun toggleDone(job: Job) {
        viewModelScope.launch { repository.toggleDone(job) }
    }

    fun deleteJob(job: Job) {
        viewModelScope.launch { repository.deleteJob(job) }
    }

    class Factory(private val repository: JobRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return JobListViewModel(repository) as T
        }
    }
}
