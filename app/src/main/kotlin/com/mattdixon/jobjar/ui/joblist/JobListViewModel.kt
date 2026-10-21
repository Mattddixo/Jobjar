package com.mattdixon.jobjar.ui.joblist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.isPending
import com.mattdixon.jobjar.data.isUnblocked
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

/** Which of the three top-level views the Jobs list is showing - replaces a plain
 * Active/Completed boolean now that a scheduled job needs a home of its own: it's neither
 * "active" in the everyday sense (it's deliberately excluded from the random draw, same
 * treatment as an in-progress job) nor "completed," so folding it into either would misplace it
 * rather than actually show it. */
enum class JobsView { ACTIVE, COMPLETED, SCHEDULED }

/**
 * One row on the Jobs list - either a top-level job or a subtask, since both are independently
 * actionable (drawable, startable, completable) and both need to actually be findable here, not
 * just visible by drilling into a parent's detail page. [parentTitle] is non-null only for a
 * subtask row; [subtaskDone]/[subtaskTotal] are only meaningful for a top-level job that has
 * subtasks of its own (a subtask can't have subtasks - one level deep); [waitingOnTitle] is
 * non-null only for a subtask row that's blocked on an unfinished sibling; [scheduledDate] just
 * mirrors [Job.scheduledDate] - carried here too so the row doesn't need to reach back into the
 * job for something this list already displays and filters on.
 */
data class JobListItem(
    val job: Job,
    val displayMinutes: Int,
    val parentTitle: String? = null,
    val subtaskDone: Int = 0,
    val subtaskTotal: Int = 0,
    val waitingOnTitle: String? = null,
    /** "Weekly" etc, or null if this job doesn't repeat. */
    val recurrenceLabel: String? = null,
    /** "Due now" / "Next: in 3 days", or null if this job doesn't repeat. */
    val dueStatus: String? = null,
    val scheduledDate: Long? = null
)

data class JobListUiState(
    val items: List<JobListItem> = emptyList(),
    val categories: List<String> = emptyList(),
    /** How many jobs (parents and subtasks alike) currently carry each category - shown in the "Manage categories" dialog so removing one can say how many jobs it'll clear. */
    val categoryCounts: Map<String, Int> = emptyMap(),
    val view: JobsView = JobsView.ACTIVE,
    /** Empty = no category narrowing (all categories included). */
    val selectedCategories: Set<String> = emptySet(),
    val showRepeatingOnly: Boolean = false,
    val showInProgressOnly: Boolean = false,
    val sortOrder: SortOrder = SortOrder.NEWEST,
    val searchQuery: String = "",
    /** Parent job IDs whose subtask group is currently expanded. Collapsed (not present) by default. */
    val expandedParentIds: Set<Long> = emptySet()
) {
    /** Whether any *narrowing* filter (as opposed to the Active/Completed/Scheduled view or sort) is on - drives the "Clear" chip. */
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

    // kotlinx.coroutines' typed combine() overloads only go up to 5 flows, and a vararg
    // combine() would require every flow to share one type - not possible here with a mix of
    // JobsView/Set<String>/SortOrder/String. Combining the four toggle-style filters first, then
    // combining that with sort and search, keeps everything typed without that limitation.
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

        // Every row - parents and subtasks alike - is its own list item. A subtask is just as
        // independently actionable (drawable, startable, completable) as a top-level job, so
        // scoping this list to top-level jobs only would hide half the picture: you could start
        // or complete a subtask from the Draw screen or its parent's detail page, but never see
        // or filter for it here. This matches the population the Jar meter and Stats already use
        // (repository.allJobsFlat) rather than disagreeing with them about what "all jobs" means.
        val items = allFlat.map { job ->
            if (job.parentId == null) {
                val subtasks = subtasksByParent[job.id].orEmpty()
                JobListItem(
                    job = job,
                    displayMinutes = if (subtasks.isEmpty()) job.estimatedMinutes else job.remainingMinutes(subtasks),
                    subtaskDone = subtasks.count { it.isDone },
                    subtaskTotal = subtasks.size,
                    recurrenceLabel = job.recurrenceDays?.let { formatRecurrenceInterval(it) },
                    dueStatus = job.recurrenceDays?.let { formatDueStatus(job.nextDueAt) },
                    scheduledDate = job.scheduledDate
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
                    scheduledDate = job.scheduledDate
                )
            }
        }

        val query = currentFilters.searchQuery.trim()

        // A repeating job never persists isDone, so "completed" for it means "resting until its
        // next cycle" - isPending() (not isDone) is what decides Active vs Completed for it, and
        // for everything else it's equivalent to !isDone. When filtering to repeating jobs only,
        // the Active/Completed split is bypassed entirely and both due and resting ones show
        // together, so this is the one place you can see the full set regardless of state. The
        // Scheduled view is its own thing entirely - scheduledDate != null - rather than a
        // pending/done split, and a repeating job can never match it (scheduling isn't offered
        // for one; its own nextDueAt cycling already covers "when").
        val filtered = items
            .filter { currentFilters.categories.isEmpty() || it.job.category in currentFilters.categories }
            .filter { !currentFilters.repeatingOnly || it.job.recurrenceDays != null }
            .filter { !currentFilters.inProgressOnly || it.job.isInProgress }
            .filter {
                when (currentFilters.view) {
                    JobsView.SCHEDULED -> it.job.scheduledDate != null
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

        // Subtasks sort immediately under their own parent instead of scattering wherever the
        // chosen sort would otherwise place them - that's the actual "messy" part of a flat list,
        // not just the missing indent. A subtask whose own parent isn't in this filtered view
        // (e.g. it matches In Progress or a search term but the parent doesn't) has no group to
        // join, so it's treated like any other top-level entry for sorting purposes instead of
        // being hidden or force-attached to an unrelated/invisible parent.
        val visibleParentIds = filtered.filter { it.job.parentId == null }.mapTo(mutableSetOf()) { it.job.id }
        val subtasksByVisibleParent = filtered
            .filter { it.job.parentId != null && it.job.parentId in visibleParentIds }
            .groupBy { it.job.parentId }
            .mapValues { (_, subs) -> subs.sortedBy { it.job.createdAt } }
        val topLevelAndStandalone = filtered.filter {
            it.job.parentId == null || it.job.parentId !in visibleParentIds
        }

        // Subtask groups default to collapsed, but a search/filter that's actively narrowing the
        // list should still surface a matching subtask even if its parent's group hasn't been
        // manually expanded - otherwise a search hit could be hidden with no visible way to reach
        // it. Plain narrowing by Active/Completed doesn't count, since every list view has that.
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
            expandedParentIds = expanded
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

    fun toggleDone(job: Job) {
        viewModelScope.launch { repository.toggleDone(job) }
    }

    /** Starts or reverts a job's in-progress flag - the same toggle backs both the "Start" and "Move back to jar" row menu items, since only one is ever shown for a given job at a time. */
    fun toggleInProgress(job: Job) {
        viewModelScope.launch { repository.toggleInProgress(job) }
    }

    fun deleteJob(job: Job) {
        viewModelScope.launch { repository.deleteJob(job) }
    }

    fun scheduleJob(job: Job, dateTimeMillis: Long) {
        viewModelScope.launch { repository.scheduleJob(job, dateTimeMillis) }
    }

    fun unscheduleJob(job: Job) {
        viewModelScope.launch { repository.unscheduleJob(job) }
    }

    /** Drops [category] from the active filter selection too, if it was one of the ones
     * narrowing the list - otherwise a just-removed category could stay checked "invisibly"
     * since it can no longer appear in the dropdown to be unchecked by hand. */
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
