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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.isPending
import com.mattdixon.jobjar.ui.components.CategoryBadge
import com.mattdixon.jobjar.ui.components.InfoBadge
import com.mattdixon.jobjar.ui.components.TimeBucketBadge

/** Consistent horizontal inset for every row on this screen. */
private val ScreenHPadding = 16.dp

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
                    title = { Text("Jobs") },
                    actions = {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search jobs")
                        }
                        Box {
                            IconButton(onClick = { sortMenuExpanded = true }) {
                                Icon(Icons.Filled.Sort, contentDescription = "Sort")
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
                            Text(if (darkTheme) "Light" else "Dark")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddJob) {
                Icon(Icons.Filled.Add, contentDescription = "Add job")
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
                    .padding(horizontal = ScreenHPadding, vertical = 12.dp)
            ) {
                SegmentedButton(
                    selected = !state.showCompleted,
                    onClick = { viewModel.setShowCompleted(false) },
                    enabled = !state.showRepeatingOnly,
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("Active") }
                SegmentedButton(
                    selected = state.showCompleted,
                    onClick = { viewModel.setShowCompleted(true) },
                    enabled = !state.showRepeatingOnly,
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("Completed") }
            }

            // Every filter - current and future - lives in this one horizontally-scrollable
            // row instead of each getting its own dedicated widget/row. Repeating is a plain
            // toggle chip; Category opens a multi-select dropdown from its chip (same pattern
            // as Sort) so the row stays a fixed size no matter how many categories exist. A new
            // filter later just means adding another chip here, not inventing a new layout.
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = ScreenHPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = state.showRepeatingOnly,
                        onClick = { viewModel.setShowRepeatingOnly(!state.showRepeatingOnly) },
                        leadingIcon = { Icon(Icons.Filled.Repeat, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        label = { Text("Repeating") }
                    )
                }
                if (state.categories.isNotEmpty()) {
                    item {
                        Box {
                            FilterChip(
                                selected = state.selectedCategories.isNotEmpty(),
                                onClick = { categoryMenuExpanded = true },
                                label = { Text(categoryChipLabel(state.selectedCategories)) },
                                trailingIcon = {
                                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            )
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
                if (state.hasActiveFilters) {
                    item {
                        AssistChip(
                            onClick = { viewModel.clearFilters() },
                            label = { Text("Clear") },
                            leadingIcon = {
                                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                labelColor = MaterialTheme.colorScheme.error,
                                leadingIconContentColor = MaterialTheme.colorScheme.error
                            )
                        )
                    }
                }
            }

            if (state.items.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = emptyStateText(state),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = ScreenHPadding, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
            title = { Text("Delete job?") },
            text = {
                Text(
                    if (item.subtaskTotal > 0) {
                        "\"${item.job.title}\" and its ${item.subtaskTotal} subtask(s) will be removed permanently."
                    } else {
                        "\"${item.job.title}\" will be removed permanently."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteJob(item.job)
                    itemPendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { itemPendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    itemPendingForceComplete?.let { item ->
        val incompleteCount = item.subtaskTotal - item.subtaskDone
        AlertDialog(
            onDismissRequest = { itemPendingForceComplete = null },
            title = { Text("Mark as done?") },
            text = {
                Text("$incompleteCount subtask(s) are still open. They'll stay open, but this job will be marked done.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.toggleDone(item.job)
                    itemPendingForceComplete = null
                }) { Text("Mark done") }
            },
            dismissButton = {
                TextButton(onClick = { itemPendingForceComplete = null }) { Text("Cancel") }
            }
        )
    }
}

private fun categoryChipLabel(selected: Set<String>): String = when (selected.size) {
    0 -> "Category"
    1 -> selected.first()
    else -> "${selected.first()} +${selected.size - 1}"
}

private fun emptyStateText(state: JobListUiState): String = when {
    state.searchQuery.isNotBlank() -> "No jobs match \"${state.searchQuery}\"."
    state.hasActiveFilters -> "No jobs match these filters."
    state.showCompleted -> "No completed jobs yet."
    else -> "No jobs yet. Tap + to add one."
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
                placeholder = { Text("Search jobs") },
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
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                }
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Close search")
            }
        }
    )
}

@Composable
private fun JobRow(
    item: JobListItem,
    onClick: () -> Unit,
    onToggleDone: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val job = item.job

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconButton(onClick = onToggleDone) {
                Icon(
                    imageVector = if (!job.isPending()) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (!job.isPending()) "Mark not done" else "Mark done"
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = job.title,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (!job.isPending()) TextDecoration.LineThrough else null
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TimeBucketBadge(minutes = item.displayMinutes)
                    if (job.category.isNotBlank()) CategoryBadge(category = job.category)
                    if (item.subtaskTotal > 0) InfoBadge(text = "${item.subtaskDone}/${item.subtaskTotal} done")
                    if (item.recurrenceLabel != null) InfoBadge(text = item.recurrenceLabel)
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
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Open") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menuExpanded = false; onClick() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menuExpanded = false; onDeleteRequest() }
                    )
                }
            }
        }
    }
}
