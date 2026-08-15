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
import androidx.compose.ui.unit.dp
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.remainingMinutesOf
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
    modifier: Modifier = Modifier
) {
    val subtasksFlow = remember(repository, parentId) { repository.subtasksOf(parentId) }
    val subtasks by subtasksFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Subtasks", style = MaterialTheme.typography.titleMedium)
            if (subtasks.isNotEmpty()) {
                Text(
                    "${subtasks.count { it.isDone }}/${subtasks.size} done",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (subtasks.isEmpty()) {
            Text(
                "Break this job into smaller pieces you can draw on their own.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val remaining = remainingMinutesOf(parentEstimatedMinutes, subtasks)
            Text(
                "${formatMinutes(remaining)} left of ${formatMinutes(parentEstimatedMinutes)} total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                subtasks.forEach { subtask ->
                    SubtaskRow(
                        subtask = subtask,
                        onClick = { onOpenSubtask(subtask.id) },
                        onToggleDone = { scope.launch { repository.toggleDone(subtask) } }
                    )
                }
            }
        }

        OutlinedButton(onClick = onAddSubtask, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Add subtask")
        }
    }
}

@Composable
private fun SubtaskRow(
    subtask: Job,
    onClick: () -> Unit,
    onToggleDone: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onToggleDone) {
                Icon(
                    imageVector = if (subtask.isDone) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (subtask.isDone) "Mark not done" else "Mark done"
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(subtask.title, style = MaterialTheme.typography.bodyLarge)
                TimeBucketBadge(minutes = subtask.estimatedMinutes)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}
