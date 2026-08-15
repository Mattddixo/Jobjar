package com.mattdixon.jobjar.ui.draw

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.LONG_JOB_MINUTES
import com.mattdixon.jobjar.ui.components.CategoryBadge
import com.mattdixon.jobjar.ui.components.InfoBadge
import com.mattdixon.jobjar.ui.components.TimeBucketBadge
import com.mattdixon.jobjar.util.formatMinutes
import com.mattdixon.jobjar.util.formatRecurrenceInterval
import kotlin.math.roundToInt

private val TIME_PRESETS = listOf(15, 30, 45, 60, 90, 120)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawScreen(
    repository: JobRepository,
    onOpenJob: (Long) -> Unit
) {
    val viewModel: DrawViewModel = viewModel(factory = DrawViewModel.Factory(repository))
    val state by viewModel.uiState.collectAsState()
    var showForceCompleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("The Job Jar") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                // Without this, the time selector + category chips + draw button + drawn job
                // card (title, badges, notes, subtask progress, three buttons) can add up to
                // more height than the screen. A plain Column doesn't clip or warn about that -
                // it just lays the overflow out past the bottom edge, so the Skip/Mark done/
                // View details buttons could end up positioned off-screen: present, but neither
                // visible nor tappable.
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            JarSummaryCard(available = state.availableCount, completed = state.completedCount)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Time available", style = MaterialTheme.typography.labelLarge)
                        Text(
                            if (state.longJobsOnly) "4+ hrs" else formatMinutes(state.availableMinutes),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    Slider(
                        value = state.availableMinutes.toFloat(),
                        onValueChange = { viewModel.setAvailableMinutes(it.toInt()) },
                        valueRange = 5f..240f,
                        enabled = !state.longJobsOnly
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(TIME_PRESETS) { minutes ->
                            FilterChip(
                                selected = !state.longJobsOnly && state.availableMinutes == minutes,
                                onClick = { viewModel.setAvailableMinutes(minutes) },
                                label = { Text(formatMinutes(minutes)) }
                            )
                        }
                        item {
                            // Not a ceiling like the other chips - an explicit "pull from the big
                            // projects" request, since the slider above can't reach past 4 hours.
                            FilterChip(
                                selected = state.longJobsOnly,
                                onClick = { viewModel.setLongJobsOnly() },
                                label = { Text("4+ hrs") }
                            )
                        }
                    }

                    if (state.categories.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Category", style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item {
                                FilterChip(
                                    selected = state.selectedCategory == null,
                                    onClick = { viewModel.setCategory(null) },
                                    label = { Text("Any") }
                                )
                            }
                            items(state.categories) { category ->
                                FilterChip(
                                    selected = state.selectedCategory == category,
                                    onClick = {
                                        viewModel.setCategory(if (state.selectedCategory == category) null else category)
                                    },
                                    label = { Text(category) }
                                )
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { viewModel.draw() },
                enabled = !state.isDrawing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(Icons.Filled.Shuffle, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Draw a job")
            }

            AnimatedContent(
                targetState = state.drawnJob,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                label = "drawn-job"
            ) { drawnJob ->
                when {
                    drawnJob != null -> DrawnJobCard(
                        job = drawnJob,
                        context = state.drawnContext,
                        isBusy = state.isDrawing,
                        onOpen = { onOpenJob(drawnJob.id) },
                        onDone = {
                            val context = state.drawnContext
                            val hasOpenSubtasks = context != null && context.subtaskDone < context.subtaskTotal
                            if (hasOpenSubtasks) {
                                showForceCompleteDialog = true
                            } else {
                                viewModel.completeDrawnJob()
                            }
                        },
                        onSkip = { viewModel.draw(excludeCurrent = true) }
                    )
                    state.noMatchFound -> Text(
                        if (state.longJobsOnly) {
                            "Nothing needs ${formatMinutes(LONG_JOB_MINUTES)}+ yet. Try a shorter time, or add a bigger job."
                        } else {
                            "No jobs fit that time and category. Try a longer time or add more jobs."
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    else -> Text(
                        "Set your time and tap \"Draw a job\" to pick something from the jar.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showForceCompleteDialog) {
        val context = state.drawnContext
        val incompleteCount = (context?.subtaskTotal ?: 0) - (context?.subtaskDone ?: 0)
        AlertDialog(
            onDismissRequest = { showForceCompleteDialog = false },
            title = { Text("Mark as done?") },
            text = {
                Text("$incompleteCount subtask(s) are still open. They'll stay open, but this job will be marked done.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.completeDrawnJob()
                    showForceCompleteDialog = false
                }) { Text("Mark done") }
            },
            dismissButton = {
                TextButton(onClick = { showForceCompleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * A jar-shaped fill meter: how much of the jar still has jobs left in it. [available] and
 * [completed] are real counts from [DrawUiState] (every job and subtask, split by
 * [com.mattdixon.jobjar.data.Job.isPending]) - the fill level is available/(available+completed),
 * not a canned animation, so it visibly drains as jobs get done and refills as repeating ones
 * come back due or new ones get added. Wrapped in its own tonal card so it reads as the screen's
 * one clear focal point rather than competing with the form below it.
 */
@Composable
private fun JarSummaryCard(available: Int, completed: Int, modifier: Modifier = Modifier) {
    val total = available + completed
    val fraction = if (total == 0) 0f else available.toFloat() / total
    val fillColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(modifier = Modifier.size(width = 68.dp, height = 92.dp)) {
                val neckWidth = size.width * 0.46f
                val neckLeft = (size.width - neckWidth) / 2f
                val bodyTop = size.height * 0.2f
                val bodyCorner = size.width * 0.16f

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

                val strokeWidth = 2.5.dp.toPx()
                drawPath(bodyPath, color = outlineColor, style = Stroke(width = strokeWidth))
                drawPath(neckPath, color = outlineColor, style = Stroke(width = strokeWidth))
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$available available", style = MaterialTheme.typography.titleMedium)
                Text(
                    "$completed completed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (total > 0) {
                    Text(
                        "Jar is ${(fraction * 100).roundToInt()}% full",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawnJobCard(
    job: Job,
    context: DrawnJobContext?,
    isBusy: Boolean,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onSkip: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(job.title, style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeBucketBadge(minutes = context?.remainingMinutes ?: job.estimatedMinutes)
                if (job.category.isNotBlank()) CategoryBadge(category = job.category)
                job.recurrenceDays?.let { InfoBadge(text = formatRecurrenceInterval(it)) }
            }
            if (context?.parentTitle != null) {
                Text(
                    "Part of: ${context.parentTitle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (context != null && context.subtaskTotal > 0) {
                Text(
                    "${context.subtaskDone}/${context.subtaskTotal} subtasks done · ${formatMinutes(context.remainingMinutes ?: job.estimatedMinutes)} left of ${formatMinutes(job.estimatedMinutes)} total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (job.notes.isNotBlank()) {
                Text(job.notes, style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(onClick = onSkip, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                    Text("Skip")
                }
                Button(onClick = onDone, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                    Text("Mark done")
                }
            }
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text("View details")
            }
        }
    }
}
