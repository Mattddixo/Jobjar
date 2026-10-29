package com.mattdixon.jobjar.data

import java.time.ZoneId

/** Ranges offered for Coming up, in days. */
val COMING_UP_RANGES = listOf(7, 14, 30)
const val DEFAULT_COMING_UP_RANGE = 14

enum class ComingUpGroup { OVERDUE, TODAY, SOON, LATER }

data class ComingUpItem(
    val job: Job,
    /** 0 = today (or due now), negative = overdue. */
    val daysUntil: Int,
    val group: ComingUpGroup
)

/**
 * Every repeating job, most urgent first: overdue, today, the next [rangeDays] days, then later.
 * A job that's simply due now counts as today.
 */
fun comingUp(
    jobs: List<Job>,
    rangeDays: Int,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault()
): List<ComingUpItem> =
    jobs.filter { it.parentId == null && it.recurrenceDays != null }
        .map { job ->
            val days = job.daysUntilDue(now, zone) ?: 0
            val group = when {
                days < 0 -> ComingUpGroup.OVERDUE
                days == 0 -> ComingUpGroup.TODAY
                days <= rangeDays -> ComingUpGroup.SOON
                else -> ComingUpGroup.LATER
            }
            ComingUpItem(job, days, group)
        }
        .sortedWith(compareBy<ComingUpItem> { it.daysUntil }.thenBy { it.job.nextDueAt ?: 0L }.thenBy { it.job.title.lowercase() })

data class ComingUpCounts(val overdue: Int, val today: Int, val soon: Int) {
    val total: Int get() = overdue + today + soon
}

fun List<ComingUpItem>.counts(): ComingUpCounts = ComingUpCounts(
    overdue = count { it.group == ComingUpGroup.OVERDUE },
    today = count { it.group == ComingUpGroup.TODAY },
    soon = count { it.group == ComingUpGroup.SOON }
)
