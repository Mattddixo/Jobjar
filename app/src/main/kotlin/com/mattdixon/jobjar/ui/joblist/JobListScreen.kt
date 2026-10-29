package com.mattdixon.jobjar.ui.joblist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.mattdixon.jobjar.data.UndoSnapshot
import com.mattdixon.jobjar.data.isBooked
import com.mattdixon.jobjar.data.isMissed
import com.mattdixon.jobjar.data.isOverdue
import com.mattdixon.jobjar.data.isPending
import com.mattdixon.jobjar.ui.components.CategoryBadge
import com.mattdixon.jobjar.ui.components.InfoBadge
import com.mattdixon.jobjar.ui.components.SchedulePickerDialog
import com.mattdixon.jobjar.ui.components.rememberCalendarResultNotice
import com.mattdixon.jobjar.ui.components.TimeBucketBadge
import com.mattdixon.jobjar.ui.components.TodayDateButton
import com.mattdixon.jobjar.ui.components.dueStatusLabel
import com.mattdixon.jobjar.ui.components.plural
import com.mattdixon.jobjar.ui.components.recurrenceLabel
import com.mattdixon.jobjar.ui.LocalUndoController
import com.mattdixon.jobjar.ui.UndoEvent
import com.mattdixon.jobjar.ui.UndoKind
import com.mattdixon.jobjar.ui.UndoSnackbarEffect
import com.mattdixon.jobjar.ui.theme.Spacing
import com.mattdixon.jobjar.ui.theme.appTopBarColors
import com.mattdixon.jobjar.ui.theme.appCardBorder
import com.mattdixon.jobjar.ui.theme.appCardColors
import com.mattdixon.jobjar.util.formatCost
import com.mattdixon.jobjar.util.formatScheduledDateTime

