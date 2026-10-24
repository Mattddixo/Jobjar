package com.mattdixon.jobjar.ui.joblist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.annotation.StringRes
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.UndoSnapshot
import com.mattdixon.jobjar.data.isBooked
import com.mattdixon.jobjar.data.isPending
import com.mattdixon.jobjar.data.isUnblocked
import com.mattdixon.jobjar.data.remainingMinutes
import com.mattdixon.jobjar.data.totalCostCents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SortOrder(@StringRes val labelRes: Int) {
    TIME_ASC(R.string.sort_shortest_first),
    TIME_DESC(R.string.sort_longest_first),
    PRIORITY(R.string.sort_priority),
    NEWEST(R.string.sort_newest),
    CATEGORY(R.string.sort_category)
}

/** Tabs on the Jobs screen. Scheduled jobs get their own tab since they're neither to-do nor done. */
enum class JobsView { ACTIVE, COMPLETED, SCHEDULED }

/** One row: a job or a subtask (subtasks are listed too so they can be found and filtered). */
data class JobListItem(
    val job: Job,
    val displayMinutes: Int,
    val parentTitle: String? = null,
    val subtaskDone: Int = 0,
    val subtaskTotal: Int = 0,
    val waitingOnTitle: String? = null,
    val scheduledDate: Long? = null,
    /** A parent's includes its subtasks. Null = no cost anywhere. */
    val costCents: Long? = null
)

data class JobListUiState(
    val items: List<JobListItem> = emptyList(),
    val categories: List<String> = emptyList(),
    /** Jobs per category, for the Edit categories dialog. */
    val categoryCounts: Map<String, Int> = emptyMap(),
    val view: JobsView = JobsView.ACTIVE,
    /** Empty = all. */
    val selectedCategories: Set<String> = emptySet(),
    val showRepeatingOnly: Boolean = false,
    val showInProgressOnly: Boolean = false,
    val sortOrder: SortOrder = SortOrder.NEWEST,
    val searchQuery: String = "",
    /** Parents whose subtasks are showing. */
    val expandedParentIds: Set<Long> = emptySet(),
    /** False until the first load. */
    val isLoaded: Boolean = false
) {
    val hasActiveFilters: Boolean get() = selectedCategories.isNotEmpty() || showRepeatingOnly || showInProgressOnly
}

private data class ToggleFilters(
    val view: JobsView,
    val categories: Set<String>,
    val repeatingOnly: Boolean,
    val inProgressOnly: Boolean
)

private data class ListFilters(
    val view: JobsView,
    val categories: Set<String>,
    val repeatingOnly: Boolean,
    val inProgressOnly: Boolean,
    val sort: SortOrder,
    val searchQuery: String
)

class JobListViewModel(private val repository: JobRepository) : ViewModel() {
    private val view = MutableStateFlow(JobsView.ACTIVE)
    private val selectedCategories = MutableStateFlow<Set<String>>(emptySet())
    private val showRepeatingOnly = MutableStateFlow(false)
    private val showInProgressOnly = MutableStateFlow(false)
    private val sortOrder = MutableStateFlow(SortOrder.NEWEST)
    private val searchQuery = MutableStateFlow("")
    private val expandedParentIds = MutableStateFlow<Set<Long>>(emptySet())

    // combine() only has typed overloads up to 5 flows, so the filters are combined in two steps.
    private val toggleFilters = combine(
        view,
        selectedCategories,
        showRepeatingOnly,
        showInProgressOnly
    ) { currentView, categories, repeatingOnly, inProgressOnly ->
        ToggleFilters(currentView, categories, repeatingOnly, inProgressOnly)
    }

    private val filters = combine(toggleFilters, sortOrder, searchQuery) { toggles, sort, query ->
        ListFilters(
            view = toggles.view,
            categories = toggles.categories,
            repeatingOnly = toggles.repeatingOnly,
            inProgressOnly = toggles.inProgressOnly,
            sort = sort,
            searchQuery = query
        )
    }

