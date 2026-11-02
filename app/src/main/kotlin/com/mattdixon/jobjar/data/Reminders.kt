package com.mattdixon.jobjar.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A repeating job worth a mention in today's reminder. */
data class DueReminder(
    val title: String,
    /** 0 = due today, negative = overdue, positive = coming up (its show-early window starts today). */
    val daysUntil: Int
)

/**
 * What today's due reminder mentions: repeating jobs that are overdue or due today, and ones
 * whose "show it early" window starts today (so "Car registration is due Fri" arrives as it
 * goes back in the jar). Jobs that are simply due now with no date (after finishing, never
 * done) aren't mentioned every day. Most urgent first.
 */
fun dueReminders(jobs: List<Job>, now: Long, zone: ZoneId): List<DueReminder> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return jobs.filter { it.parentId == null && it.recurrenceDays != null }
        .mapNotNull { job ->
            val due = job.nextDueAt ?: return@mapNotNull null
            val days = job.daysUntilDue(now, zone) ?: return@mapNotNull null
            val lead = job.repeatRule(zone)?.leadDays ?: 0
            val windowStarts = Instant.ofEpochMilli(due).atZone(zone).toLocalDate().minusDays(lead.toLong())
            when {
                days <= 0 -> DueReminder(job.title, days)
                lead > 0 && ChronoUnit.DAYS.between(today, windowStarts) == 0L -> DueReminder(job.title, days)
                else -> null
            }
        }
        .sortedWith(compareBy<DueReminder> { it.daysUntil }.thenBy { it.title.lowercase() })
}

/** Today at the reminder time ([minutes] after midnight) if that's still ahead, otherwise tomorrow. */
fun nextReminderAt(now: Long, minutes: Int, zone: ZoneId): Long {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun at(day: LocalDate) = day.atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()
    return at(today).takeIf { it > now } ?: at(today.plusDays(1))
}
