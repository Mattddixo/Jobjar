package com.mattdixon.jobjar.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.SpendingGroup
import com.mattdixon.jobjar.data.SpendingItem
import com.mattdixon.jobjar.data.spendingBreakdown
import com.mattdixon.jobjar.ui.components.plural
import com.mattdixon.jobjar.ui.theme.AppShapes
import com.mattdixon.jobjar.ui.theme.Spacing
import com.mattdixon.jobjar.ui.theme.appCardBorder
import com.mattdixon.jobjar.ui.theme.appCardColors
import com.mattdixon.jobjar.ui.theme.appTopBarColors
import com.mattdixon.jobjar.util.formatCost
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Categories show this many jobs until you ask for the rest. */
private const val ITEMS_SHOWN = 5

/**
 * Stats > Spent: where the money went, all time or a year at a time. Categories biggest first,
 * each with its share and its jobs (top few, then Show all). Tap a job to open it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingScreen(repository: JobRepository, onOpenJob: (Long) -> Unit, onBack: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val locale = LocalConfiguration.current.locales[0]
    val jobs by repository.allJobsFlat.collectAsState(initial = emptyList())
    val history by remember { repository.completionsBetween(0, Long.MAX_VALUE) }.collectAsState(initial = emptyList())
    // Null = all time.
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    val breakdown = remember(jobs, history, year) { spendingBreakdown(jobs, history, year, zone) }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = appTopBarColors(),
                title = { Text(stringResource(R.string.spending_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Spacing.xl, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            if (breakdown.years.size > 1 || (breakdown.years.size == 1 && breakdown.undatedCents > 0)) {
                item(key = "periods") {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        FilterChip(
                            selected = year == null,
                            onClick = { year = null },
                            label = { Text(stringResource(R.string.spending_all_time)) }
                        )
                        breakdown.years.forEach { y ->
                            FilterChip(selected = year == y, onClick = { year = y }, label = { Text(y.toString()) })
                        }
                    }
                }
            }

            item(key = "total") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(formatCost(breakdown.totalCents), style = MaterialTheme.typography.headlineMedium)
                    val jobCount = breakdown.groups.sumOf { it.items.size }
                    Text(
                        if (year == null) {
                            plural(R.plurals.spending_summary_all_time, jobCount, jobCount)
                        } else {
                            plural(R.plurals.spending_summary_year, jobCount, jobCount, year.toString())
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (year == null && breakdown.undatedCents > 0 && breakdown.years.isNotEmpty()) {
                        Text(
                            stringResource(R.string.spending_undated_note, formatCost(breakdown.undatedCents)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (breakdown.groups.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.spending_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(breakdown.groups, key = { it.category }) { group ->
                SpendingGroupCard(
                    group = group,
                    totalCents = breakdown.totalCents,
                    expanded = group.category in expanded,
                    onToggle = { expanded = if (group.category in expanded) expanded - group.category else expanded + group.category },
                    onOpenJob = onOpenJob,
                    zone = zone,
                    locale = locale
                )
            }
        }
    }
}

@Composable
private fun SpendingGroupCard(
    group: SpendingGroup,
    totalCents: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenJob: (Long) -> Unit,
    zone: ZoneId,
    locale: Locale
) {
    val share = if (totalCents > 0) group.cents.toFloat() / totalCents else 0f
    Card(colors = appCardColors(), border = appCardBorder(), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = Spacing.md)) {
            Column(
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.category,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(formatCost(group.cents), style = MaterialTheme.typography.titleMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    LinearProgressIndicator(
                        progress = { share },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(AppShapes.hairline)
                    )
                    Text(
                        stringResource(R.string.spending_share, (share * 100).roundToInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val shown = if (expanded) group.items else group.items.take(ITEMS_SHOWN)
            shown.forEach { item ->
                HorizontalDivider(modifier = Modifier.padding(horizontal = Spacing.xl))
                SpendingRow(item, onClick = { onOpenJob(item.jobId) }, zone = zone, locale = locale)
            }
            if (group.items.size > ITEMS_SHOWN) {
                TextButton(onClick = onToggle, modifier = Modifier.padding(horizontal = Spacing.md)) {
                    Text(
                        if (expanded) {
                            stringResource(R.string.spending_show_fewer)
                        } else {
                            stringResource(R.string.spending_show_all, group.items.size)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SpendingRow(item: SpendingItem, onClick: () -> Unit, zone: ZoneId, locale: Locale) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val date = item.lastDoneAt?.let { formatDoneDate(it, zone, locale) }
            val done = when {
                item.times > 1 && date != null -> plural(R.plurals.spending_done_times_last, item.times, item.times, date)
                item.times > 1 -> plural(R.plurals.spending_done_times, item.times, item.times)
                date != null -> stringResource(R.string.spending_done_on, date)
                else -> stringResource(R.string.spending_done)
            }
            Text(
                item.parentTitle?.let { stringResource(R.string.spending_part_of, it, done) } ?: done,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(formatCost(item.cents), style = MaterialTheme.typography.bodyLarge)
    }
}

/** "Oct 12" this year, "Oct 12, 2024" before. */
private fun formatDoneDate(at: Long, zone: ZoneId, locale: Locale): String {
    val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val pattern = if (date.year == LocalDate.now(zone).year) "MMM d" else "MMM d, yyyy"
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}
