package com.mattdixon.jobjar.ui.jobdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.isPending
import com.mattdixon.jobjar.data.remainingMinutes
import com.mattdixon.jobjar.ui.components.CategoryBadge
import com.mattdixon.jobjar.ui.components.InfoBadge
import com.mattdixon.jobjar.ui.components.SubtasksSection
import com.mattdixon.jobjar.ui.components.TimeBucketBadge
import com.mattdixon.jobjar.ui.theme.AppShapes
import com.mattdixon.jobjar.ui.theme.Spacing
import com.mattdixon.jobjar.util.formatDueStatus
import com.mattdixon.jobjar.util.formatMinutes
import com.mattdixon.jobjar.util.formatRecurrenceInterval
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobDetailScreen(
    repository: JobRepository,
    jobId: Long,
    onEdit: () -> Unit,
    onAddSubtask: () -> Unit,
    onOpenJob: (Long) -> Unit,
    onBack: () -> Unit
) {
    // remember()'d so recompositions (e.g. toggling a dialog) reuse the same Flow instance
    // instead of Room starting a brand-new query/subscription on every recomposition.
    val jobFlow = remember(repository, jobId) { repository.jobById(jobId) }
    val job by jobFlow.collectAsState(initial = null)

    val subtasksFlow = remember(repository, jobId) { repository.subtasksOf(jobId) }
    val subtasks by subtasksFlow.collectAsState(initial = emptyList())

    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showForceCompleteDialog by remember { mutableStateOf(false) }

    val currentJob = job
    val parentFlow = remember(currentJob?.parentId) {
        currentJob?.parentId?.let { repository.jobById(it) } ?: flowOf(null)
    }
    val parent by parentFlow.collectAsState(initial = null)

    // Siblings under the same parent, only needed to resolve this job's own dependsOnSubtaskId
    // (if it's a subtask) into a title for the "Waiting on" indicator below.
    val siblingsFlow = remember(currentJob?.parentId) {
        currentJob?.parentId?.let { repository.subtasksOf(it) } ?: flowOf(emptyList())
    }
    val siblings by siblingsFlow.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.jobdetail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.cd_edit))
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                }
            )
        }
    ) { padding ->
        if (currentJob == null) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(Spacing.xxxl)
            ) {
                Text(stringResource(R.string.job_not_found))
            }
        } else {
            val incompleteSubtaskCount = subtasks.count { !it.isDone }
            val displayMinutes = if (subtasks.isNotEmpty()) {
                currentJob.remainingMinutes(subtasks)
            } else {
                currentJob.estimatedMinutes
            }

            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.xxxl),
                verticalArrangement = Arrangement.spacedBy(Spacing.xl)
            ) {
                Text(currentJob.title, style = MaterialTheme.typography.headlineMedium)

                // Placed right under the title (rather than lower, among plain info text) and
                // with a trailing chevron - the same "this leads somewhere" cue
                // SubtasksSection's own rows already use - so it reads unmistakably as
                // navigation to the parent, not just another label.
                parent?.let { parentJob ->
                    TextButton(
                        onClick = { onOpenJob(parentJob.id) },
                        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.xs)
                    ) {
                        Text(stringResource(R.string.label_part_of, parentJob.title))
                        Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    if (currentJob.isInProgress) InfoBadge(text = stringResource(R.string.badge_in_progress))
                    TimeBucketBadge(minutes = displayMinutes)
                    if (currentJob.category.isNotBlank()) CategoryBadge(category = currentJob.category)
                    currentJob.recurrenceDays?.let { InfoBadge(text = formatRecurrenceInterval(it)) }
                }

                if (subtasks.isNotEmpty()) {
                    Text(
                        stringResource(R.string.label_remaining_of_total, formatMinutes(displayMinutes), formatMinutes(currentJob.estimatedMinutes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (currentJob.recurrenceDays != null) {
                    Text(
                        formatDueStatus(currentJob.nextDueAt) +
                            if (currentJob.completionCount > 0) {
                                stringResource(R.string.completed_n_times, currentJob.completionCount)
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val prerequisite = currentJob.dependsOnSubtaskId?.let { depId -> siblings.find { it.id == depId } }
                if (prerequisite != null && !prerequisite.isDone) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.label_waiting_on, prerequisite.title),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    stringResource(R.string.priority_display, currentJob.priority.displayName),
                    style = MaterialTheme.typography.bodyMedium
                )

                if (currentJob.notes.isNotBlank()) {
                    Text(currentJob.notes, style = MaterialTheme.typography.bodyLarge)
                }

                if (currentJob.timesDrawn > 0) {
                    Text(
                        stringResource(R.string.drawn_n_times, currentJob.timesDrawn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Only offered while the job is actually pending - a resting repeating job (not
                // due yet) or an already-done job has nothing to "start." Starting itself needs
                // no guard dialog the way completing does: nothing gets silently left behind by
                // picking a job up, so it's a plain toggle either way.
                if (currentJob.isPending()) {
                    OutlinedButton(
                        onClick = { scope.launch { repository.toggleInProgress(currentJob) } },
                        shape = AppShapes.control,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            if (currentJob.isInProgress) Icons.Filled.Undo else Icons.Filled.PlayArrow,
                            contentDescription = null
                        )
                        Text(stringResource(if (currentJob.isInProgress) R.string.action_move_to_jar else R.string.action_start))
                    }
                }

                Button(
                    onClick = {
                        if (currentJob.isPending() && incompleteSubtaskCount > 0) {
                            showForceCompleteDialog = true
                        } else {
                            scope.launch { repository.toggleDone(currentJob) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            when {
                                currentJob.isPending() -> R.string.action_mark_as_done
                                currentJob.recurrenceDays != null -> R.string.action_make_available_now
                                else -> R.string.action_mark_as_not_done
                            }
                        )
                    )
                }

                if (currentJob.parentId == null) {
                    HorizontalDivider()
                    SubtasksSection(
                        repository = repository,
                        parentId = currentJob.id,
                        parentEstimatedMinutes = currentJob.estimatedMinutes,
                        onOpenSubtask = onOpenJob,
                        onAddSubtask = onAddSubtask
                    )
                }
            }

            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = { Text(stringResource(R.string.dialog_delete_job_title)) },
                    text = {
                        Text(
                            if (subtasks.isNotEmpty()) {
                                stringResource(R.string.dialog_delete_job_with_subtasks, currentJob.title, subtasks.size)
                            } else {
                                stringResource(R.string.dialog_delete_job_plain, currentJob.title)
                            }
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch {
                                repository.deleteJob(currentJob)
                                showDeleteDialog = false
                                onBack()
                            }
                        }) { Text(stringResource(R.string.action_delete)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                    }
                )
            }

            if (showForceCompleteDialog) {
                AlertDialog(
                    onDismissRequest = { showForceCompleteDialog = false },
                    title = { Text(stringResource(R.string.dialog_mark_as_done_title)) },
                    text = { Text(stringResource(R.string.dialog_force_complete_body, incompleteSubtaskCount)) },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch { repository.toggleDone(currentJob) }
                            showForceCompleteDialog = false
                        }) { Text(stringResource(R.string.action_mark_done)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showForceCompleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                    }
                )
            }
        }
    }
}
