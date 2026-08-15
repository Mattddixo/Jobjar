package com.mattdixon.jobjar.ui.jobdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.remainingMinutes
import com.mattdixon.jobjar.ui.components.CategoryBadge
import com.mattdixon.jobjar.ui.components.SubtasksSection
import com.mattdixon.jobjar.ui.components.TimeBucketBadge
import com.mattdixon.jobjar.util.formatMinutes
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Job details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
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
                    .padding(24.dp)
            ) {
                Text("Job not found.")
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
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(currentJob.title, style = MaterialTheme.typography.headlineMedium)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeBucketBadge(minutes = displayMinutes)
                    if (currentJob.category.isNotBlank()) CategoryBadge(category = currentJob.category)
                }

                if (subtasks.isNotEmpty()) {
                    Text(
                        "${formatMinutes(displayMinutes)} left of ${formatMinutes(currentJob.estimatedMinutes)} total",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                parent?.let { parentJob ->
                    TextButton(onClick = { onOpenJob(parentJob.id) }) {
                        Text("Part of: ${parentJob.title}")
                    }
                }

                Text("Priority: ${currentJob.priority.displayName}", style = MaterialTheme.typography.bodyMedium)

                if (currentJob.notes.isNotBlank()) {
                    Text(currentJob.notes, style = MaterialTheme.typography.bodyLarge)
                }

                if (currentJob.timesDrawn > 0) {
                    Text(
                        "Drawn from the jar ${currentJob.timesDrawn} time(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = {
                        if (!currentJob.isDone && incompleteSubtaskCount > 0) {
                            showForceCompleteDialog = true
                        } else {
                            scope.launch { repository.toggleDone(currentJob) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (currentJob.isDone) "Mark as not done" else "Mark as done")
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
                    title = { Text("Delete job?") },
                    text = {
                        Text(
                            if (subtasks.isNotEmpty()) {
                                "\"${currentJob.title}\" and its ${subtasks.size} subtask(s) will be removed permanently."
                            } else {
                                "\"${currentJob.title}\" will be removed permanently."
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
                        }) { Text("Delete") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
                    }
                )
            }

            if (showForceCompleteDialog) {
                AlertDialog(
                    onDismissRequest = { showForceCompleteDialog = false },
                    title = { Text("Mark as done?") },
                    text = {
                        Text("$incompleteSubtaskCount subtask(s) are still open. They'll stay open, but this job will be marked done.")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch { repository.toggleDone(currentJob) }
                            showForceCompleteDialog = false
                        }) { Text("Mark done") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showForceCompleteDialog = false }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}
