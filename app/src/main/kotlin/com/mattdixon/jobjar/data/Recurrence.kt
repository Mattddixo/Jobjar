package com.mattdixon.jobjar.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Monday = bit 0 ... Sunday = bit 6. */
fun weekdayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)

fun weekdaysOf(mask: Int): List<DayOfWeek> = DayOfWeek.entries.filter { mask and weekdayBit(it) != 0 }

const val WEEKDAYS_MASK: Int = 0b0011111

const val WEEKEND_MASK: Int = 0b1100000

const val EVERY_DAY_MASK: Int = 0b1111111

private fun LocalDate.startMillis(zone: ZoneId): Long = atStartOfDay(zone).toInstant().toEpochMilli()

/** First date after [after] that falls on one of the days in [mask]. */
private fun nextMatchingDate(after: LocalDate, mask: Int): LocalDate {
    require(mask and EVERY_DAY_MASK != 0) { "No weekdays selected" }
    var date = after.plusDays(1)
    while (mask and weekdayBit(date.dayOfWeek) == 0) date = date.plusDays(1)
    return date
}

/**
 * Next due date after finishing at [completedAt].
 *
 * Every N days: N calendar days later, same time of day (so DST doesn't shift it).
 * Set weekdays: start of the next chosen day, even if you were late this time.
 */
fun nextDueAfterCompletion(recurrenceDays: Int, repeatWeekdays: Int?, completedAt: Long, zone: ZoneId): Long {
    val completed = Instant.ofEpochMilli(completedAt).atZone(zone)
    return if (repeatWeekdays != null && repeatWeekdays != 0) {
        nextMatchingDate(completed.toLocalDate(), repeatWeekdays).startMillis(zone)
    } else {
        completed.plusDays(recurrenceDays.toLong()).toInstant().toEpochMilli()
    }
}

/**
 * Starting due date for a weekday schedule: now if today's one of the days, otherwise the next
 * one. A "Mondays" job made on a Wednesday waits until Monday.
 */
fun initialNextDue(repeatWeekdays: Int?, now: Long, zone: ZoneId): Long? {
    if (repeatWeekdays == null || repeatWeekdays == 0) return null
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    if (repeatWeekdays and weekdayBit(today.dayOfWeek) != 0) return null
    return nextMatchingDate(today, repeatWeekdays).startMillis(zone)
}
