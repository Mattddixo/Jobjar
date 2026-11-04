package com.mattdixon.jobjar.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.ui.components.TodayDateButton
import com.mattdixon.jobjar.ui.theme.AppShapes
import com.mattdixon.jobjar.ui.theme.Spacing
import com.mattdixon.jobjar.ui.theme.appTopBarColors
import com.mattdixon.jobjar.ui.theme.appCardBorder
import com.mattdixon.jobjar.ui.theme.appCardColors
import com.mattdixon.jobjar.util.formatCost
import com.mattdixon.jobjar.util.formatMinutes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    repository: JobRepository,
    onOpenSettings: () -> Unit,
    onOpenSpending: () -> Unit = {}
) {
    val viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory(repository))
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                colors = appTopBarColors(),
                title = {
                    Column {
                        TodayDateButton()
                        Text(stringResource(R.string.stats_screen_title))
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
                .verticalScroll(rememberScrollState())
                .padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl)
        ) {
            WeekHistoryCard(repository)

            val toDo = stringResource(R.string.stat_active_jobs) to state.activeCount.toString()
            val completed = stringResource(R.string.stat_completed) to state.completedCount.toString()
            val time = stringResource(R.string.stat_time_invested) to formatMinutes(state.totalMinutesCompleted)
            // Spent only appears once something with a cost has been completed; then the cards go
            // two by two so none of them gets squeezed. Tapping it shows where the money went.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                if (state.spentCents > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                        StatCard(label = toDo.first, value = toDo.second, modifier = Modifier.weight(1f))
                        StatCard(label = completed.first, value = completed.second, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                        StatCard(label = time.first, value = time.second, modifier = Modifier.weight(1f))
                        StatCard(
                            label = stringResource(R.string.stat_spent),
                            value = formatCost(state.spentCents),
                            onClick = onOpenSpending,
                            onClickLabel = stringResource(R.string.cd_open_spending),
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                        listOf(toDo, completed, time).forEach { (label, value) ->
                            StatCard(label = label, value = value, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Text(stringResource(R.string.stats_by_category), style = MaterialTheme.typography.titleMedium)

            if (state.categoryStats.isEmpty()) {
                Text(
                    stringResource(R.string.stats_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Already sorted biggest first, so the first one sets the bar scale.
                val maxMinutes = state.categoryStats.first().totalMinutes.coerceAtLeast(1)
                // One scroll for the page now the week's history sits above.
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    state.categoryStats.forEach { stat ->
                        Card(colors = appCardColors(), border = appCardBorder(), modifier = Modifier.fillMaxWidth()) {
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
                                        if (stat.spentCents > 0) {
                                            stringResource(
                                                R.string.stats_category_summary_with_cost,
                                                stat.completedCount,
                                                formatMinutes(stat.totalMinutes),
                                                formatCost(stat.spentCents)
                                            )
                                        } else {
                                            stringResource(R.string.stats_category_summary, stat.completedCount, formatMinutes(stat.totalMinutes))
                                        },
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

/** With [onClick], the card is a button and shows a chevron so it reads as one. */
@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null
) {
    Card(
        colors = appCardColors(),
        border = appCardBorder(),
        modifier = if (onClick != null) {
            modifier
                .clip(CardDefaults.shape)
                .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
        } else {
            modifier
        }
    ) {
        Row(modifier = Modifier.padding(Spacing.xl), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(value, style = MaterialTheme.typography.titleLarge)
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onClick != null) {
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
