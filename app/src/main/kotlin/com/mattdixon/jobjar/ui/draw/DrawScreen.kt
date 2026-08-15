package com.mattdixon.jobjar.ui.draw

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.LONG_JOB_MINUTES
import com.mattdixon.jobjar.ui.components.CategoryBadge
import com.mattdixon.jobjar.ui.components.TimeBucketBadge
import com.mattdixon.jobjar.util.formatMinutes

private val TIME_PRESETS = listOf(15, 30, 45, 60, 90, 120)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawScreen(
    repository: JobRepository,
    onOpenJob: (Long) -> Unit
) {
    val viewModel: DrawViewModel = viewModel(factory = DrawViewModel.Factory(repository))
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("The Job Jar") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How much time do you have?", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.longJobsOnly) "4+ hrs" else formatMinutes(state.availableMinutes),
                    style = MaterialTheme.typography.headlineMedium
                )
                Slider(
                    value = state.availableMinutes.toFloat(),
                    onValueChange = { viewModel.setAvailableMinutes(it.toInt()) },
                    valueRange = 5f..240f,
                    enabled = !state.longJobsOnly
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }

            if (state.categories.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Category", style = MaterialTheme.typography.titleMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

            Button(
                onClick = { viewModel.draw() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
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
                        onOpen = { onOpenJob(drawnJob.id) },
                        onDone = { viewModel.completeDrawnJob() },
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
}

@Composable
private fun DrawnJobCard(
    job: Job,
    context: DrawnJobContext?,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onSkip: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(job.title, style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeBucketBadge(minutes = context?.remainingMinutes ?: job.estimatedMinutes)
                if (job.category.isNotBlank()) CategoryBadge(category = job.category)
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
                OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                    Text("Skip")
                }
                Button(onClick = onDone, modifier = Modifier.weight(1f)) {
                    Text("Mark done")
                }
            }
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text("View details")
            }
        }
    }
}
