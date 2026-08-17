package com.mattdixon.jobjar.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.ui.theme.AppShapes
import com.mattdixon.jobjar.ui.theme.Spacing
import com.mattdixon.jobjar.util.formatMinutes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    repository: JobRepository,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory(repository))
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_screen_title)) },
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
                .padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                StatCard(
                    label = stringResource(R.string.stat_active_jobs),
                    value = state.activeCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = stringResource(R.string.stat_completed),
                    value = state.completedCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = stringResource(R.string.stat_time_invested),
                    value = formatMinutes(state.totalMinutesCompleted),
                    modifier = Modifier.weight(1f)
                )
            }

            Text(stringResource(R.string.stats_by_category), style = MaterialTheme.typography.titleMedium)

            if (state.categoryStats.isEmpty()) {
                Text(
                    stringResource(R.string.stats_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Sorted descending by totalMinutes already, so the first entry is the max -
                // used to scale each bar relative to your biggest time sink.
                val maxMinutes = state.categoryStats.first().totalMinutes.coerceAtLeast(1)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    items(state.categoryStats, key = { it.category }) { stat ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Spacing.xl),
                                verticalArrangement = Arrangement.spacedBy(Spacing.md)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stat.category, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        stringResource(R.string.stats_category_summary, stat.completedCount, formatMinutes(stat.totalMinutes)),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { stat.totalMinutes.toFloat() / maxMinutes },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(AppShapes.hairline)
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
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