private val ScreenHPadding = Spacing.xl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobListScreen(
    repository: JobRepository,
    onAddJob: () -> Unit,
    onOpenJob: (Long) -> Unit,
    onOpenSettings: () -> Unit
) {
    val viewModel: JobListViewModel = viewModel(factory = JobListViewModel.Factory(repository))
    val state by viewModel.uiState.collectAsState()
    var itemPendingDelete by remember { mutableStateOf<JobListItem?>(null) }
    var itemPendingForceComplete by remember { mutableStateOf<JobListItem?>(null) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    var manageCategoriesOpen by remember { mutableStateOf(false) }
    var categoryPendingRemoval by remember { mutableStateOf<String?>(null) }
    var itemPendingSchedule by remember { mutableStateOf<JobListItem?>(null) }
    val calendarNotice = rememberCalendarResultNotice()
    val undo = LocalUndoController.current
    val snackbarHostState = remember { SnackbarHostState() }
    UndoSnackbarEffect(snackbarHostState, isJobList = true)
    val onCompleted: (JobListItem) -> (UndoSnapshot) -> Unit = { item ->
        { snapshot -> undo.offer(UndoEvent(UndoKind.COMPLETED, item.job.title, snapshot)) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    colors = appTopBarColors(),
                    title = {
                        Column {
                            TodayDateButton()
                            Text(stringResource(R.string.jobs_screen_title))
                        }
                    },
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
                                        text = { Text(stringResource(order.labelRes)) },
                                        onClick = {
                                            viewModel.setSortOrder(order)
                                            sortMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddJob,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
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
                    selected = state.view == JobsView.ACTIVE,
                    onClick = { viewModel.setView(JobsView.ACTIVE) },
                    enabled = !state.showRepeatingOnly,
                    shape = SegmentedButtonDefaults.itemShape(0, 3)
                ) { Text(stringResource(R.string.filter_active)) }
                SegmentedButton(
                    selected = state.view == JobsView.COMPLETED,
                    onClick = { viewModel.setView(JobsView.COMPLETED) },
                    enabled = !state.showRepeatingOnly,
                    shape = SegmentedButtonDefaults.itemShape(1, 3)
                ) { Text(stringResource(R.string.filter_completed)) }
                SegmentedButton(
                    selected = state.view == JobsView.SCHEDULED,
                    onClick = { viewModel.setView(JobsView.SCHEDULED) },
                    // Scheduled never overlaps with Repeating, so it stays enabled.
                    shape = SegmentedButtonDefaults.itemShape(2, 3)
                ) { Text(stringResource(R.string.filter_scheduled)) }
            }

            // All filters live in this one scrolling row. Tap a chip again to clear it.
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
                            // The label stays fixed and the badge shows the count, so the chip doesn't change size.
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
                                // Nowhere else lists every category, so editing them lives here.
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.manage_categories_title)) },
                                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                    onClick = {
                                        categoryMenuExpanded = false
                                        manageCategoriesOpen = true
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (!state.isLoaded) {
                // Loads almost instantly; better to show nothing than flash "empty".
                Spacer(Modifier.fillMaxSize())
            } else if (state.items.isEmpty()) {
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
                            isExpanded = item.job.id in state.expandedParentIds,
                            onClick = { onOpenJob(item.job.id) },
                            onToggleDone = {
                                val hasOpenSubtasks = item.subtaskTotal > 0 && item.subtaskDone < item.subtaskTotal
                                if (item.job.isPending() && hasOpenSubtasks) {
                                    itemPendingForceComplete = item
                                } else {
                                    viewModel.toggleDone(item.job, onCompleted(item))
                                }
                            },
                            onToggleInProgress = { viewModel.toggleInProgress(item.job) },
                            onDeleteRequest = { itemPendingDelete = item },
                            onToggleExpanded = { viewModel.toggleExpanded(item.job.id) },
                            onScheduleRequest = { itemPendingSchedule = item },
                            onUnschedule = { viewModel.unscheduleJob(item.job) }
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
                        plural(R.plurals.dialog_delete_job_with_subtasks, item.subtaskTotal, item.job.title, item.subtaskTotal)
                    } else {
                        stringResource(R.string.dialog_delete_job_plain, item.job.title)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteJob(item.job) { snapshot ->
                        undo.offer(UndoEvent(UndoKind.DELETED, item.job.title, snapshot))
                    }
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
            text = { Text(plural(R.plurals.dialog_force_complete_body, incompleteCount, incompleteCount)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.toggleDone(item.job, onCompleted(item))
                    itemPendingForceComplete = null
                }) { Text(stringResource(R.string.action_mark_done)) }
            },
            dismissButton = {
                TextButton(onClick = { itemPendingForceComplete = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (manageCategoriesOpen) {
        ManageCategoriesDialog(
            categories = state.categories,
            categoryCounts = state.categoryCounts,
            onRemoveRequest = { category -> categoryPendingRemoval = category },
            onDismiss = { manageCategoriesOpen = false }
        )
    }

    categoryPendingRemoval?.let { category ->
        AlertDialog(
            onDismissRequest = { categoryPendingRemoval = null },
            title = { Text(stringResource(R.string.dialog_remove_category_title)) },
            text = {
                val count = state.categoryCounts[category] ?: 0
                Text(plural(R.plurals.dialog_remove_category_body, count, category, count))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeCategory(category)
                    categoryPendingRemoval = null
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { categoryPendingRemoval = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    itemPendingSchedule?.let { item ->
        SchedulePickerDialog(
            onDismiss = { itemPendingSchedule = null },
            onConfirm = { dateTimeMillis ->
                viewModel.scheduleJob(item.job, dateTimeMillis, calendarNotice)
                itemPendingSchedule = null
            }
        )
    }
}

/** Lists categories in use so you can remove one. The caller confirms the removal. */
@Composable
private fun ManageCategoriesDialog(
    categories: List<String>,
    categoryCounts: Map<String, Int>,
    onRemoveRequest: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manage_categories_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                categories.forEach { category ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(category, style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = { onRemoveRequest(category) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_remove_category, category))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        }
    )
}

@Composable
private fun emptyStateText(state: JobListUiState): String = when {
    state.searchQuery.isNotBlank() -> stringResource(R.string.empty_no_search_match, state.searchQuery)
    state.hasActiveFilters -> stringResource(R.string.empty_no_filter_match)
    state.view == JobsView.SCHEDULED -> stringResource(R.string.empty_no_scheduled)
    state.view == JobsView.COMPLETED -> stringResource(R.string.empty_no_completed)
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
        colors = appTopBarColors(),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JobRow(
    item: JobListItem,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onToggleDone: () -> Unit,
    onToggleInProgress: () -> Unit,
    onDeleteRequest: () -> Unit,
    onToggleExpanded: () -> Unit,
    onScheduleRequest: () -> Unit,
    onUnschedule: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val job = item.job
    val isSubtask = job.parentId != null

    // Subtasks: indented, smaller title, no border, so they sit under their parent.
    val indent = if (isSubtask) Spacing.xxl else 0.dp
    val rowPadding = if (isSubtask) Spacing.md else Spacing.lg
    val titleStyle = if (isSubtask) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium

    Card(
        onClick = onClick,
        colors = if (isSubtask) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        } else {
            appCardColors()
        },
        border = if (isSubtask) null else appCardBorder(),
        modifier = Modifier.fillMaxWidth().padding(start = indent)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(rowPadding),
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
                    style = titleStyle,
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
                // FlowRow wraps badges to a second line instead of squashing the last one.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    if (job.isInProgress) InfoBadge(text = stringResource(R.string.badge_in_progress))
                    TimeBucketBadge(minutes = item.displayMinutes)
                    item.costCents?.let { InfoBadge(text = formatCost(it)) }
                    if (job.category.isNotBlank()) CategoryBadge(category = job.category)
                    if (item.subtaskTotal > 0) InfoBadge(text = stringResource(R.string.subtasks_done_count, item.subtaskDone, item.subtaskTotal))
                    if (job.recurrenceDays != null) InfoBadge(text = recurrenceLabel(job))
                    if (item.scheduledDate != null) {
                        val label = if (job.isMissed()) R.string.badge_missed else R.string.badge_scheduled
                        InfoBadge(text = stringResource(label, formatScheduledDateTime(item.scheduledDate)))
                    }
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
                if (job.recurrenceDays != null) {
                    Text(
                        dueStatusLabel(job),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (job.isOverdue()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Expand/collapse for parents. Groups start collapsed (search and filters open them).
            if (item.subtaskTotal > 0) {
                IconButton(onClick = onToggleExpanded) {
                    Icon(
                        if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = stringResource(
                            if (isExpanded) R.string.cd_collapse_subtasks else R.string.cd_expand_subtasks
                        )
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
                    // Repeating jobs can't be scheduled. A missed booking offers Schedule again.
                    if (job.recurrenceDays == null) {
                        if (job.isBooked()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_unschedule)) },
                                leadingIcon = { Icon(Icons.Filled.EventBusy, contentDescription = null) },
                                onClick = { menuExpanded = false; onUnschedule() }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_schedule)) },
                                leadingIcon = { Icon(Icons.Filled.Event, contentDescription = null) },
                                onClick = { menuExpanded = false; onScheduleRequest() }
                            )
                        }
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
