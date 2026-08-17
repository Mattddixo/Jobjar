package com.mattdixon.jobjar.ui.addedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.LONG_JOB_MINUTES
import com.mattdixon.jobjar.data.Priority
import com.mattdixon.jobjar.data.subtasksAvailableAsDependency
import com.mattdixon.jobjar.ui.components.SubtasksSection
import com.mattdixon.jobjar.util.formatMinutes
import com.mattdixon.jobjar.util.formatRecurrenceInterval

private val QUICK_DURATIONS = listOf(5, 15, 30, 45, 60, 90, 120, 180)
private val RECURRENCE_PRESETS = listOf(1, 7, 14, 30)

/** Generous enough for a real task phrase, short enough to always stay tidy in a list row, a
 * badge-heavy card, or a detail-screen header - the places a title actually has to fit. */
private const val MAX_TITLE_LENGTH = 80

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditJobScreen(
    repository: JobRepository,
    jobId: Long?,
    onDone: () -> Unit,
    parentId: Long? = null,
    onOpenSubtask: (Long) -> Unit = {},
    onAddSubtask: (Long) -> Unit = {}
) {
    val viewModel: AddEditJobViewModel = viewModel(
        factory = AddEditJobViewModel.Factory(repository, jobId, parentId)
    )
    val state by viewModel.formState.collectAsState()
    val categories by repository.categories.collectAsState(initial = emptyList())

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) onDone()
    }

    val title = when {
        jobId != null && state.parentId != null -> "Edit subtask"
        jobId != null -> "Edit job"
        parentId != null -> "New subtask"
        else -> "New job"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.save() }, enabled = state.isValid) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = { if (it.length <= MAX_TITLE_LENGTH) viewModel.setTitle(it) },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                supportingText = {
                    Text(
                        "${state.title.length}/$MAX_TITLE_LENGTH",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

            OutlinedTextField(
                value = state.notes,
                onValueChange = viewModel::setNotes,
                label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How long will it take?", style = MaterialTheme.typography.labelLarge)
                Text(formatMinutes(state.estimatedMinutes), style = MaterialTheme.typography.headlineSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(QUICK_DURATIONS) { minutes ->
                        FilterChip(
                            selected = state.estimatedMinutes == minutes,
                            onClick = { viewModel.setEstimatedMinutes(minutes) },
                            label = { Text(formatMinutes(minutes)) }
                        )
                    }
                    item {
                        // Long jobs don't have one "right" length, so this is a range, not an
                        // exact value: selected whenever the estimate is 4hr or more, and tapping
                        // it seeds 4hr as a starting point you can then fine-tune below.
                        FilterChip(
                            selected = state.estimatedMinutes >= LONG_JOB_MINUTES,
                            onClick = { viewModel.setEstimatedMinutes(LONG_JOB_MINUTES) },
                            label = { Text("4+ hrs") }
                        )
                    }
                }
                OutlinedTextField(
                    value = if (state.estimatedMinutes == 0) "" else state.estimatedMinutes.toString(),
                    onValueChange = { text ->
                        val value = text.filter { it.isDigit() }.toIntOrNull() ?: 0
                        viewModel.setEstimatedMinutes(value)
                    },
                    label = { Text("Custom minutes") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Category", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = state.category,
                    onValueChange = viewModel::setCategory,
                    label = { Text("e.g. Chores, Work, Errands") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )
                if (categories.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(categories) { category ->
                            FilterChip(
                                selected = state.category == category,
                                onClick = { viewModel.setCategory(category) },
                                label = { Text(category) }
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Priority", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow {
                    Priority.entries.forEachIndexed { index, priority ->
                        SegmentedButton(
                            selected = state.priority == priority,
                            onClick = { viewModel.setPriority(priority) },
                            shape = SegmentedButtonDefaults.itemShape(index, Priority.entries.size)
                        ) { Text(priority.displayName) }
                    }
                }
            }

            // The opposite gate from below: only a subtask can depend on another subtask, and
            // only among its own siblings (same parent).
            val subtaskParentId = state.parentId
            if (state.isLoaded && subtaskParentId != null) {
                HorizontalDivider()

                val siblingsFlow = remember(repository, subtaskParentId) { repository.subtasksOf(subtaskParentId) }
                val siblings by siblingsFlow.collectAsState(initial = emptyList())
                val candidates = subtasksAvailableAsDependency(siblings, excludingSelfId = state.id)

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Depends on", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Optional: this subtask stays out of the jar's random draw until the one it depends on is done. You can still check it off by hand any time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = state.dependsOnSubtaskId == null,
                                onClick = { viewModel.setDependsOn(null) },
                                label = { Text("None") }
                            )
                        }
                        items(candidates) { candidate ->
                            FilterChip(
                                selected = state.dependsOnSubtaskId == candidate.id,
                                onClick = { viewModel.setDependsOn(candidate.id) },
                                label = { Text(candidate.title) }
                            )
                        }
                    }
                }
            }

            // Only a job that isn't itself a subtask can repeat or have subtasks (one level
            // deep). Gated on isLoaded so an existing subtask being edited never flashes either
            // section before we know its real parentId.
            if (state.isLoaded && state.parentId == null) {
                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Repeat", style = MaterialTheme.typography.labelLarge)
                        Switch(
                            checked = state.recurrenceDays != null,
                            onCheckedChange = { viewModel.setRecurring(it) }
                        )
                    }
                    val recurrenceDays = state.recurrenceDays
                    if (recurrenceDays != null) {
                        Text(formatRecurrenceInterval(recurrenceDays), style = MaterialTheme.typography.headlineSmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(RECURRENCE_PRESETS) { days ->
                                FilterChip(
                                    selected = recurrenceDays == days,
                                    onClick = { viewModel.setRecurrenceDays(days) },
                                    label = { Text(formatRecurrenceInterval(days)) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = if (recurrenceDays == 0) "" else recurrenceDays.toString(),
                            onValueChange = { text ->
                                val value = text.filter { it.isDigit() }.toIntOrNull() ?: 0
                                viewModel.setRecurrenceDays(value)
                            },
                            label = { Text("Custom: every N days") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    } else {
                        Text(
                            "Completing a repeating job reopens it automatically after the interval, instead of leaving it done for good.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider()

                val savedId = state.id
                if (savedId != null) {
                    SubtasksSection(
                        repository = repository,
                        parentId = savedId,
                        parentEstimatedMinutes = state.estimatedMinutes,
                        onOpenSubtask = onOpenSubtask,
                        onAddSubtask = { onAddSubtask(savedId) }
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Subtasks", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Adding a subtask saves this job first, using what you've filled in so far.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = { viewModel.ensurePersisted { newId -> onAddSubtask(newId) } },
                            enabled = state.isValid,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text("Add subtask")
                        }
                    }
                }
            }
        }
    }
}
