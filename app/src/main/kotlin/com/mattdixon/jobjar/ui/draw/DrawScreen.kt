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
import com.mattdixon.jobjar.ui.theme.AppShapes
import com.mattdixon.jobjar.ui.theme.Spacing
import com.mattdixon.jobjar.util.formatMinutes
import com.mattdixon.jobjar.util.formatRecurrenceInterval

private val TIME_PRESETS = listOf(15, 30, 45, 60, 90, 120)

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
    val appContext = LocalContext.current.applicationContext
    val viewModel: DrawViewModel = viewModel(factory = DrawViewModel.Factory(repository, appContext))
    val state by viewModel.uiState.collectAsState()
    var jobIdPendingSchedule by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.draw_screen_title)) },
                actions = {
                    TextButton(onClick = onToggleTheme) {
                        Text(stringResource(if (darkTheme) R.string.theme_toggle_light else R.string.theme_toggle_dark))
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
                .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            JarHero(pendingCount = state.pendingCount, inProgressCount = state.inProgressCount)

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

            // Keyed on "is there anything drawn" rather than the drawn-jobs list itself, so a
            // change to the batch (e.g. "Redraw" replacing it) doesn't cause a jarring
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
 * The screen's one decorative moment: a jar glyph plus how many jobs are actually in it right
 * now. Deliberately just a count, not a dashboard - a completed-vs-pending ratio was here before
 * and it decayed toward "always looks full" as lifetime completions piled up, which stopped
 * meaning anything after a while. Anyone who wants completion history has the Stats tab for
 * that; this is just "how full does my jar look today." [pendingCount] (and the glyph's fill
 * level) only counts jobs actually available to draw - once a job is started it's left the jar,
 * so [inProgressCount] is shown as its own small line rather than folded into the main count,
 * and only when it's actually nonzero so an idle jar doesn't show a "0 in progress" line.
 */
@Composable
private fun JarHero(pendingCount: Int, inProgressCount: Int, modifier: Modifier = Modifier) {
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
                stringResource(R.string.draw_jar_count, pendingCount),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            if (inProgressCount > 0) {
                Text(
                    stringResource(R.string.draw_in_progress_count, inProgressCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
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
                        // Not a duration like the presets above it - picking this flips the draw
                        // from "fits this time" to "needs 4+ hours" and ignores the slider
                        // entirely, but it's still just one more value this control can be set to.
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
                        // Same checkbox multiselect as the Jobs list's own Category filter,
                        // rather than the single-pick dropdown this used to be - drawing from
                        // more than one category at once ("Home or Errands tonight") is just as
                        // reasonable a want here as it is when filtering that list. Unlike the
                        // Jobs list's fixed "Category" chip + count badge, this chip's label
                        // stays dynamic (it already was, before multiselect): "Any" with nothing
                        // picked, the category name itself with exactly one, or a plain count
                        // once there's more than one - "Any" plus a badge number would have
                        // directly contradicted each other, since "Any" means no restriction at all.
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
                            // The chip is right-pinned by this row's SpaceBetween, so a label
                            // that changes length (Any / a category name / "N categories") moves
                            // the chip's own left edge - and the dropdown it anchors - every time
                            // the selection changes. A minimum width absorbs that for every short
                            // label (which covers the everyday cases); it can still grow for an
                            // unusually long single category name, same as it always could.
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
 * The current draw's results: an optional "how much budget is left" summary (only worth
 * showing when more than one job was actually requested - for the default single-job draw it'd
 * just be noise), each card at its natural size (no inner scroll region or height cap of its
 * own - the whole screen is the one scroll owner, see [DrawScreen]), and two half-width buttons
 * after the last card - "Redraw" (fresh picks excluding whatever's currently shown) and
 * "Close jar" (just clears the batch back to the empty prompt; nothing was ever removed from the
 * jar by drawing it in the first place, so there's nothing to "put back" beyond that). Each card
 * is wrapped in `key(entry.job.id)` so a card that gets replaced (e.g. after "Redraw") is a
 * genuinely fresh composable instance rather than reusing whatever remembered state occupied
 * that slot before.
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
                stringResource(
                    R.string.draw_budget_summary,
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
 * A drawn job gets the app's one reserved accent (tertiary) - every other surface on this
 * screen is in the primary family, so this card reads as "the spotlight," not just another
 * panel. The whole card is tappable to open the job's detail page - a dedicated "View details"
 * button would just be redundant with that. The only action here is "Start" - a job being drawn
 * doesn't mean it's finished, just that you've picked it up, so completing it happens later from
 * its detail page or the Jobs list, once you actually are done. "Redraw" (below the list) draws
 * a fresh batch. Notes are deliberately left off (available on the detail page) so a long
 * description can't grow a card unpredictably.
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
                job.recurrenceDays?.let { InfoBadge(text = formatRecurrenceInterval(it), compact = true) }
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
            // Repeating jobs don't get a "Schedule" button - the same scope decision as
            // everywhere else this feature appears - so they keep the original full-width Start.
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
