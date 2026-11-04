package com.mattdixon.jobjar.data

import java.time.Instant
import java.time.ZoneId

/** One job's spending in the chosen period. */
data class SpendingItem(
    val jobId: Long,
    val title: String,
    /** Set for a subtask, so it's clear where it belongs. */
    val parentTitle: String?,
    val category: String,
    val cents: Long,
    /** How many times its cost was spent: 1 for a one-off, every finish for a repeating job. */
    val times: Int,
    /** The latest finish in the period, if known. */
    val lastDoneAt: Long?
)

/** A category's spending, its jobs biggest first. */
data class SpendingGroup(val category: String, val cents: Long, val items: List<SpendingItem>)

data class SpendingBreakdown(
    val totalCents: Long,
    /** Biggest first. */
    val groups: List<SpendingGroup>,
    /** Years with dated spending, newest first. */
    val years: List<Int>,
    /** Spending with no date (repeating finishes from before history was kept). All time only. */
    val undatedCents: Long
)

/**
 * Where the money went, all time or in one [year]. Counts the same as the Spent total on Stats:
 * a finished job or subtask's cost once, a repeating job's cost every time it's finished, using
 * each job's current cost. Dates come from when a one-off was finished and from the history for
 * repeating jobs; finishes from before the history started count in All time only.
 */
fun spendingBreakdown(
    jobs: List<Job>,
    completions: List<Completion>,
    year: Int? = null,
    zone: ZoneId = ZoneId.systemDefault()
): SpendingBreakdown {
    val byId = jobs.associateBy { it.id }
    val repeatingParentIds = jobs.filter { it.parentId == null && it.recurrenceDays != null }.map { it.id }.toSet()
    val finishesByJob = completions.groupBy({ it.jobId }, { it.completedAt })

    // Each time a job's cost was spent, with its date when known.
    val spends: List<Pair<Job, Long?>> = jobs.flatMap { job ->
        val cost = job.costCents ?: 0
        when {
            cost <= 0 -> emptyList()
            job.recurrenceDays != null -> {
                val dated = finishesByJob[job.id].orEmpty().sortedDescending().take(job.completionCount)
                dated.map { job to it } + List(job.completionCount - dated.size) { job to null }
            }
            // Subtasks of repeating parents only exist in old data and never counted.
            job.isDone && job.parentId !in repeatingParentIds -> listOf(job to job.completedAt)
            else -> emptyList()
        }
    }

    fun yearOf(at: Long) = Instant.ofEpochMilli(at).atZone(zone).year
    val inPeriod = if (year == null) spends else spends.filter { (_, at) -> at != null && yearOf(at) == year }

    val groups = inPeriod.groupBy { (job, _) -> job.id }
        .map { (_, list) ->
            val job = list.first().first
            SpendingItem(
                jobId = job.id,
                title = job.title,
                parentTitle = job.parentId?.let { byId[it]?.title },
                category = job.category.ifBlank { UNCATEGORIZED },
                cents = (job.costCents ?: 0) * list.size,
                times = list.size,
                lastDoneAt = list.mapNotNull { it.second }.maxOrNull()
            )
        }
        .groupBy { it.category }
        .map { (category, items) ->
            SpendingGroup(
                category = category,
                cents = items.sumOf { it.cents },
                items = items.sortedWith(compareByDescending<SpendingItem> { it.cents }.thenBy { it.title.lowercase() })
            )
        }
        .sortedWith(compareByDescending<SpendingGroup> { it.cents }.thenBy { it.category.lowercase() })

    return SpendingBreakdown(
        totalCents = groups.sumOf { it.cents },
        groups = groups,
        years = spends.mapNotNull { (_, at) -> at?.let(::yearOf) }.distinct().sortedDescending(),
        undatedCents = spends.filter { it.second == null }.sumOf { it.first.costCents ?: 0 }
    )
}

/** What Stats calls jobs with no category. */
const val UNCATEGORIZED = "Uncategorized"
