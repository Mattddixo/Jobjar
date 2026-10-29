package com.mattdixon.jobjar.ui.comingup

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.COMING_UP_RANGES
import com.mattdixon.jobjar.data.ComingUpGroup
import com.mattdixon.jobjar.data.ComingUpItem
import com.mattdixon.jobjar.data.DrawPreferences
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.comingUp
import com.mattdixon.jobjar.data.counts
import com.mattdixon.jobjar.ui.LocalUndoController
import com.mattdixon.jobjar.ui.UndoEvent
import com.mattdixon.jobjar.ui.UndoKind
import com.mattdixon.jobjar.ui.UndoSnackbarEffect
import com.mattdixon.jobjar.ui.components.dueStatusLabel
import com.mattdixon.jobjar.ui.components.plural
import com.mattdixon.jobjar.ui.components.recurrenceLabel
import com.mattdixon.jobjar.ui.theme.AppShapes
import com.mattdixon.jobjar.ui.theme.Spacing
import com.mattdixon.jobjar.ui.theme.appCardBorder
import com.mattdixon.jobjar.ui.theme.appCardColors
import com.mattdixon.jobjar.ui.theme.appTopBarColors

/** Rows shown when the card is opened; the rest are on the See all page. */
private const val CARD_ROWS = 5

/** Finishes the cycle and offers Undo. Runs app-wide so leaving the screen can't cut it off. */
@Composable
fun rememberCompleteCycle(repository: JobRepository): (Job) -> Unit {
    val undo = LocalUndoController.current
    return remember(repository, undo) {
        { job ->
            undo.launchInApp {
                repository.completeCycle(job.id)?.let { undo.offer(UndoEvent(UndoKind.COMPLETED, job.title, it)) }
            }
        }
    }
}

/**
 * The slim line under the jar: "1 overdue · 1 today · 3 this week". Tap to open the first few
 * underneath. Starts closed every time, and isn't shown at all when nothing's due in the range.
 */
@Composable
fun ComingUpCard(
    jobs: List<Job>,
    rangeDays: Int,
    onOpenJob: (Long) -> Unit,
    onComplete: (Job) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val items = comingUp(jobs, rangeDays).filter { it.group != ComingUpGroup.LATER }
    if (items.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val counts = items.counts()

    Card(
        shape = AppShapes.panel,
        colors = appCardColors(),
        border = appCardBorder(),
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        val toggleLabel = stringResource(if (expanded) R.string.cd_coming_up_collapse else R.string.cd_coming_up_expand)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .semantics { contentDescription = toggleLabel }
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Icon(
                Icons.Filled.EventRepeat,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                if (items.size == 1) {
                    stringResource(R.string.coming_up_single, items.single().job.title, dueStatusLabel(items.single().job))
                } else {
                    summaryText(counts.overdue, counts.today, counts.soon, rangeDays)
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (counts.overdue > 0) {
                val overdueLabel = stringResource(R.string.cd_coming_up_overdue)
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                        .semantics { contentDescription = overdueLabel }
                )
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            HorizontalDivider()
            Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
                items.take(CARD_ROWS).forEach { item ->
                    ComingUpRow(
                        item = item,
                        showRule = false,
                        onOpen = { onOpenJob(item.job.id) },
                        onComplete = { onComplete(item.job) }
                    )
                }
                TextButton(
                    onClick = onSeeAll,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = Spacing.sm)
                ) {
                    Text(stringResource(R.string.coming_up_see_all))
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** "1 overdue · 1 today · 3 this week", leaving out whatever's zero. */
@Composable
private fun summaryText(overdue: Int, today: Int, soon: Int, rangeDays: Int): String {
    val parts = buildList {
        if (overdue > 0) add(plural(R.plurals.coming_up_overdue, overdue, overdue))
        if (today > 0) add(plural(R.plurals.coming_up_today, today, today))
        if (soon > 0) {
            add(
                if (rangeDays == 7) {
                    plural(R.plurals.coming_up_this_week, soon, soon)
                } else {
                    plural(R.plurals.coming_up_next_days, soon, soon, rangeDays)
                }
            )
        }
    }
    return parts.joinToString(" · ")
}

/** Tick, name (tap for the job), and when it's due. */
@Composable
private fun ComingUpRow(item: ComingUpItem, showRule: Boolean, onOpen: () -> Unit, onComplete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onComplete) {
            Icon(
                Icons.Filled.RadioButtonUnchecked,
                contentDescription = stringResource(R.string.cd_mark_done),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.job.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (showRule) {
                    Text(
                        recurrenceLabel(item.job),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(Spacing.md))
            Text(
                dueStatusLabel(item.job),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.group == ComingUpGroup.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Every repeating job by when it's due: Overdue, Today, the next N days, then Later (folded
 * away). The range chips here also set the card's range.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComingUpScreen(repository: JobRepository, onOpenJob: (Long) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val jobs by repository.allJobsFlat.collectAsState(initial = null)
    var rangeDays by remember { mutableIntStateOf(DrawPreferences.comingUpRange(context)) }
    var laterOpen by remember { mutableStateOf(false) }
    val complete = rememberCompleteCycle(repository)
    val snackbarHostState = remember { SnackbarHostState() }
    UndoSnackbarEffect(snackbarHostState)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = appTopBarColors(),
                title = { Text(stringResource(R.string.coming_up_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
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
                .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                COMING_UP_RANGES.forEach { days ->
                    FilterChip(
                        selected = rangeDays == days,
                        onClick = {
                            rangeDays = days
                            DrawPreferences.setComingUpRange(context, days)
                        },
                        label = { Text(plural(R.plurals.n_days, days, days)) }
                    )
                }
            }

            val all = jobs ?: return@Column
            val items = comingUp(all, rangeDays)
            if (items.isEmpty()) {
                Text(
                    stringResource(R.string.coming_up_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }
            val byGroup = items.groupBy { it.group }
            if (byGroup.keys.none { it != ComingUpGroup.LATER }) {
                Text(
                    stringResource(R.string.coming_up_none_soon),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ComingUpGroup.entries.forEach { group ->
                val groupItems = byGroup[group].orEmpty()
                if (groupItems.isEmpty()) return@forEach
                val title = when (group) {
                    ComingUpGroup.OVERDUE -> stringResource(R.string.coming_up_group_overdue)
                    ComingUpGroup.TODAY -> stringResource(R.string.coming_up_group_today)
                    ComingUpGroup.SOON -> plural(R.plurals.coming_up_group_soon, rangeDays, rangeDays)
                    ComingUpGroup.LATER -> stringResource(R.string.coming_up_group_later)
                }
                val foldable = group == ComingUpGroup.LATER
                Card(shape = AppShapes.panel, colors = appCardColors(), border = appCardBorder(), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (foldable) Modifier.clickable { laterOpen = !laterOpen } else Modifier)
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "$title · ${groupItems.size}",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (group == ComingUpGroup.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (foldable) {
                            Icon(
                                if (laterOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (!foldable || laterOpen) {
                        groupItems.forEach { item ->
                            ComingUpRow(
                                item = item,
                                showRule = true,
                                onOpen = { onOpenJob(item.job.id) },
                                onComplete = { complete(item.job) }
                            )
                        }
                    }
                }
            }
        }
    }
}
