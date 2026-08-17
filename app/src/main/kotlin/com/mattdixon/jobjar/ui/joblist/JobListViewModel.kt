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
    /** Empty = no category narrowing (all categories included). */
    val selectedCategories: Set<String> = emptySet(),
    val showRepeatingOnly: Boolean = false,
    val sortOrder: SortOrder = SortOrder.NEWEST,
    val searchQuery: String = ""
) {
    /** Whether any *narrowing* filter (as opposed to the Active/Completed view or sort) is on - drives the "Clear" chip. */
    val hasActiveFilters: Boolean get() = selectedCategories.isNotEmpty() || showRepeatingOnly
}

private data class ListFilters(
    val showCompleted: Boolean,
    val categories: Set<String>,
    val repeatingOnly: Boolean,
    val sort: SortOrder,
    val searchQuery: String
)

class JobListViewModel(private val repository: JobRepository) : ViewModel() {

    private val showCompleted = MutableStateFlow(false)
    private val selectedCategories = MutableStateFlow<Set<String>>(emptySet())
    private val showRepeatingOnly = MutableStateFlow(false)
    private val sortOrder = MutableStateFlow(SortOrder.NEWEST)
    private val searchQuery = MutableStateFlow("")

    private val filters = combine(
        showCompleted,
        selectedCategories,
        showRepeatingOnly,
        sortOrder,
        searchQuery
    ) { showDone, categories, repeatingOnly, sort, query ->
        ListFilters(showDone, categories, repeatingOnly, sort, query)
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

        val query = currentFilters.searchQuery.trim()

        // A repeating job never persists isDone, so "completed" for it means "resting until its
        // next cycle" - isPending() (not isDone) is what decides Active vs Completed for it, and
        // for everything else it's equivalent to !isDone. When filtering to repeating jobs only,
        // the Active/Completed split is bypassed entirely and both due and resting ones show
        // together, so this is the one place you can see the full set regardless of state.
        val filtered = items
            .filter { currentFilters.categories.isEmpty() || it.job.category in currentFilters.categories }
            .filter { !currentFilters.repeatingOnly || it.job.recurrenceDays != null }
            .filter { currentFilters.repeatingOnly || it.job.isPending() != currentFilters.showCompleted }
            .filter {
                query.isBlank() ||
                    it.job.title.contains(query, ignoreCase = true) ||
                    it.job.notes.contains(query, ignoreCase = true)
            }

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
            selectedCategories = currentFilters.categories,
            showRepeatingOnly = currentFilters.repeatingOnly,
            sortOrder = currentFilters.sort,
            searchQuery = currentFilters.searchQuery
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JobListUiState())

    fun setShowCompleted(value: Boolean) { showCompleted.value = value }

    fun toggleCategory(category: String) {
        selectedCategories.value = selectedCategories.value.let {
            if (category in it) it - category else it + category
        }
    }

    fun setShowRepeatingOnly(value: Boolean) { showRepeatingOnly.value = value }
    fun setSortOrder(value: SortOrder) { sortOrder.value = value }
    fun setSearchQuery(value: String) { searchQuery.value = value }

    /** Resets every *narrowing* filter (category, repeating) - deliberately leaves Active/Completed and sort alone, since those aren't "filters" in the same sense. */
    fun clearFilters() {
        selectedCategories.value = emptySet()
        showRepeatingOnly.value = false
    }

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
