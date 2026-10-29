package com.mattdixon.jobjar.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Monday = bit 0 ... Sunday = bit 6. */
fun weekdayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)

fun weekdaysOf(mask: Int): List<DayOfWeek> = DayOfWeek.entries.filter { mask and weekdayBit(it) != 0 }

const val WEEKDAYS_MASK: Int = 0b0011111

const val WEEKEND_MASK: Int = 0b1100000

const val EVERY_DAY_MASK: Int = 0b1111111

/** [Job.repeatMonthDay] for "the last day of the month". */
const val LAST_DAY_OF_MONTH: Int = -1

enum class RepeatUnit { DAY, WEEK, MONTH, YEAR }

/**
 * What the next due date counts from. COMPLETION: N units after you finish. SCHEDULE: the next
 * date on the rule's own calendar (the 15th, Mar 3...), however early or late you were.
 */
enum class RepeatAnchor { COMPLETION, SCHEDULE }

/**
 * A repeating job's rule, read from the job's columns with the old shapes folded in: weekday
 * jobs are always "every week on these days", and old rows are "every N days from when you
 * finish".
 */
data class RepeatRule(
    val interval: Int,
    val unit: RepeatUnit = RepeatUnit.DAY,
    /** Weekly only. Set = due on these days (see [weekdayBit]) and [anchor] doesn't apply. */
    val weekdays: Int? = null,
    val anchor: RepeatAnchor = RepeatAnchor.COMPLETION,
    /** Monthly/yearly on a schedule: 1..31, or [LAST_DAY_OF_MONTH]. */
    val monthDay: Int? = null,
    /** Yearly on a schedule: 1..12. */
    val month: Int? = null,
    /** Back in the jar (and "coming up") this many days before it's due. */
    val leadDays: Int = 0
) {
    val onWeekdays: Boolean get() = weekdays != null && weekdays and EVERY_DAY_MASK != 0

    /**
     * Same kind of cycle, so an edit can keep the current due date. Days and weeks count the
     * same way, so "every 7 days" to "every week" isn't a change.
     */
    fun sameShapeAs(other: RepeatRule): Boolean {
        fun RepeatUnit.family() = if (this == RepeatUnit.WEEK) RepeatUnit.DAY else this
        if (onWeekdays || other.onWeekdays) return weekdays == other.weekdays
        return unit.family() == other.unit.family() &&
            anchor == other.anchor &&
            monthDay == other.monthDay &&
            month == other.month
    }
}

fun Job.repeatRule(): RepeatRule? {
    val count = recurrenceDays ?: return null
    if (repeatWeekdays != null && repeatWeekdays and EVERY_DAY_MASK != 0) {
        return RepeatRule(interval = 1, unit = RepeatUnit.WEEK, weekdays = repeatWeekdays, leadDays = leadDays)
    }
    return RepeatRule(
        interval = count.coerceAtLeast(1),
        unit = repeatUnit,
        anchor = repeatAnchor,
        monthDay = repeatMonthDay,
        month = repeatMonth,
        leadDays = leadDays
    )
}

/** Stores [rule] in the job's columns, dropping parts that don't apply. Null = doesn't repeat. */
fun Job.withRepeatRule(rule: RepeatRule?): Job = when {
    rule == null -> copy(
        recurrenceDays = null, repeatWeekdays = null, repeatUnit = RepeatUnit.DAY,
        repeatAnchor = RepeatAnchor.COMPLETION, repeatMonthDay = null, repeatMonth = null, leadDays = 0
    )
    rule.onWeekdays -> copy(
        recurrenceDays = 1, repeatWeekdays = rule.weekdays!! and EVERY_DAY_MASK, repeatUnit = RepeatUnit.WEEK,
        repeatAnchor = RepeatAnchor.SCHEDULE, repeatMonthDay = null, repeatMonth = null,
        leadDays = rule.leadDays.coerceAtLeast(0)
    )
    else -> {
        val onCalendar = rule.anchor == RepeatAnchor.SCHEDULE
        val monthly = rule.unit == RepeatUnit.MONTH || rule.unit == RepeatUnit.YEAR
        copy(
            recurrenceDays = rule.interval.coerceAtLeast(1),
            repeatWeekdays = null,
            repeatUnit = rule.unit,
            repeatAnchor = rule.anchor,
            repeatMonthDay = rule.monthDay?.takeIf { onCalendar && monthly && (it == LAST_DAY_OF_MONTH || it in 1..31) },
            repeatMonth = rule.month?.takeIf { onCalendar && rule.unit == RepeatUnit.YEAR && it in 1..12 },
            leadDays = rule.leadDays.coerceAtLeast(0)
        )
    }
}

private fun LocalDate.startMillis(zone: ZoneId): Long = atStartOfDay(zone).toInstant().toEpochMilli()

private fun Long.localDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/** First date after [after] that falls on one of the days in [mask]. */
private fun nextMatchingDate(after: LocalDate, mask: Int): LocalDate {
    require(mask and EVERY_DAY_MASK != 0) { "No weekdays selected" }
    var date = after.plusDays(1)
    while (mask and weekdayBit(date.dayOfWeek) == 0) date = date.plusDays(1)
    return date
}

/** [day] in that month, pulled back to the month's last day (the 31st in April is the 30th). */
private fun YearMonth.clampedDay(day: Int): LocalDate =
    if (day == LAST_DAY_OF_MONTH) atEndOfMonth() else atDay(day.coerceIn(1, lengthOfMonth()))

/**
 * The rule's dates on its own calendar, counted from [from] in steps of the interval: the first
 * one strictly after [after]. Months and years land on [RepeatRule.monthDay] (and
 * [RepeatRule.month]), falling back to [from]'s day and month.
 */