    val uiState: StateFlow<JobListUiState> = combine(
        filters,
        repository.allJobsFlat,
        repository.categories,
        expandedParentIds
    ) { currentFilters, allFlat, categories, expanded ->
        val allById = allFlat.associateBy { it.id }
        val subtasksByParent = allFlat.filter { it.parentId != null }.groupBy { it.parentId }

        // Subtasks get their own rows too, same as the Jar and Stats count them.
        val items = allFlat.map { job ->
            if (job.parentId == null) {
                val subtasks = subtasksByParent[job.id].orEmpty()
                JobListItem(
                    job = job,
                    displayMinutes = if (subtasks.isEmpty()) job.estimatedMinutes else job.remainingMinutes(subtasks),
                    subtaskDone = subtasks.count { it.isDone },
                    subtaskTotal = subtasks.size,
                    scheduledDate = job.scheduledDate,
                    costCents = totalCostCents(job, subtasks)
                )
            } else {
                val siblingsById = subtasksByParent[job.parentId].orEmpty().associateBy { it.id }
                JobListItem(
                    job = job,
                    displayMinutes = job.estimatedMinutes,
                    parentTitle = allById[job.parentId]?.title,
                    waitingOnTitle = job.dependsOnSubtaskId
                        ?.takeUnless { job.isUnblocked(siblingsById) }
                        ?.let { siblingsById[it]?.title },
                    scheduledDate = job.scheduledDate,
                    costCents = job.costCents
                )
            }
        }

        val query = currentFilters.searchQuery.trim()

        // Repeating jobs never stay done, so isPending() decides To do vs Done. The Repeating filter
        // ignores that split and shows them all. Scheduled is jobs booked for a slot that hasn't
        // finished; a missed booking is back in To do.
        val filtered = items
            .filter { currentFilters.categories.isEmpty() || it.job.category in currentFilters.categories }
            .filter { !currentFilters.repeatingOnly || it.job.recurrenceDays != null }
            .filter { !currentFilters.inProgressOnly || it.job.isInProgress }
            .filter {
                when (currentFilters.view) {
                    JobsView.SCHEDULED -> it.job.isBooked()
                    JobsView.ACTIVE -> currentFilters.repeatingOnly || it.job.isPending()
                    JobsView.COMPLETED -> currentFilters.repeatingOnly || !it.job.isPending()
                }
            }
            .filter {
                query.isBlank() ||
                    it.job.title.contains(query, ignoreCase = true) ||
                    it.job.notes.contains(query, ignoreCase = true)
            }

        fun sortTopLevel(entries: List<JobListItem>): List<JobListItem> = when (currentFilters.sort) {
            SortOrder.TIME_ASC -> entries.sortedBy { it.displayMinutes }
            SortOrder.TIME_DESC -> entries.sortedByDescending { it.displayMinutes }
            SortOrder.PRIORITY -> entries.sortedByDescending { it.job.priority.ordinal }
            SortOrder.NEWEST -> entries.sortedByDescending { it.job.createdAt }
            SortOrder.CATEGORY -> entries.sortedBy { it.job.category }
        }

        // Subtasks sit under their parent. If the parent is filtered out, the subtask sorts like a
        // normal row.
        val visibleParentIds = filtered.filter { it.job.parentId == null }.mapTo(mutableSetOf()) { it.job.id }
        val subtasksByVisibleParent = filtered
            .filter { it.job.parentId != null && it.job.parentId in visibleParentIds }
            .groupBy { it.job.parentId }
            .mapValues { (_, subs) -> subs.sortedBy { it.job.createdAt } }
        val topLevelAndStandalone = filtered.filter {
            it.job.parentId == null || it.job.parentId !in visibleParentIds
        }

        // Groups start collapsed, but searching or filtering opens them so matches aren't hidden.
        val hasActiveNarrowing = currentFilters.categories.isNotEmpty() ||
            currentFilters.repeatingOnly ||
            currentFilters.inProgressOnly ||
            query.isNotBlank()

        val sorted = sortTopLevel(topLevelAndStandalone).flatMap { entry ->
            val subs = subtasksByVisibleParent[entry.job.id].orEmpty()
            if (subs.isEmpty() || hasActiveNarrowing || entry.job.id in expanded) {
                listOf(entry) + subs
            } else {
                listOf(entry)
            }
        }

        JobListUiState(
            items = sorted,
            categories = categories,
            categoryCounts = allFlat.filter { it.category.isNotBlank() }.groupingBy { it.category }.eachCount(),
            view = currentFilters.view,
            selectedCategories = currentFilters.categories,
            showRepeatingOnly = currentFilters.repeatingOnly,
            showInProgressOnly = currentFilters.inProgressOnly,
            sortOrder = currentFilters.sort,
            searchQuery = currentFilters.searchQuery,
            expandedParentIds = expanded,
            isLoaded = true
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JobListUiState())

    fun setView(value: JobsView) { view.value = value }

    fun toggleCategory(category: String) {
        selectedCategories.value = selectedCategories.value.let {
            if (category in it) it - category else it + category
        }
    }

    fun setShowRepeatingOnly(value: Boolean) { showRepeatingOnly.value = value }
    fun setShowInProgressOnly(value: Boolean) { showInProgressOnly.value = value }
    fun setSortOrder(value: SortOrder) { sortOrder.value = value }
    fun setSearchQuery(value: String) { searchQuery.value = value }

    fun toggleExpanded(parentId: Long) {
        expandedParentIds.value = expandedParentIds.value.let {
            if (parentId in it) it - parentId else it + parentId
        }
    }

    /** [onCompleted] only fires when this completed the job, not when it reopened it. */
    fun toggleDone(job: Job, onCompleted: (UndoSnapshot) -> Unit) {
        val completing = job.isPending()
        viewModelScope.launch {
            val snapshot = repository.toggleDone(job.id)
            if (snapshot != null && completing) onCompleted(snapshot)
        }
    }

    /** Start / Put back in jar. */
    fun toggleInProgress(job: Job) {
        viewModelScope.launch { repository.toggleInProgress(job.id) }
    }

    fun deleteJob(job: Job, onDeleted: (UndoSnapshot) -> Unit) {
        viewModelScope.launch { repository.deleteJob(job.id)?.let(onDeleted) }
    }

    fun scheduleJob(job: Job, dateTimeMillis: Long, onResult: (wroteEvent: Boolean) -> Unit) {
        viewModelScope.launch { onResult(repository.scheduleJob(job.id, dateTimeMillis)) }
    }

    fun unscheduleJob(job: Job) {
        viewModelScope.launch { repository.unscheduleJob(job.id) }
    }

    /** Also unticks it in the filter, since it won't be in the dropdown any more. */
    fun removeCategory(category: String) {
        viewModelScope.launch { repository.removeCategory(category) }
        selectedCategories.value = selectedCategories.value - category
    }

    class Factory(private val repository: JobRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return JobListViewModel(repository) as T
        }
    }
}
