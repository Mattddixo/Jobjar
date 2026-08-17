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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.LONG_JOB_MINUTES
import com.mattdixon.jobjar.ui.components.CategoryBadge
import com.mattdixon.jobjar.ui.components.InfoBadge
import com.mattdixon.jobjar.ui.components.TimeBucketBadge
import com.mattdixon.jobjar.util.formatMinutes
import com.mattdixon.jobjar.util.formatRecurrenceInterval

private val TIME_PRESETS = listOf(15, 30, 45, 60, 90, 120)

/** Shared rounding for every surface on this screen so they read as one design language. */
private val PanelShape = RoundedCornerShape(20.dp)

/**
 * Jobs pending at or above this count show the jar as visually full - a soft cap for the fill
 * glyph, not a limit on how many jobs you can actually have. Chosen to look "getting full" at a
 * realistic personal backlog size rather than needing dozens of jobs to register visually.
 */
private const val JAR_FILL_CAP = 15

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
                // Every section below is sized to fit a normal phone screen without scrolling
                // by default (that's the actual design goal), but this stays as a safety net -
                // a large system font size or an unusually short screen shouldn't be able to
                // strand the Skip/Mark done/View details buttons somewhere unreachable.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            JarHero(pendingCount = state.pendingCount)

            PickerPanel(
                state = state,
                onAvailableMinutesChange = viewModel::setAvailableMinutes,
                onLongJobsOnly = viewModel::setLongJobsOnly,
                onCategorySelect = viewModel::setCategory
            )

            Button(
                onClick = { viewModel.draw() },
                enabled = !state.isDrawing,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Draw a job", style = MaterialTheme.typography.titleSmall)
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
                    state.noMatchFound -> EmptyStateText(
                        if (state.longJobsOnly) {
                            "Nothing needs ${formatMinutes(LONG_JOB_MINUTES)}+ yet. Try a shorter time, or add a bigger job."
                        } else {
                            "No jobs fit that time and category. Try a longer time or add more jobs."
                        }
                    )
                    else -> EmptyStateText("Set your time and tap \"Draw a job\" to pick something from the jar.")
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
 * The screen's one decorative moment: a jar glyph plus how many jobs are actually in it right
 * now. Deliberately just a count, not a dashboard - a completed-vs-pending ratio was here before
 * and it decayed toward "always looks full" as lifetime completions piled up, which stopped
 * meaning anything after a while. Anyone who wants completion history has the Stats tab for
 * that; this is just "how full does my jar look today."
 */
@Composable
private fun JarHero(pendingCount: Int, modifier: Modifier = Modifier) {
    val fraction = (pendingCount.toFloat() / JAR_FILL_CAP).coerceIn(0f, 1f)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        JarGlyph(fraction = fraction, modifier = Modifier.size(width = 52.dp, height = 70.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            "$pendingCount in the jar",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * The jar-shaped fill glyph itself - purely the picture, no data logic of its own. [fraction]
 * (0f-1f) is however the caller wants to represent "how full," decided by [JarHero].
 */
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

/** The actually-functional part of the screen: time budget and category filter. */
@Composable
private fun PickerPanel(
    state: DrawUiState,
    onAvailableMinutesChange: (Int) -> Unit,
    onLongJobsOnly: () -> Unit,
    onCategorySelect: (String?) -> Unit
) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Card(
        shape = PanelShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel("TIME AVAILABLE")
                Text(
                    if (state.longJobsOnly) "4+ hrs" else formatMinutes(state.availableMinutes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Slider(
                value = state.availableMinutes.toFloat(),
                onValueChange = { onAvailableMinutesChange(it.toInt()) },
                valueRange = 5f..240f,
                enabled = !state.longJobsOnly
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(TIME_PRESETS) { minutes ->
                    FilterChip(
                        selected = !state.longJobsOnly && state.availableMinutes == minutes,
                        onClick = { onAvailableMinutesChange(minutes) },
                        label = { Text(formatMinutes(minutes)) }
                    )
                }
                item {
                    // Not a ceiling like the other chips - an explicit "pull from the big
                    // projects" request, since the slider above can't reach past 4 hours.
                    FilterChip(
                        selected = state.longJobsOnly,
                        onClick = onLongJobsOnly,
                        label = { Text("4+ hrs") }
                    )
                }
            }

            if (state.categories.isNotEmpty()) {
                HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 2.dp))
                SectionLabel("CATEGORY")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = state.selectedCategory == null,
                            onClick = { onCategorySelect(null) },
                            label = { Text("Any") }
                        )
                    }
                    items(state.categories) { category ->
                        FilterChip(
                            selected = state.selectedCategory == category,
                            onClick = {
                                onCategorySelect(if (state.selectedCategory == category) null else category)
                            },
                            label = { Text(category) }
                        )
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
            .padding(vertical = 16.dp)
    )
}

/**
 * The whole card is tappable to open the job's detail page (notes, subtasks, everything) - the
 * dedicated "View details" button that used to sit here was redundant with that and just ate
 * space. Skip/Mark done stay as explicit buttons since those are the two actions you'd actually
 * take *without* leaving this screen; opening details is a "step away from the jar" action, so
 * it gets the plain-tap affordance instead of competing for button space. Notes are deliberately
 * left off this card (available on the detail page) so a long description can't push the card -
 * and with it, the buttons - past what fits on screen.
 */
@Composable
private fun DrawnJobCard(
    job: Job,
    context: DrawnJobContext?,
    isBusy: Boolean,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onSkip: () -> Unit
) {
    Card(onClick = onOpen, shape = PanelShape, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                job.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TimeBucketBadge(minutes = context?.remainingMinutes ?: job.estimatedMinutes)
                if (job.category.isNotBlank()) CategoryBadge(category = job.category)
                job.recurrenceDays?.let { InfoBadge(text = formatRecurrenceInterval(it)) }
            }
            if (context?.parentTitle != null) {
                Text(
                    "Part of: ${context.parentTitle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (context != null && context.subtaskTotal > 0) {
                Text(
                    "${context.subtaskDone}/${context.subtaskTotal} subtasks done · ${formatMinutes(context.remainingMinutes ?: job.estimatedMinutes)} left",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onSkip,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                ) {
                    Text("Skip", style = MaterialTheme.typography.labelLarge)
                }
                Button(
                    onClick = onDone,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                ) {
                    Text("Mark done", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