private fun RepeatRule.nextScheduledDate(from: LocalDate, after: LocalDate): LocalDate {
    val step = interval.coerceAtLeast(1).toLong()
    return when (unit) {
        RepeatUnit.DAY, RepeatUnit.WEEK -> {
            val stepDays = if (unit == RepeatUnit.WEEK) step * 7 else step
            val behind = ChronoUnit.DAYS.between(from, after)
            val cycles = if (behind < 0) 0 else behind / stepDays + 1
            from.plusDays(cycles * stepDays)
        }
        RepeatUnit.MONTH, RepeatUnit.YEAR -> {
            val stepMonths = if (unit == RepeatUnit.YEAR) step * 12 else step
            val day = monthDay ?: from.dayOfMonth
            var cycle = YearMonth.from(from)
            if (unit == RepeatUnit.YEAR) cycle = cycle.withMonth(month ?: from.monthValue)
            // Start a cycle back so an early date in [from]'s own cycle isn't skipped.
            cycle = cycle.minusMonths(stepMonths)
            var date = cycle.clampedDay(day)
            while (!date.isAfter(after)) {
                cycle = cycle.plusMonths(stepMonths)
                date = cycle.clampedDay(day)
            }
            date
        }
    }
}

/**
 * Next due date after finishing at [completedAt]. [previousDue] is the due date of the cycle
 * you just finished (null = it was due now).
 *
 * - Set weekdays: start of the next chosen day, even if you were late this time.
 * - From when you finish: N units later at the same time of day (so DST doesn't shift it). A
 *   month after Jan 31 is Feb 28.
 * - On a schedule: the rule's next date after both the old due date and today, at the start of
 *   the day. Finishing a yearly job early doesn't bring next year's forward, and finishing it
 *   late doesn't push it back.
 */
fun RepeatRule.nextDue(previousDue: Long?, completedAt: Long, zone: ZoneId): Long {
    val completed = Instant.ofEpochMilli(completedAt).atZone(zone)
    val completedDate = completed.toLocalDate()
    if (onWeekdays) return nextMatchingDate(completedDate, weekdays!!).startMillis(zone)
    if (anchor == RepeatAnchor.COMPLETION) {
        val n = interval.coerceAtLeast(1).toLong()
        val next = when (unit) {
            RepeatUnit.DAY -> completed.plusDays(n)
            RepeatUnit.WEEK -> completed.plusWeeks(n)
            RepeatUnit.MONTH -> completed.plusMonths(n)
            RepeatUnit.YEAR -> completed.plusYears(n)
        }
        return next.toInstant().toEpochMilli()
    }
    val dueDate = previousDue?.localDate(zone)
    val after = if (dueDate != null && dueDate.isAfter(completedDate)) dueDate else completedDate
    return nextScheduledDate(from = dueDate ?: completedDate, after = after).startMillis(zone)
}

/**
 * Starting due date for a new rule. Null = due now.
 *
 * - Weekdays: now if today's one of the days, otherwise the next one. A "Mondays" job made on a
 *   Wednesday waits until Monday.
 * - On a schedule: the next date on the rule's calendar, today included. Today's is stored as
 *   the start of today (not null) so it can go overdue.
 * - From when you finish: due now.
 */
fun RepeatRule.initialDue(now: Long, zone: ZoneId): Long? {
    val today = now.localDate(zone)
    if (onWeekdays) {
        if (weekdays!! and weekdayBit(today.dayOfWeek) != 0) return null
        return nextMatchingDate(today, weekdays).startMillis(zone)
    }
    if (anchor != RepeatAnchor.SCHEDULE) return null
    return nextScheduledDate(from = today, after = today.minusDays(1)).startMillis(zone)
}

/**
 * When a resting job comes back into the jar: the due time itself, or with a lead window the
 * start of the day that many days before.
 */
fun showFrom(nextDueAt: Long, leadDays: Int, zone: ZoneId): Long =
    if (leadDays <= 0) nextDueAt else nextDueAt.localDate(zone).minusDays(leadDays.toLong()).startMillis(zone)

/** Calendar days from today to the due date: 0 = today, negative = overdue. Null = due now. */
fun Job.daysUntilDue(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Int? {
    val due = nextDueAt ?: return null
    if (recurrenceDays == null) return null
    return ChronoUnit.DAYS.between(now.localDate(zone), due.localDate(zone)).toInt()
}

/** Past its due date (by the calendar: due yesterday is overdue, due earlier today isn't). */
fun Job.isOverdue(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Boolean =
    (daysUntilDue(now, zone) ?: 0) < 0

/** The editor's "show it before" default for a rule: none for frequent things, more the rarer it is. */
fun suggestedLeadDays(rule: RepeatRule): Int {
    if (rule.onWeekdays) return 0
    val days = when (rule.unit) {
        RepeatUnit.DAY -> rule.interval
        RepeatUnit.WEEK -> rule.interval * 7
        RepeatUnit.MONTH -> rule.interval * 30
        RepeatUnit.YEAR -> rule.interval * 365
    }
    return when {
        days < 28 -> 0
        days < 80 -> 3
        days < 300 -> 7
        else -> 30
    }
}

/** The editor's default for "counts from": days and weeks from when you finish, months and years on the calendar. */
fun defaultAnchor(unit: RepeatUnit): RepeatAnchor =
    if (unit == RepeatUnit.MONTH || unit == RepeatUnit.YEAR) RepeatAnchor.SCHEDULE else RepeatAnchor.COMPLETION
