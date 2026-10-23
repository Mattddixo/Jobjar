package com.mattdixon.jobjar.ui.draw

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.LONG_JOB_MINUTES
import com.mattdixon.jobjar.ui.components.CategoryBadge
import com.mattdixon.jobjar.ui.components.InfoBadge
import com.mattdixon.jobjar.ui.components.SchedulePickerDialog
import com.mattdixon.jobjar.ui.components.TimeBucketBadge
import com.mattdixon.jobjar.ui.components.TodayDateButton
import com.mattdixon.jobjar.ui.theme.AppShapes
import com.mattdixon.jobjar.ui.theme.Spacing
import com.mattdixon.jobjar.ui.theme.appCardBorder
import com.mattdixon.jobjar.ui.theme.appCardColors
import com.mattdixon.jobjar.util.formatMinutes
import com.mattdixon.jobjar.ui.components.plural
import com.mattdixon.jobjar.ui.components.recurrenceLabel

private val TIME_PRESETS = listOf(15, 30, 45, 60, 90, 120)

/** Jar looks full from this many jobs up. Only affects the picture. */
private const val JAR_FILL_CAP = 15

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawScreen(
    repository: JobRepository,
    onOpenJob: (Long) -> Unit,
    onOpenSettings: () -> Unit
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: DrawViewModel = viewModel(factory = DrawViewModel.Factory(repository, appContext))
    val state by viewModel.uiState.collectAsState()
    var jobIdPendingSchedule by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        TodayDateButton()
                        Text(stringResource(R.string.draw_screen_title))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                // One scroll for the whole page. The results can be anything from 1 to 10 cards, which
                // doesn't fit in whatever space is left under the header.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            JarHero(pendingCount = state.pendingCount, inProgressCount = state.inProgressCount, isLoaded = state.isLoaded)

            PickerPanel(
                state = state,
                onAvailableMinutesChange = viewModel::setAvailableMinutes,
                onLongJobsOnly = viewModel::setLongJobsOnly,
                onToggleCategory = viewModel::toggleCategory,
                onBatchSizeChange = viewModel::setBatchSize
            )

            Button(
                onClick = { viewModel.draw() },
                enabled = !state.isDrawing,
                shape = AppShapes.action,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(Spacing.md))
                Text(stringResource(R.string.draw_button), style = MaterialTheme.typography.titleSmall)
            }

            // Keyed on empty vs not, so a redraw doesn't crossfade the whole list.
            AnimatedContent(
                targetState = state.drawnJobs.isNotEmpty(),
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                label = "drawn-jobs"
            ) { hasDrawnJobs ->
                when {
                    hasDrawnJobs -> DrawnJobsBatch(
                        entries = state.drawnJobs,
                        showBudgetSummary = !state.longJobsOnly && state.batchSize != DrawBatchSize.ONE,
                        availableMinutes = state.availableMinutes,
                        remainingMinutes = state.remainingMinutesAfterDraw,
                        isBusy = state.isDrawing,
                        onOpen = { jobId -> onOpenJob(jobId) },
                        onStart = { jobId -> viewModel.startJob(jobId) },
                        onScheduleRequest = { jobId -> jobIdPendingSchedule = jobId },
                        onRedraw = { viewModel.draw(excludeCurrent = true) },
                        onCloseJar = { viewModel.clearDraw() }
                    )
                    state.noMatchFound -> EmptyStateText(
                        if (state.longJobsOnly) {
                            stringResource(R.string.draw_no_match_long, formatMinutes(LONG_JOB_MINUTES))
                        } else {
                            stringResource(R.string.draw_no_match_short)
                        }
                    )
                    else -> EmptyStateText(stringResource(R.string.draw_empty_prompt))
                }
            }
        }
    }

    jobIdPendingSchedule?.let { jobId ->
        SchedulePickerDialog(
            onDismiss = { jobIdPendingSchedule = null },
            onConfirm = { dateTimeMillis ->
                viewModel.scheduleJob(jobId, dateTimeMillis)
                jobIdPendingSchedule = null
            }
        )
    }
}

/**
 * Jar picture plus how many jobs are in it. Started jobs have left the jar so they get their own
 * line (hidden when zero).
 */
