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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
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
    onOpenJob: (Long) -> Unit,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val viewModel: DrawViewModel = viewModel(factory = DrawViewModel.Factory(repository))
    val state by viewModel.uiState.collectAsState()
    var forceCompleteJobId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("The Job Jar") },
                actions = {
                    TextButton(onClick = onToggleTheme) {
                        Text(if (darkTheme) "Light" else "Dark")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                // The screen's header (jar + picker panel + button) is fixed, but the results
                // below it can range from one card to ten - rather than squeezing that into
                // whatever sliver of space happens to be left under the header (which is what a
                // weight(1f) region does, and reads as "barely see anything"), the whole page is
                // one scroll region. That's also the only sound option in Compose terms: a
                // verticalScroll() container measures its content with unbounded height, so a
                // weight()ed child inside it isn't just visually cramped, it's not a supported
                // combination in the first place.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            JarHero(pendingCount = state.pendingCount)

            PickerPanel(
                state = state,
                onAvailableMinutesChange = viewModel::setAvailableMinutes,
                onLongJobsOnly = viewModel::setLongJobsOnly,
                onCategorySelect = viewModel::setCategory,
                onBatchSizeChange = viewModel::setBatchSize
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

            // Keyed on "is there anything drawn" rather than the drawn-jobs list itself, so a
            // change to the batch (e.g. "Skip all" redrawing it) doesn't cause a jarring
            // full-list crossfade for no reason - only empty<->populated transitions animate.
            // The content lambda still reads state.drawnJobs live on every recomposition
            // regardless of whether the crossfade itself re-triggers.
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
                        onDone = { jobId ->
                            val entry = state.drawnJobs.find { it.job.id == jobId }
                            val hasOpenSubtasks = entry?.context != null &&
                                entry.context.subtaskDone < entry.context.subtaskTotal
                            if (hasOpenSubtasks) {
                                forceCompleteJobId = jobId
                            } else {
                                viewModel.completeJob(jobId)
                            }
                        },
                        onSkipAll = { viewModel.draw(excludeCurrent = true) }
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

    forceCompleteJobId?.let { jobId ->
        val entry = state.drawnJobs.find { it.job.id == jobId }
        val incompleteCount = (entry?.context?.subtaskTotal ?: 0) - (entry?.context?.subtaskDone ?: 0)
        AlertDialog(
            onDismissRequest = { forceCompleteJobId = null },
            title = { Text("Mark as done?") },
            text = {
                Text("$incompleteCount subtask(s) are still open. They'll stay open, but this job will be marked done.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.completeJob(jobId)
                    forceCompleteJobId = null
                }) { Text("Mark done") }
            },
            dismissButton = {
                TextButton(onClick = { forceCompleteJobId = null }) { Text("Cancel") }
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

/**
 * The actually-functional part of the screen: time budget, how many jobs to try to draw, and
 * category filter. Each narrows down to a single dropdown chip - showing the current value
 * doubles as the control that changes it - instead of a whole row of preset chips each.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerPanel(
    state: DrawUiState,
    onAvailableMinutesChange: (Int) -> Unit,
    onLongJobsOnly: () -> Unit,
    onCategorySelect: (String?) -> Unit,
    onBatchSizeChange: (DrawBatchSize) -> Unit
) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    var timeMenuExpanded by remember { mutableStateOf(false) }
    var batchMenuExpanded by remember { mutableStateOf(false) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }

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
                Box {
                    FilterChip(
                        selected = true,
                        onClick = { timeMenuExpanded = true },
                        label = {
                            Text(if (state.longJobsOnly) "4+ hrs" else formatMinutes(state.availableMinutes))
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
                        // Not a duration like the presets above it - picking this flips the draw
                        // from "fits this time" to "needs 4+ hours" and ignores the slider
                        // entirely, but it's still just one more value this control can be set to.
                        DropdownMenuItem(
                            text = { Text("4+ hrs") },
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
                // The default inactive-track color derives from surfaceVariant, which in light
                // theme is nearly the same tone as this panel's own card background - the track
                // all but disappears. outlineVariant has real contrast against the card in both
                // themes, so the unfilled part of the bar stays visibly a bar.
                colors = SliderDefaults.colors(
                    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                    disabledInactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )
            )

            // "4+ hrs" always draws exactly one job (there's no remaining budget to keep
            // filling after an open-ended pick), so this control only makes sense - and only
            // shows - for a normal time-budget draw.
            if (!state.longJobsOnly) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel("HOW MANY")
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
                HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel("CATEGORY")
                    Box {
                        FilterChip(
                            selected = state.selectedCategory != null,
                            onClick = { categoryMenuExpanded = true },
                            label = { Text(state.selectedCategory ?: "Any") },
                            trailingIcon = {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                        DropdownMenu(expanded = categoryMenuExpanded, onDismissRequest = { categoryMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Any") },
                                onClick = {
                                    onCategorySelect(null)
                                    categoryMenuExpanded = false
                                }
                            )
                            state.categories.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category) },
                                    onClick = {
                                        onCategorySelect(category)
                                        categoryMenuExpanded = false
                                    }
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
            .padding(vertical = 16.dp)
    )
}

/**
 * The current draw's results: an optional "how much budget is left" summary (only worth
 * showing when more than one job was actually requested - for the default single-job draw it'd
 * just be noise), each card at its natural size (no inner scroll region or height cap of its
 * own - the whole screen is the one scroll owner, see [DrawScreen]), and one "Skip all" button
 * after the last card that redraws the whole batch. Each card is wrapped in `key(entry.job.id)`
 * so a card that gets replaced (e.g. after "Skip all" redraws the batch) is a genuinely fresh
 * composable instance rather than reusing whatever remembered state occupied that slot before.
 */
@Composable
private fun DrawnJobsBatch(
    entries: List<DrawnJobEntry>,
    showBudgetSummary: Boolean,
    availableMinutes: Int,
    remainingMinutes: Int,
    isBusy: Boolean,
    onOpen: (Long) -> Unit,
    onDone: (Long) -> Unit,
    onSkipAll: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showBudgetSummary) {
            Text(
                "${entries.size} job(s) · ${formatMinutes(remainingMinutes)} left of ${formatMinutes(availableMinutes)}",
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
                    onDone = { onDone(entry.job.id) }
                )
            }
        }
        OutlinedButton(
            onClick = onSkipAll,
            enabled = !isBusy,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
        ) {
            Text("Skip all", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * A drawn job gets the app's one reserved accent (tertiary) - every other surface on this
 * screen is in the primary family, so this card reads as "the spotlight," not just another
 * panel. The whole card is tappable to open the job's detail page - a dedicated "View details"
 * button would just be redundant with that. "Skip all" (below the list) redraws the whole
 * batch. Notes are deliberately left off (available on the detail page) so a long description
 * can't grow a card unpredictably.
 */
@Composable
private fun BatchJobCard(
    entry: DrawnJobEntry,
    isBusy: Boolean,
    onOpen: () -> Unit,
    onDone: () -> Unit
) {
    val job = entry.job
    val context = entry.context

    Card(
        onClick = onOpen,
        shape = PanelShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
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
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (context != null && context.subtaskTotal > 0) {
                Text(
                    "${context.subtaskDone}/${context.subtaskTotal} subtasks done · ${formatMinutes(context.remainingMinutes ?: job.estimatedMinutes)} left",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = onDone,
                enabled = !isBusy,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            ) {
                Text("Mark done", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

