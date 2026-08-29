package com.mattdixon.jobjar.ui.jobpicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One pickable row - [parentTitle] is non-null only for a subtask, captioned so picking one
 * among many similarly-named subtasks is unambiguous. */
data class JobPickerItem(val job: Job, val parentTitle: String?)

/**
 * Backs the "Link to existing Job Tracker job" flow, reached via a `jobjar://pickjob` deep link.
 * [returnJobId] is the *Tracker* job that wants a Job Jar counterpart - picking a job here sets
 * that job's own [Job.linkedTrackerJobId] to [returnJobId], establishing the link from this
 * side; the screen itself fires the `hometracker://linked` callback so Tracker learns which Job
 * Jar job it's now linked to.
 */
class JobPickerViewModel(
    private val repository: JobRepository,
    val returnJobId: Long
) : ViewModel() {

    /**
     * Every job (parents and subtasks), not filtered down to only-unlinked. The two sides of a
     * link can end up desynced - one side's own copy cleared by a bug, or a job on either side
     * deleted and its id later reused - with no way for either app to detect that on its own, so
     * the only reliable recovery is letting the user re-pick and re-establish the correct pairing
     * directly rather than hard-blocking a job because ITS OWN possibly-stale record claims it's
     * "already" linked. Picking any job here always overwrites its previous linkedTrackerJobId,
     * whatever that was. Deliberately includes subtasks (each captioned with its parent's title),
     * since a subtask may have its own separate cost worth tracking independently of its parent's
     * link.
     */
    val pickableJobs: StateFlow<List<JobPickerItem>> = repository.allJobsFlat
        .map { jobs ->
            val byId = jobs.associateBy { it.id }
            jobs.sortedBy { it.title.lowercase() }
                .map { job -> JobPickerItem(job, parentTitle = job.parentId?.let { byId[it]?.title }) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun linkTo(job: Job, onLinked: (Job) -> Unit) {
        viewModelScope.launch {
            repository.setLinkedTrackerJobId(job.id, returnJobId)
            onLinked(job)
        }
    }

    class Factory(
        private val repository: JobRepository,
        private val returnJobId: Long
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return JobPickerViewModel(repository, returnJobId) as T
        }
    }
}
