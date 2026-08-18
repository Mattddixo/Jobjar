package com.mattdixon.jobjar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.LONG_JOB_MINUTES
import com.mattdixon.jobjar.data.isUnblocked
import com.mattdixon.jobjar.data.remainingMinutesOf
import com.mattdixon.jobjar.data.unallocatedMinutesOf
import com.mattdixon.jobjar.ui.theme.Spacing
import com.mattdixon.jobjar.util.formatMinutes
import kotlinx.coroutines.launch

/**
 * The "Subtasks" list, progress summary, and add-subtask button for a top-level job. Shared by
 * the job detail screen and the job creation/edit screen so subtask management looks and works
 * identically everywhere it appears, instead of each screen reimplementing it.
 *
 * [parentId] must refer to a job that is itself NOT a subtask (subtasks are one level deep) -
 * callers are responsible for that check, since this composable has no way to verify it.
 */
@Composable
fun SubtasksSection(
    repository: JobRepository,
    parentId: Long,
    parentEstimatedMinutes: Int,
    onOpenSubtask: (Long) -> Unit,
    onAddSubtask: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The "remaining of total" line (unfinished work left) is only meaningful once a job's
     * actually being worked - a freshly created subtask is never done, so it'd just sit at the
     * full total the whole time a list is being built. The add/edit screen leaves this false and
     * shows its own live [AllocationSummary] next to the duration picker instead (for both the
     * job being edited and, while adding a subtask, the parent it belongs to) - not here, so the
     * two don't compete for attention on the same screen. The detail screen leaves this true.
     */
    showRemainingSummary: Boolean = true
) {
    val subtasksFlow = remember(repository, parentId) { repository.subtasksOf(parentId) }
    val subtasks by subtasksFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.subtasks_title), style = MaterialTheme.typography.titleMedium)
            if (subtasks.isNotEmpty()) {
                Text(
                    stringResource(R.string.subtasks_done_count, subtasks.count { it.isDone }, subtasks.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (subtasks.isEmpty()) {
            Text(
                stringResource(R.string.subtasks_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            if (showRemainingSummary) {
                val remaining = remainingMinutesOf(parentEstimatedMinutes, subtasks)
                Text(
                    stringResource(R.string.label_remaining_of_total, formatMinutes(remaining), formatMinutes(parentEstimatedMinutes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val siblingsById = subtasks.associateBy { it.id }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                subtasks.forEach { subtask ->
                    SubtaskRow(
                        subtask = subtask,
                        waitingOnTitle = subtask.dependsOnSubtaskId
                            ?.takeUnless { subtask.isUnblocked(siblingsById) }
                            ?.let { siblingsById[it]?.title },
                        onClick = { onOpenSubtask(subtask.id) },
                        onToggleDone = { scope.launch { repository.toggleDone(subtask) } }
                    )
                }
            }
        }

        OutlinedButton(onClick = onAddSubtask, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.cd_add_subtask))
        }
    }
}

/**
 * How much of the typed total [subtasksTotalMinutes] worth of subtasks add up to so far. A job
 * at or above [LONG_JOB_MINUTES] never had a firm target in the first place (see the "4+ hrs"
 * duration option) - "left to allocate" or "over by" would imply a ceiling that doesn't really
 * exist, so this just reports a running sum instead, no comparison to any total. Not private:
 * also used directly by the add/edit screen next to the duration picker itself, where
 * [subtasksTotalMinutes] is a live projection (every sibling subtask plus whatever's currently
 * typed for the one being edited) rather than a straight sum of already-saved subtasks.
 */
@Composable
fun AllocationSummary(estimatedMinutes: Int, subtasksTotalMinutes: Int) {
    val text = if (estimatedMinutes >= LONG_JOB_MINUTES) {
        stringResource(R.string.subtasks_allocation_running_total, formatMinutes(subtasksTotalMinutes))
    } else {
        when (val unallocated = unallocatedMinutesOf(estimatedMinutes, subtasksTotalMinutes)) {
            0 -> stringResource(R.string.subtasks_allocation_exact, formatMinutes(estimatedMinutes))
            else -> if (unallocated > 0) {
                stringResource(R.string.subtasks_allocation_under, formatMinutes(unallocated), formatMinutes(estimatedMinutes))
            } else {
                stringResource(R.string.subtasks_allocation_over, formatMinutes(-unallocated), formatMinutes(estimatedMinutes))
            }
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * [waitingOnTitle], when non-null, means this subtask is soft-blocked on an unfinished sibling:
 * it's greyed out with a lock indicator and excluded from the jar's draw pool (see
 * [com.mattdixon.jobjar.data.JobRepository.drawJob]), but the checkbox stays fully live - it can
 * still be checked off by hand at any time.
 */
@Composable
private fun SubtaskRow(
    subtask: Job,
    waitingOnTitle: String?,
    onClick: () -> Unit,
    onToggleDone: () -> Unit
) {
    val blocked = waitingOnTitle != null
    val contentColor = if (blocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            IconButton(onClick = onToggleDone) {
                Icon(
                    imageVector = if (subtask.isDone) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = stringResource(if (subtask.isDone) R.string.cd_mark_not_done else R.string.cd_mark_done),
                    tint = contentColor
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(subtask.title, style = MaterialTheme.typography.bodyLarge, color = contentColor)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    TimeBucketBadge(minutes = subtask.estimatedMinutes)
                    if (waitingOnTitle != null) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.padding(start = Spacing.xxs),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.label_waiting_on, waitingOnTitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = contentColor)
        }
    }
}
