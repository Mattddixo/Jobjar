package com.mattdixon.jobjar.ui.addedit

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.LONG_JOB_MINUTES
import com.mattdixon.jobjar.data.Priority
import com.mattdixon.jobjar.data.subtasksAvailableAsDependency
import com.mattdixon.jobjar.ui.components.AllocationSummary
import com.mattdixon.jobjar.ui.components.SubtasksSection
import com.mattdixon.jobjar.ui.theme.Spacing
import com.mattdixon.jobjar.util.formatMinutes
import com.mattdixon.jobjar.util.formatRecurrenceInterval
import com.mattdixon.jobjar.util.formatScheduledDateTime
import kotlinx.coroutines.flow.flowOf

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
    onAddSubtask: (Long) -> Unit = {},
    prefillTitle: String? = null,
    prefillCategory: String? = null,
    sourceTrackerJobId: Long? = null,
    prefillEstimatedMinutes: Int? = null,
    prefillScheduledDate: String? = null
) {
    val viewModel: AddEditJobViewModel = viewModel(
        factory = AddEditJobViewModel.Factory(
            repository,
            jobId,
            parentId,
            prefillTitle,
            prefillCategory,
            sourceTrackerJobId,
            prefillEstimatedMinutes,
            prefillScheduledDate
        )
    )
    val state by viewModel.formState.collectAsState()
    val categories by repository.categories.collectAsState(initial = emptyList())

    // Only meaningful for a subtask (state.parentId != null): its own parent, and every sibling
    // sharing that parent. Hoisted here rather than fetched separately by each section that
    // needs it (the live allocation summary next to the duration picker below, and the
    // "Depends on" picker further down) - one subscription, not two.
    val subtaskParentId = state.parentId
    val parentJobFlow = remember(repository, subtaskParentId) {
        subtaskParentId?.let { repository.jobById(it) } ?: flowOf(null)
    }
    val parentJob by parentJobFlow.collectAsState(initial = null)
    val siblingsFlow = remember(repository, subtaskParentId) {
        subtaskParentId?.let { repository.subtasksOf(it) } ?: flowOf(emptyList())
    }
    val siblings by siblingsFlow.collectAsState(initial = emptyList())

    // The opposite direction: only meaningful for a job that ISN'T itself a subtask (it's the
    // one this screen is editing, not its parent) and already has an id to have subtasks of its
    // own - its own subtask list, purely for the live allocation summary next to its duration
    // picker. SubtasksSection further down fetches this same job's subtasks independently for
    // its own list rendering; not worth threading a pre-fetched list through it just to avoid
    // one extra cheap Room query subscription.
    val savedId = state.id
    val ownSubtasksFlow = remember(repository, savedId) {
        savedId?.let { repository.subtasksOf(it) } ?: flowOf(emptyList())
    }
    val ownSubtasks by ownSubtasksFlow.collectAsState(initial = emptyList())

    val context = LocalContext.current
    LaunchedEffect(state.isSaved) {
        if (state.isSaved) {
            val trackerJobId = state.linkedTrackerJobId
            if (state.wasJustCreated && trackerJobId != null && savedId != null) {
                fireLinkedToTracker(context, trackerJobId = trackerJobId, jobJarId = savedId)
            }
            onDone()
        }
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
                        // Long jobs don't have one "right" length, so this is a range, not an
                        // exact value: selected whenever the estimate is 4hr or more, and tapping
                        // it seeds 4hr as a starting point you can then fine-tune below.
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

                // Only a subtask has a parent to allocate against. Live projection, not a
                // straight sum of already-saved siblings: this subtask's own last-saved estimate
                // (if it has one) is swapped out for whatever's currently typed above, so the
                // summary tracks the duration picker as it's being used instead of only updating
                // after a save.
                val currentParent = parentJob
                if (state.isLoaded && subtaskParentId != null && currentParent != null) {
                    val otherSiblingsTotal = siblings.filter { it.id != state.id }.sumOf { it.estimatedMinutes }
                    AllocationSummary(
                        estimatedMinutes = currentParent.estimatedMinutes,
                        subtasksTotalMinutes = otherSiblingsTotal + state.estimatedMinutes
                    )
                }

                // The other direction: this job isn't a subtask, but already has subtasks of its
                // own - shows here, next to its own duration picker, instead of above the
                // subtask list further down (SubtasksSection's own summary line is off for this
                // screen; see showRemainingSummary = false below), so both this screen and a
                // subtask's own screen keep the same summary in the same relative spot: right
                // where its own time gets chosen.
                if (subtaskParentId == null && savedId != null && ownSubtasks.isNotEmpty()) {
                    AllocationSummary(
                        estimatedMinutes = state.estimatedMinutes,
                        subtasksTotalMinutes = ownSubtasks.sumOf { it.estimatedMinutes }
                    )
                }

                // Only present on a brand-new job opened via a jobjar://newjob deep link that
                // carried a Tracker scheduledDate - not a full scheduling picker, just a visible,
                // removable preview of what Save will apply (see
                // AddEditJobViewModel.persist/clearPrefillSchedule). Actually scheduling this job
                // (with a real calendar event) still only happens once Save is pressed.
                state.scheduledDateMillis?.let { millis ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                                stringResource(R.string.label_scheduled_from_tracker, formatScheduledDateTime(millis)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { viewModel.clearPrefillSchedule() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_clear_schedule_prefill))
                        }
                    }
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

            // The opposite gate from below: only a subtask can depend on another subtask, and
            // only among its own siblings (same parent). subtaskParentId/siblings are the
            // hoisted values from the top of this composable, shared with the allocation
            // summary next to the duration picker above.
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

            // Only a job that isn't itself a subtask can repeat or have subtasks (one level
            // deep). Gated on isLoaded so an existing subtask being edited never flashes either
            // section before we know its real parentId.
            if (state.isLoaded && state.parentId == null) {
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
                            onCheckedChange = { viewModel.setRecurring(it) }
                        )
                    }
                    val recurrenceDays = state.recurrenceDays
                    if (recurrenceDays != null) {
                        Text(formatRecurrenceInterval(recurrenceDays), style = MaterialTheme.typography.headlineSmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
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
                            label = { Text(stringResource(R.string.field_custom_recurrence)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    } else {
                        Text(
                            stringResource(R.string.repeat_explainer),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider()

                if (savedId != null) {
                    SubtasksSection(
                        repository = repository,
                        parentId = savedId,
                        onOpenSubtask = onOpenSubtask,
                        onAddSubtask = { onAddSubtask(savedId) }
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

/**
 * The return trip for a job created via a `jobjar://newjob?...&sourceId=<trackerJobId>` deep
 * link: tells Tracker which Job Jar job it should now remember as linked, so a second "Send" from
 * that same Tracker job can't create a duplicate. Only fired once, right after the create that
 * established the link (see [AddEditFormState.wasJustCreated]) - never on a later edit-save.
 */
private fun fireLinkedToTracker(context: Context, trackerJobId: Long, jobJarId: Long) {
    val uri = Uri.parse("hometracker://linked").buildUpon()
        .appendQueryParameter("jobId", trackerJobId.toString())
        .appendQueryParameter("otherId", jobJarId.toString())
        .build()
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.toast_tracker_not_installed), Toast.LENGTH_SHORT).show()
    }
}
