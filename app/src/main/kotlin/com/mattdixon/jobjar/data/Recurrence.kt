package com.mattdixon.jobjar.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** [Job.repeatWeekdays] bit for [day]: Monday is bit 0, Sunday bit 6 (i.e. `DayOfWeek.value - 1`). */
fun weekdayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)

/** Every day of the week [mask] includes, Monday first. */
fun weekdaysOf(mask: Int): List<DayOfWeek> = DayOfWeek.entries.filter { mask and weekdayBit(it) != 0 }

/** Monday to Friday, for the "Weekdays" shortcut. */
const val WEEKDAYS_MASK: Int = 0b0011111

/** Saturday and Sunday. */
const val WEEKEND_MASK: Int = 0b1100000

/** All seven days. */
const val EVERY_DAY_MASK: Int = 0b1111111

private fun LocalDate.startMillis(zone: ZoneId): Long = atStartOfDay(zone).toInstant().toEpochMilli()

/** The first date strictly after [after] whose day of week is in [mask]. [mask] must be non-zero. */
private fun nextMatchingDate(after: LocalDate, mask: Int): LocalDate {
    require(mask and EVERY_DAY_MASK != 0) { "No weekdays selected" }
    var date = after.plusDays(1)
    while (mask and weekdayBit(date.dayOfWeek) == 0) date = date.plusDays(1)
    return date
}

/**
 * When a repeating job comes due again after being completed at [completedAt].
 *
 * - Interval schedule ([repeatWeekdays] null): [recurrenceDays] calendar days later, at the same
 *   local time of day. Done in local time rather than by adding fixed 24-hour blocks, so a daily
 *   job doesn't drift an hour every time the clocks change.
 * - Weekday schedule: the start of the next matching day after the day it was completed - so
 *   finishing Monday's job (even late on Tuesday) brings it back the following Monday, not the
 *   next day.
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
 * [Job.nextDueAt] for a weekday schedule that was just created or changed: null (due now) if
 * today is one of its days, otherwise the start of the next day that is - so a "Mondays" job
 * created on a Wednesday waits for Monday instead of landing in the jar straight away. Interval
 * schedules always start due now (null).
 */
fun initialNextDue(repeatWeekdays: Int?, now: Long, zone: ZoneId): Long? {
    if (repeatWeekdays == null || repeatWeekdays == 0) return null
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    if (repeatWeekdays and weekdayBit(today.dayOfWeek) != 0) return null
    return nextMatchingDate(today, repeatWeekdays).startMillis(zone)
}
