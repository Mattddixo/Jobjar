package com.mattdixon.jobjar.ui.joblist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.isPending
import com.mattdixon.jobjar.ui.components.CategoryBadge
import com.mattdixon.jobjar.ui.components.InfoBadge
import com.mattdixon.jobjar.ui.components.TimeBucketBadge
import com.mattdixon.jobjar.ui.theme.Spacing

/** Consistent horizontal inset for every row on this screen. */
private val ScreenHPadding = Spacing.xl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobListScreen(
    repository: JobRepository,
    onAddJob: () -> Unit,
    onOpenJob: (Long) -> Unit,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val viewModel: JobListViewModel = viewModel(factory = JobListViewModel.Factory(repository))
    val state by viewModel.uiState.collectAsState()
    var itemPendingDelete by remember { mutableStateOf<JobListItem?>(null) }
    var itemPendingForceComplete by remember { mutableStateOf<JobListItem?>(null) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (searchActive) {
                SearchTopBar(
                    query = state.searchQuery,
                    onQueryChange = viewModel::setSearchQuery,
                    onClose = {
                        searchActive = false
                        viewModel.setSearchQuery("")
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.jobs_screen_title)) },
                    actions = {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.cd_search_jobs))
                        }
                        Box {
                            IconButton(onClick = { sortMenuExpanded = true }) {
                                Icon(Icons.Filled.Sort, contentDescription = stringResource(R.string.cd_sort))
                            }
                            DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                                SortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(order.label) },
                                        onClick = {
                                            viewModel.setSortOrder(order)
                                            sortMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        TextButton(onClick = onToggleTheme) {
                            Text(stringResource(if (darkTheme) R.string.theme_toggle_light else R.string.theme_toggle_dark))
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddJob) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_job))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenHPadding, vertical = Spacing.lg)
            ) {
                SegmentedButton(
                    selected = !state.showCompleted,
                    onClick = { viewModel.setShowCompleted(false) },
                    enabled = !state.showRepeatingOnly,
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text(stringResource(R.string.filter_active)) }
                SegmentedButton(
                    selected = state.showCompleted,
                    onClick = { viewModel.setShowCompleted(true) },
                    enabled = !state.showRepeatingOnly,
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text(stringResource(R.string.filter_completed)) }
            }

            // Every filter - current and future - lives in this one horizontally-scrollable
            // row instead of each getting its own dedicated widget/row. Repeating and In
            // Progress are plain toggle chips, cleared the same way they're set - tap again.
            // Category opens a multi-select dropdown from its chip and always shows a fixed
            // "Category · N" label rather than the actual selected names, so the chip's own
            // width can never grow with the selection; each category clears the same way it's
            // set too - re-tick its checkbox in the dropdown. There's deliberately no separate
            // "clear all" control living in this row: that was a second element competing for
            // the same scrollable space, which is exactly what caused it to visually collide
            // with the Category chip.
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = ScreenHPadding),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                item {
                    FilterChip(
                        selected = state.showRepeatingOnly,
                        onClick = { viewModel.setShowRepeatingOnly(!state.showRepeatingOnly) },
                        leadingIcon = { Icon(Icons.Filled.Repeat, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        label = { Text(stringResource(R.string.filter_repeating)) }
                    )
                }
                item {
                    FilterChip(
                        selected = state.showInProgressOnly,
                        onClick = { viewModel.setShowInProgressOnly(!state.showInProgressOnly) },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        label = { Text(stringResource(R.string.filter_in_progress)) }
                    )
                }
                if (state.categories.isNotEmpty()) {
                    item {
                        Box {
                            // The chip's own label never changes with the selection - only the
                            // badge does - so the chip itself never resizes and can't throw off
                            // the row's rhythm the way a growing "Category · N" label used to.
                            BadgedBox(
                                badge = {
                                    if (state.selectedCategories.isNotEmpty()) {
                                        Badge { Text(state.selectedCategories.size.toString()) }
                                    }
                                }
                            ) {
                                FilterChip(
                                    selected = state.selectedCategories.isNotEmpty(),
                                    onClick = { categoryMenuExpanded = true },
                                    label = { Text(stringResource(R.string.filter_category_default)) },
                                    trailingIcon = {
                                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                )
                            }
                            DropdownMenu(expanded = categoryMenuExpanded, onDismissRequest = { categoryMenuExpanded = false }) {
                                state.categories.forEach { category ->
                                    DropdownMenuItem(
                                        text = { Text(category) },
                                        leadingIcon = {
                                            Checkbox(
                                                checked = category in state.selectedCategories,
                                                onCheckedChange = null
                                            )
                                        },
                                        onClick = { viewModel.toggleCategory(category) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (state.items.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Spacing.xxxl + Spacing.xs, vertical = Spacing.xl)
                ) {
                    Text(
                        text = emptyStateText(state),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = ScreenHPadding, vertical = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    items(state.items, key = { it.job.id }) { item ->
                        JobRow(
                            item = item,
                            onClick = { onOpenJob(item.job.id) },
                            onToggleDone = {
                                val hasOpenSubtasks = item.subtaskTotal > 0 && item.subtaskDone < item.subtaskTotal
                                if (item.job.isPending() && hasOpenSubtasks) {
                                    itemPendingForceComplete = item
                                } else {
                                    viewModel.toggleDone(item.job)
                                }
                            },
                            onToggleInProgress = { viewModel.toggleInProgress(item.job) },
                            onDeleteRequest = { itemPendingDelete = item }
                        )
                    }
                }
            }
        }
    }

    itemPendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemPendingDelete = null },
            title = { Text(stringResource(R.string.dialog_delete_job_title)) },
            text = {
                Text(
                    if (item.subtaskTotal > 0) {
                        stringResource(R.string.dialog_delete_job_with_subtasks, item.job.title, item.subtaskTotal)
                    } else {
                        stringResource(R.string.dialog_delete_job_plain, item.job.title)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteJob(item.job)
                    itemPendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { itemPendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    itemPendingForceComplete?.let { item ->
        val incompleteCount = item.subtaskTotal - item.subtaskDone
        AlertDialog(
            onDismissRequest = { itemPendingForceComplete = null },
            title = { Text(stringResource(R.string.dialog_mark_as_done_title)) },
            text = { Text(stringResource(R.string.dialog_force_complete_body, incompleteCount)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.toggleDone(item.job)
                    itemPendingForceComplete = null
                }) { Text(stringResource(R.string.action_mark_done)) }
            },
            dismissButton = {
                TextButton(onClick = { itemPendingForceComplete = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun emptyStateText(state: JobListUiState): String = when {
    state.searchQuery.isNotBlank() -> stringResource(R.string.empty_no_search_match, state.searchQuery)
    state.hasActiveFilters -> stringResource(R.string.empty_no_filter_match)
    state.showCompleted -> stringResource(R.string.empty_no_completed)
    else -> stringResource(R.string.empty_no_jobs)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.cd_search_jobs)) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = MaterialTheme.colorScheme.surface,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surface
                ),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_clear_search))
                        }
                    }
                }
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_close_search))
            }
        }
    )
}

@Composable
private fun JobRow(
    item: JobListItem,
    onClick: () -> Unit,
    onToggleDone: () -> Unit,
    onToggleInProgress: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val job = item.job

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = job.title,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (!job.isPending()) TextDecoration.LineThrough else null
                )
                if (item.parentTitle != null) {
                    Text(
                        stringResource(R.string.label_part_of, item.parentTitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (job.isInProgress) InfoBadge(text = stringResource(R.string.badge_in_progress))
                    TimeBucketBadge(minutes = item.displayMinutes)
                    if (job.category.isNotBlank()) CategoryBadge(category = job.category)
                    if (item.subtaskTotal > 0) InfoBadge(text = stringResource(R.string.subtasks_done_count, item.subtaskDone, item.subtaskTotal))
                    if (item.recurrenceLabel != null) InfoBadge(text = item.recurrenceLabel)
                }
                if (item.waitingOnTitle != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.label_waiting_on, item.waitingOnTitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (item.dueStatus != null) {
                    Text(
                        item.dueStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_open)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menuExpanded = false; onClick() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (!job.isPending()) R.string.cd_mark_not_done else R.string.cd_mark_done)) },
                        leadingIcon = {
                            Icon(
                                if (!job.isPending()) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = null
                            )
                        },
                        onClick = { menuExpanded = false; onToggleDone() }
                    )
                    // Starting doesn't need the subtask guard completing does - nothing gets
                    // silently closed by picking a job up, so it's a plain toggle either way.
                    if (job.isInProgress) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_move_to_jar)) },
                            leadingIcon = { Icon(Icons.Filled.Undo, contentDescription = null) },
                            onClick = { menuExpanded = false; onToggleInProgress() }
                        )
                    } else if (job.isPending()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_start)) },
                            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                            onClick = { menuExpanded = false; onToggleInProgress() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menuExpanded = false; onDeleteRequest() }
                    )
                }
            }
        }
    }
}
