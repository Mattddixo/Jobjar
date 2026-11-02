package com.mattdixon.jobjar.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.Completion
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.ui.components.plural
import com.mattdixon.jobjar.ui.theme.Spacing
import com.mattdixon.jobjar.ui.theme.appCardBorder
import com.mattdixon.jobjar.ui.theme.appCardColors
import com.mattdixon.jobjar.util.formatMinutes
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What got done, a week at a time (Monday to Sunday), newest first and grouped by day. The
 * arrows step back through earlier weeks.
 */
@Composable
fun WeekHistoryCard(repository: JobRepository, modifier: Modifier = Modifier) {
    val zone = ZoneId.systemDefault()
    val locale = LocalConfiguration.current.locales[0]
    var weeksBack by rememberSaveable { mutableIntStateOf(0) }
    val today = LocalDate.now(zone)
    val weekStart = today.with(DayOfWeek.MONDAY).minusWeeks(weeksBack.toLong())
    val from = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = weekStart.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val entries by remember(from, to) { repository.completionsBetween(from, to) }.collectAsState(initial = emptyList())

    Card(colors = appCardColors(), border = appCardBorder(), modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { weeksBack++ }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.cd_previous_week))
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(weekTitle(weeksBack, weekStart, locale), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (entries.isEmpty()) {
                            stringResource(if (weeksBack == 0) R.string.stats_week_empty_now else R.string.stats_week_empty_past)
                        } else {
                            plural(R.plurals.stats_week_summary, entries.size, entries.size, formatMinutes(entries.sumOf { it.estimatedMinutes }))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { weeksBack-- }, enabled = weeksBack > 0) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.cd_next_week))
                }
            }
            entries.groupBy { it.day(zone) }.forEach { (day, done) ->
                Text(
                    dayLabel(day, today, locale),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs)
                )
                done.forEach { entry -> HistoryRow(entry) }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: Completion) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            entry.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(Spacing.md))
        Text(
            formatMinutes(entry.estimatedMinutes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun Completion.day(zone: ZoneId): LocalDate = Instant.ofEpochMilli(completedAt).atZone(zone).toLocalDate()

/** "This week", "Last week", then "Oct 19 – 25". */
@Composable
private fun weekTitle(weeksBack: Int, start: LocalDate, locale: Locale): String = when (weeksBack) {
    0 -> stringResource(R.string.stats_week_this)
    1 -> stringResource(R.string.stats_week_last)
    else -> {
        val end = start.plusDays(6)
        val endPattern = if (end.month == start.month) "d" else "MMM d"
        stringResource(
            R.string.stats_week_range,
            start.format(DateTimeFormatter.ofPattern("MMM d", locale)),
            end.format(DateTimeFormatter.ofPattern(endPattern, locale))
        )
    }
}

/** "Today", "Yesterday", or "Mon, Nov 2". */
@Composable
private fun dayLabel(day: LocalDate, today: LocalDate, locale: Locale): String = when (day) {
    today -> stringResource(R.string.repeat_today)
    today.minusDays(1) -> stringResource(R.string.history_yesterday)
    else -> day.format(DateTimeFormatter.ofPattern("EEE, MMM d", locale))
}
