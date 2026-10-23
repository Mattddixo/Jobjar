package com.mattdixon.jobjar.ui.addedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.LONG_JOB_MINUTES
import com.mattdixon.jobjar.data.Priority
import com.mattdixon.jobjar.data.subtasksAvailableAsDependency
import com.mattdixon.jobjar.data.weekdayBit
import com.mattdixon.jobjar.ui.components.AllocationSummary
import com.mattdixon.jobjar.ui.components.SubtasksSection
import com.mattdixon.jobjar.ui.components.recurrenceLabel
import com.mattdixon.jobjar.ui.theme.Spacing
import com.mattdixon.jobjar.ui.theme.appTopBarColors
import com.mattdixon.jobjar.util.formatMinutes
import kotlinx.coroutines.flow.flowOf
import java.time.DayOfWeek
import java.time.format.TextStyle

private val QUICK_DURATIONS = listOf(5, 15, 30, 45, 60, 90, 120, 180)
private val RECURRENCE_PRESETS = listOf(1, 7, 14, 30)

/** Long enough for a real task, short enough to fit on a card. */
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

    // Subtasks only: the parent and siblings, for the time summary and the "Do this after" picker.
    val subtaskParentId = state.parentId
    val parentJobFlow = remember(repository, subtaskParentId) {
        subtaskParentId?.let { repository.jobById(it) } ?: flowOf(null)
    }
    val parentJob by parentJobFlow.collectAsState(initial = null)
    val siblingsFlow = remember(repository, subtaskParentId) {
        subtaskParentId?.let { repository.subtasksOf(it) } ?: flowOf(emptyList())
    }
    val siblings by siblingsFlow.collectAsState(initial = emptyList())

    // Parents only: this job's own subtasks, for the time summary next to the duration.
    val savedId = state.id
    val ownSubtasksFlow = remember(repository, savedId) {
        savedId?.let { repository.subtasksOf(it) } ?: flowOf(emptyList())
    }
    val ownSubtasks by ownSubtasksFlow.collectAsState(initial = emptyList())

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) onDone()
    }

    val titleRes = when {
        jobId != null && state.parentId != null -> R.string.addedit_title_edit_subtask
        jobId != null -> R.string.addedit_title_edit_job
        parentId != null -> R.string.addedit_title_new_subtask
        else -> R.string.addedit_title_new_job
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = appTopBarColors(),
                title = { Text(stringResource(titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.action_cancel))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.save() }, enabled = state.isValid) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.cd_save))
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
                .padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxl)
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = { if (it.length <= MAX_TITLE_LENGTH) viewModel.setTitle(it) },
                label = { Text(stringResource(R.string.field_title_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                supportingText = {
                    Text(
                        stringResource(R.string.title_char_count, state.title.length, MAX_TITLE_LENGTH),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

            OutlinedTextField(
                value = state.notes,
                onValueChange = viewModel::setNotes,
                label = { Text(stringResource(R.string.field_notes_label)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
            )

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(stringResource(R.string.duration_question), style = MaterialTheme.typography.labelLarge)
                Text(formatMinutes(state.estimatedMinutes), style = MaterialTheme.typography.headlineSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    items(QUICK_DURATIONS) { minutes ->
                        FilterChip(
                            selected = state.estimatedMinutes == minutes,
                            onClick = { viewModel.setEstimatedMinutes(minutes) },
                            label = { Text(formatMinutes(minutes)) }
                        )
                    }
                    item {
                        // Selected for anything 4hr+. Tapping it sets 4hr, which you can then adjust below.
                        FilterChip(
                            selected = state.estimatedMinutes >= LONG_JOB_MINUTES,
                            onClick = { viewModel.setEstimatedMinutes(LONG_JOB_MINUTES) },
                            label = { Text(stringResource(R.string.long_job_chip_label)) }
                        )
                    }
                }
                OutlinedTextField(
                    value = if (state.estimatedMinutes == 0) "" else state.estimatedMinutes.toString(),
                    onValueChange = { text ->
                        val value = text.filter { it.isDigit() }.toIntOrNull() ?: 0
                        viewModel.setEstimatedMinutes(value)
                    },
                    label = { Text(stringResource(R.string.field_custom_minutes)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Uses what's typed right now for this subtask, not its saved estimate.
                val currentParent = parentJob
                if (state.isLoaded && subtaskParentId != null && currentParent != null) {
                    val otherSiblingsTotal = siblings.filter { it.id != state.id }.sumOf { it.estimatedMinutes }
                    AllocationSummary(
                        estimatedMinutes = currentParent.estimatedMinutes,
                        subtasksTotalMinutes = otherSiblingsTotal + state.estimatedMinutes
                    )
                }

                if (subtaskParentId == null && savedId != null && ownSubtasks.isNotEmpty()) {
                    AllocationSummary(
                        estimatedMinutes = state.estimatedMinutes,
                        subtasksTotalMinutes = ownSubtasks.sumOf { it.estimatedMinutes }
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(stringResource(R.string.category_label), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = state.category,
                    onValueChange = viewModel::setCategory,
                    label = { Text(stringResource(R.string.category_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )
                if (categories.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
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

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(stringResource(R.string.priority_label), style = MaterialTheme.typography.labelLarge)
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

            // Only subtasks can depend on something, and only on a sibling.
            if (state.isLoaded && subtaskParentId != null) {
                HorizontalDivider()

                val candidates = subtasksAvailableAsDependency(siblings, excludingSelfId = state.id)

                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(stringResource(R.string.depends_on_label), style = MaterialTheme.typography.labelLarge)
                    Text(
                        stringResource(R.string.depends_on_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        item {
                            FilterChip(
                                selected = state.dependsOnSubtaskId == null,
                                onClick = { viewModel.setDependsOn(null) },
                                label = { Text(stringResource(R.string.depends_on_none)) }
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

            // Top-level jobs only. Repeat and subtasks are either/or (see JobRepository.addJob).
            // Waits for isLoaded so a subtask doesn't flash these sections while it loads.
            if (state.isLoaded && state.parentId == null) {
                val hasSubtasks = ownSubtasks.isNotEmpty()
                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.repeat_label), style = MaterialTheme.typography.labelLarge)
                        Switch(
                            checked = state.recurrenceDays != null,
                            onCheckedChange = { viewModel.setRecurring(it) },
                            enabled = state.recurrenceDays != null || !hasSubtasks
                        )
                    }
                    val recurrenceDays = state.recurrenceDays
                    if (recurrenceDays != null) {
                        Text(
                            recurrenceLabel(recurrenceDays, state.repeatWeekdays.takeIf { state.repeatsOnWeekdays }),
                            style = MaterialTheme.typography.headlineSmall
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = !state.repeatsOnWeekdays,
                                onClick = { viewModel.setRepeatOnWeekdays(false) },
                                shape = SegmentedButtonDefaults.itemShape(0, 2)
                            ) { Text(stringResource(R.string.repeat_mode_interval)) }
                            SegmentedButton(
                                selected = state.repeatsOnWeekdays,
                                onClick = { viewModel.setRepeatOnWeekdays(true) },
                                shape = SegmentedButtonDefaults.itemShape(1, 2)
                            ) { Text(stringResource(R.string.repeat_mode_weekdays)) }
                        }
                        if (state.repeatsOnWeekdays) {
                            WeekdayPicker(
                                mask = state.repeatWeekdays ?: 0,
                                onToggle = viewModel::toggleWeekday
                            )
                            Text(
                                stringResource(R.string.repeat_weekdays_explainer),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                items(RECURRENCE_PRESETS) { days ->
                                    FilterChip(
                                        selected = recurrenceDays == days,
                                        onClick = { viewModel.setRecurrenceDays(days) },
                                        label = { Text(recurrenceLabel(days)) }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = if (recurrenceDays == 0) "" else recurrenceDays.toString(),
                                onValueChange = { text ->
                                    val value = text.filter { it.isDigit() }.take(3).toIntOrNull() ?: 0
                                    viewModel.setRecurrenceDays(value)
                                },
                                label = { Text(stringResource(R.string.field_custom_recurrence)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Text(
                                stringResource(R.string.repeat_interval_explainer),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            stringResource(if (hasSubtasks) R.string.repeat_blocked_by_subtasks else R.string.repeat_explainer),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider()

                if (state.recurrenceDays != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Text(stringResource(R.string.subtasks_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.subtasks_blocked_by_repeat),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (savedId != null) {
                    SubtasksSection(
                        repository = repository,
                        parentId = savedId,
                        onOpenSubtask = onOpenSubtask,
                        onAddSubtask = { viewModel.ensurePersisted { id -> onAddSubtask(id) } }
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Text(stringResource(R.string.subtasks_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.subtasks_save_first_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = { viewModel.ensurePersisted { newId -> onAddSubtask(newId) } },
                            enabled = state.isValid,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text(stringResource(R.string.cd_add_subtask))
                        }
                    }
                }
            }
        }
    }
}

/** Monday first. Any combination. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayPicker(mask: Int, onToggle: (DayOfWeek) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        DayOfWeek.entries.forEach { day ->
            FilterChip(
                selected = mask and weekdayBit(day) != 0,
                onClick = { onToggle(day) },
                label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) }
            )
        }
    }
}
