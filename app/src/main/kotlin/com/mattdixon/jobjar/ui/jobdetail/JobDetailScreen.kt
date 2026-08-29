package com.mattdixon.jobjar.ui.jobdetail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.EventBusy
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.isPending
import com.mattdixon.jobjar.data.remainingMinutes
import com.mattdixon.jobjar.ui.components.CategoryBadge
import com.mattdixon.jobjar.ui.components.InfoBadge
import com.mattdixon.jobjar.ui.components.SchedulePickerDialog
import com.mattdixon.jobjar.ui.components.SubtasksSection
import com.mattdixon.jobjar.ui.components.TimeBucketBadge
import com.mattdixon.jobjar.ui.theme.AppShapes
import com.mattdixon.jobjar.ui.theme.Spacing
import com.mattdixon.jobjar.util.formatDueStatus
import com.mattdixon.jobjar.util.formatMinutes
import com.mattdixon.jobjar.util.formatRecurrenceInterval
import com.mattdixon.jobjar.util.formatScheduledDateTime
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

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
    var showSchedulePicker by remember { mutableStateOf(false) }

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

                if (currentJob.scheduledDate != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Icon(
                            Icons.Filled.Event,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.badge_scheduled, formatScheduledDateTime(currentJob.scheduledDate)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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

                // Only offered while the job is actually pending - a resting repeating job (not
                // due yet) or an already-done job has nothing to "start." Starting itself needs
                // no guard dialog the way completing does: nothing gets silently left behind by
                // picking a job up, so it's a plain toggle either way.
                if (currentJob.isPending()) {
                    // Scheduling is out of scope for repeating jobs (see SchedulePickerDialog's
                    // doc comment) - those keep the original full-width Start/Move-to-jar button.
                    if (currentJob.recurrenceDays == null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                        ) {
                            OutlinedButton(
                                onClick = { scope.launch { repository.toggleInProgress(currentJob) } },
                                shape = AppShapes.control,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    if (currentJob.isInProgress) Icons.Filled.Undo else Icons.Filled.PlayArrow,
                                    contentDescription = null
                                )
                                Text(stringResource(if (currentJob.isInProgress) R.string.action_move_to_jar else R.string.action_start))
                            }
                            OutlinedButton(
                                onClick = {
                                    if (currentJob.scheduledDate != null) {
                                        scope.launch { repository.unscheduleJob(currentJob) }
                                    } else {
                                        showSchedulePicker = true
                                    }
                                },
                                shape = AppShapes.control,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    if (currentJob.scheduledDate != null) Icons.Filled.EventBusy else Icons.Filled.Event,
                                    contentDescription = null
                                )
                                Text(stringResource(if (currentJob.scheduledDate != null) R.string.action_unschedule else R.string.action_schedule))
                            }
                        }
                    } else {
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

                HorizontalDivider()
                TrackerLinkActions(currentJob, onUnlink = { scope.launch { repository.setLinkedTrackerJobId(currentJob.id, null) } })

                if (currentJob.parentId == null) {
                    HorizontalDivider()
                    SubtasksSection(
                        repository = repository,
                        parentId = currentJob.id,
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

            if (showSchedulePicker) {
                SchedulePickerDialog(
                    onDismiss = { showSchedulePicker = false },
                    onConfirm = { dateTimeMillis ->
                        scope.launch { repository.scheduleJob(currentJob, dateTimeMillis) }
                        showSchedulePicker = false
                    }
                )
            }
        }
    }
}

/**
 * Hands this job off to Home Jobs Tracker (a separate, unrelated app for tracking a job's
 * vendor/cost/payment details) via an implicit `ACTION_VIEW` intent against its own custom URI
 * scheme - the standard way for two local-only Android apps on the same device to talk to each
 * other without a shared backend. Once [Job.linkedTrackerJobId] is set - whether by sending a fresh
 * copy over or by linking to an existing Tracker job via the picker - the Send/Link actions are
 * replaced by "Open in Job Tracker" plus a smaller "Unlink", so a job can never end up linked
 * twice, but a mistaken or outdated link can always be broken and redone. Deliberately not gated
 * on this job being a top-level one: a subtask gets these same actions on its own detail screen,
 * since it may carry its own separate Tracker job with its own cost, independent of whatever its
 * parent is linked to.
 */
@Composable
private fun TrackerLinkActions(job: Job, onUnlink: () -> Unit) {
    val context = LocalContext.current
    var showUnlinkConfirm by remember { mutableStateOf(false) }
    val linkedTrackerJobId = job.linkedTrackerJobId

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (linkedTrackerJobId == null) {
            OutlinedButton(
                onClick = { openInJobTracker(context, sendToTrackerUri(job)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.action_send_to_tracker))
            }
            OutlinedButton(
                onClick = { openInJobTracker(context, Uri.parse("hometracker://pickjob?returnJobId=${job.id}")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.action_link_to_tracker))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                OutlinedButton(
                    onClick = { openInJobTracker(context, Uri.parse("hometracker://job/$linkedTrackerJobId")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_open_in_tracker))
                }
                OutlinedButton(
                    onClick = { showUnlinkConfirm = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_unlink_tracker))
                }
            }
        }
    }

    if (showUnlinkConfirm && linkedTrackerJobId != null) {
        AlertDialog(
            onDismissRequest = { showUnlinkConfirm = false },
            title = { Text(stringResource(R.string.dialog_unlink_tracker_title)) },
            text = { Text(stringResource(R.string.dialog_unlink_tracker_body)) },
            confirmButton = {
                TextButton(onClick = {
                    fireUnlinkedToTracker(context, trackerJobId = linkedTrackerJobId)
                    onUnlink()
                    showUnlinkConfirm = false
                }) { Text(stringResource(R.string.action_unlink_tracker)) }
            },
            dismissButton = {
                TextButton(onClick = { showUnlinkConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

private fun fireUnlinkedToTracker(context: Context, trackerJobId: Long) {
    val uri = Uri.parse("hometracker://unlinked").buildUpon()
        .appendQueryParameter("jobId", trackerJobId.toString())
        .build()
    openInJobTracker(context, uri)
}

private fun sendToTrackerUri(job: Job): Uri {
    val builder = Uri.parse("hometracker://newjob").buildUpon()
        .appendQueryParameter("title", job.title)
        .appendQueryParameter("sourceId", job.id.toString())
        .appendQueryParameter("estimatedMinutes", job.estimatedMinutes.toString())
    if (job.category.isNotBlank()) builder.appendQueryParameter("category", job.category)
    // Tracker's scheduledDate is a plain ISO date with no time component, so the time-of-day this
    // job may be scheduled for is dropped here - only the date survives the trip.
    job.scheduledDate?.let { millis ->
        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        builder.appendQueryParameter("scheduledDate", date.toString())
    }
    return builder.build()
}

private fun openInJobTracker(context: Context, uri: Uri) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.toast_tracker_not_installed), Toast.LENGTH_SHORT).show()
    }
}