@Composable
private fun JarHero(pendingCount: Int, inProgressCount: Int, isLoaded: Boolean, modifier: Modifier = Modifier) {
    val fraction = (pendingCount.toFloat() / JAR_FILL_CAP).coerceIn(0f, 1f)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        JarGlyph(fraction = fraction, modifier = Modifier.size(width = 52.dp, height = 70.dp))
        Spacer(modifier = Modifier.width(Spacing.lg))
        Column {
            Text(
                // Blank until loaded rather than flashing "0 jobs".
                if (isLoaded) plural(R.plurals.draw_jar_count, pendingCount, pendingCount) else "",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            if (inProgressCount > 0) {
                Text(
                    plural(R.plurals.draw_in_progress_count, inProgressCount, inProgressCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** [fraction] 0..1. */
@Composable
private fun JarGlyph(fraction: Float, modifier: Modifier = Modifier) {
    val fillColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Canvas(modifier = modifier) {
        val neckWidth = size.width * 0.46f
        val neckLeft = (size.width - neckWidth) / 2f
        val bodyTop = size.height * 0.2f
        val bodyCorner = size.width * 0.18f

        val bodyPath = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = bodyTop,
                    right = size.width,
                    bottom = size.height,
                    cornerRadius = CornerRadius(bodyCorner, bodyCorner)
                )
            )
        }
        val neckPath = Path().apply {
            addRoundRect(
                RoundRect(
                    left = neckLeft,
                    top = 0f,
                    right = neckLeft + neckWidth,
                    bottom = bodyTop + bodyCorner,
                    cornerRadius = CornerRadius(bodyCorner * 0.6f, bodyCorner * 0.6f)
                )
            )
        }

        clipPath(bodyPath) { drawRect(color = trackColor) }
        drawPath(neckPath, color = trackColor)

        val bodyHeight = size.height - bodyTop
        val fillHeight = bodyHeight * fraction
        if (fillHeight > 0f) {
            clipPath(bodyPath) {
                drawRect(
                    color = fillColor,
                    topLeft = Offset(0f, size.height - fillHeight),
                    size = Size(size.width, fillHeight)
                )
            }
        }

        val strokeWidth = 2.dp.toPx()
        drawPath(bodyPath, color = outlineColor, style = Stroke(width = strokeWidth))
        drawPath(neckPath, color = outlineColor, style = Stroke(width = strokeWidth))
    }
}

/** Time, how many, and category, each as a dropdown chip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerPanel(
    state: DrawUiState,
    onAvailableMinutesChange: (Int) -> Unit,
    onLongJobsOnly: () -> Unit,
    onToggleCategory: (String) -> Unit,
    onBatchSizeChange: (DrawBatchSize) -> Unit
) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    var timeMenuExpanded by remember { mutableStateOf(false) }
    var batchMenuExpanded by remember { mutableStateOf(false) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    val longJobLabel = stringResource(R.string.long_job_chip_label)

    Card(
        shape = AppShapes.panel,
        colors = appCardColors(),
        border = appCardBorder(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel(stringResource(R.string.draw_section_time_available))
                Box {
                    FilterChip(
                        selected = true,
                        onClick = { timeMenuExpanded = true },
                        label = {
                            Text(if (state.longJobsOnly) longJobLabel else formatMinutes(state.availableMinutes))
                        },
                        trailingIcon = {
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                    DropdownMenu(expanded = timeMenuExpanded, onDismissRequest = { timeMenuExpanded = false }) {
                        TIME_PRESETS.forEach { minutes ->
                            DropdownMenuItem(
                                text = { Text(formatMinutes(minutes)) },
                                onClick = {
                                    onAvailableMinutesChange(minutes)
                                    timeMenuExpanded = false
                                }
                            )
                        }
                        // Not a duration: switches to "4hr or longer" and the slider stops applying.
                        DropdownMenuItem(
                            text = { Text(longJobLabel) },
                            onClick = {
                                onLongJobsOnly()
                                timeMenuExpanded = false
                            }
                        )
                    }
                }
            }
            Slider(
                value = state.availableMinutes.toFloat(),
                onValueChange = { onAvailableMinutesChange(it.toInt()) },
                valueRange = 5f..240f,
                enabled = !state.longJobsOnly,
                thumb = {
                    SliderDefaults.Thumb(
                        interactionSource = remember { MutableInteractionSource() },
                        thumbSize = DpSize(16.dp, 16.dp)
                    )
                },
                // Default inactive track nearly disappears on the white card.
                colors = SliderDefaults.colors(
                    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                    disabledInactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )
            )

            // 4+ hrs always draws one job, so "how many" only shows for normal draws.
            if (!state.longJobsOnly) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel(stringResource(R.string.draw_section_how_many))
                    Box {
                        FilterChip(
                            selected = state.batchSize != DrawBatchSize.ONE,
                            onClick = { batchMenuExpanded = true },
                            label = { Text(state.batchSize.label) },
                            trailingIcon = {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                        DropdownMenu(expanded = batchMenuExpanded, onDismissRequest = { batchMenuExpanded = false }) {
                            DrawBatchSize.entries.forEach { size ->
                                DropdownMenuItem(
                                    text = { Text(size.label) },
                                    onClick = {
                                        onBatchSizeChange(size)
                                        batchMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (state.categories.isNotEmpty()) {
                HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = Spacing.xxs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel(stringResource(R.string.draw_section_category))
                    Box {
                        // Multi-select like the Jobs list. Label: "Any", the category name, or "N categories".
                        val categoryLabel = when {
                            state.selectedCategories.isEmpty() -> stringResource(R.string.draw_category_any)
                            state.selectedCategories.size == 1 -> state.selectedCategories.first()
                            else -> stringResource(R.string.draw_category_n_selected, state.selectedCategories.size)
                        }
                        FilterChip(
                            selected = state.selectedCategories.isNotEmpty(),
                            onClick = { categoryMenuExpanded = true },
                            label = { Text(categoryLabel) },
                            trailingIcon = {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            // Min width so the chip (and its menu) doesn't jump around as the label changes.
                            modifier = Modifier.widthIn(min = 88.dp)
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
                                    onClick = { onToggleCategory(category) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.8.sp
    )
}

@Composable
private fun EmptyStateText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xl)
    )
}

/**
 * The drawn jobs, with a "time spare" line when you asked for more than one, then Draw again /
 * Never mind. Drawing doesn't take anything out of the jar, so Never mind just clears the list.
 */
@Composable
private fun DrawnJobsBatch(
    entries: List<DrawnJobEntry>,
    showBudgetSummary: Boolean,
    availableMinutes: Int,
    remainingMinutes: Int,
    isBusy: Boolean,
    onOpen: (Long) -> Unit,
    onStart: (Long) -> Unit,
    onScheduleRequest: (Long) -> Unit,
    onRedraw: () -> Unit,
    onCloseJar: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (showBudgetSummary) {
            Text(
                plural(
                    R.plurals.draw_budget_summary,
                    entries.size,
                    entries.size,
                    formatMinutes(remainingMinutes),
                    formatMinutes(availableMinutes)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        entries.forEach { entry ->
            key(entry.job.id) {
                BatchJobCard(
                    entry = entry,
                    isBusy = isBusy,
                    onOpen = { onOpen(entry.job.id) },
                    onStart = { onStart(entry.job.id) },
                    onScheduleRequest = { onScheduleRequest(entry.job.id) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            OutlinedButton(
                onClick = onRedraw,
                enabled = !isBusy,
                shape = AppShapes.control,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
            ) {
                Text(stringResource(R.string.draw_redraw), style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = onCloseJar,
                enabled = !isBusy,
                shape = AppShapes.control,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
            ) {
                Text(stringResource(R.string.draw_close_jar), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * A drawn job, on the honey card. Tap for details. Actions are Start and Schedule; finishing
 * happens later from the job itself. Notes are left off to keep cards a predictable size.
 */
@Composable
private fun BatchJobCard(
    entry: DrawnJobEntry,
    isBusy: Boolean,
    onOpen: () -> Unit,
    onStart: () -> Unit,
    onScheduleRequest: () -> Unit
) {
    val job = entry.job
    val context = entry.context

    Card(
        onClick = onOpen,
        shape = AppShapes.card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                job.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                TimeBucketBadge(minutes = context?.remainingMinutes ?: job.estimatedMinutes, compact = true)
                if (job.category.isNotBlank()) CategoryBadge(category = job.category, compact = true)
                job.recurrenceDays?.let { InfoBadge(text = recurrenceLabel(it, job.repeatWeekdays), compact = true) }
            }
            if (context?.parentTitle != null) {
                Text(
                    stringResource(R.string.label_part_of, context.parentTitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (context != null && context.subtaskTotal > 0) {
                Text(
                    stringResource(
                        R.string.draw_subtasks_progress,
                        context.subtaskDone,
                        context.subtaskTotal,
                        formatMinutes(context.remainingMinutes ?: job.estimatedMinutes)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // No scheduling for repeating jobs.
            if (job.recurrenceDays == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(
                        onClick = onStart,
                        enabled = !isBusy,
                        shape = AppShapes.control,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                    ) {
                        Text(stringResource(R.string.action_start), style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = onScheduleRequest,
                        enabled = !isBusy,
                        shape = AppShapes.control,
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                    ) {
                        Text(stringResource(R.string.action_schedule), style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = !isBusy,
                    shape = AppShapes.control,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                ) {
                    Text(stringResource(R.string.action_start), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
