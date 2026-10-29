package com.mattdixon.jobjar.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Monday = bit 0 ... Sunday = bit 6. */
fun weekdayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)

fun weekdaysOf(mask: Int): List<DayOfWeek> = DayOfWeek.entries.filter { mask and weekdayBit(it) != 0 }

const val WEEKDAYS_MASK: Int = 0b0011111

const val WEEKEND_MASK: Int = 0b1100000

const val EVERY_DAY_MASK: Int = 0b1111111

/** [Job.repeatMonthDay] for "the last day of the month", and [Job.repeatWeekOfMonth] for "the last <weekday>". */
const val LAST_DAY_OF_MONTH: Int = -1

enum class RepeatUnit { DAY, WEEK, MONTH, YEAR }

/**
 * The two kinds of repeat. COMPLETION ("after I finish it"): N units after the day you tick it
 * off. SCHEDULE ("on set days"): the rule's own dates (Wednesdays, the 15th, Mar 3...), however
 * early or late you were.
 */
enum class RepeatAnchor { COMPLETION, SCHEDULE }

/**
 * A repeating job's rule. Read it with [repeatRule], which folds in the older shapes.
 *
 * On set days, the unit says what the dates are:
 * - DAY: every N days from the first due date.
 * - WEEK: every N weeks on [weekdays].
 * - MONTH: every N months on [monthDay], or on the [weekOfMonth]th of the one day in [weekdays].
 * - YEAR: every N years on [month] [monthDay].
 */
data class RepeatRule(
    val interval: Int,
    val unit: RepeatUnit = RepeatUnit.DAY,
    /** Bitmask, see [weekdayBit]. Weekly: the days. Monthly by weekday: exactly one day. */
    val weekdays: Int? = null,
    val anchor: RepeatAnchor = RepeatAnchor.COMPLETION,
    /** Monthly by date and yearly: 1..31, or [LAST_DAY_OF_MONTH] (monthly only). */
    val monthDay: Int? = null,
    /** Yearly: 1..12. */
    val month: Int? = null,
    /** Monthly by weekday: 1..4, or [LAST_DAY_OF_MONTH] for the last one. */
    val weekOfMonth: Int? = null,
    /** Back in the jar (and "coming up") this many days before it's due. See [maxLeadDays]. */
    val leadDays: Int = 0
) {
    val onSetDays: Boolean get() = anchor == RepeatAnchor.SCHEDULE

    /** Monthly on "the first Monday" rather than a date. */
    val byWeekdayOfMonth: Boolean get() = onSetDays && unit == RepeatUnit.MONTH && weekOfMonth != null

    private val dayMask: Int get() = (weekdays ?: 0) and EVERY_DAY_MASK

    /**
     * Same kind of cycle, so an edit can keep the current due date: same mode, and on set days the
     * same days or dates. The number and the lead window don't count. After finishing, days and
     * weeks count the same way, so "every 7 days" to "every week" isn't a change.
     */
    fun sameShapeAs(other: RepeatRule): Boolean {
        if (anchor != other.anchor) return false
        fun RepeatUnit.family() = if (this == RepeatUnit.WEEK) RepeatUnit.DAY else this
        if (!onSetDays) return unit.family() == other.unit.family()
        return unit == other.unit && dayMask == other.dayMask && monthDay == other.monthDay &&
            month == other.month && weekOfMonth == other.weekOfMonth
    }

    /** The rule's date in [month]: "the first Monday", or the day ([fallbackDay] if none is set). */
    private fun dateInMonth(month: YearMonth, fallbackDay: Int): LocalDate {
        val week = weekOfMonth
        val day = weekdaysOf(dayMask).firstOrNull()
        if (byWeekdayOfMonth && week != null && day != null) {
            return if (week == LAST_DAY_OF_MONTH) {
                month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(day))
            } else {
                month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(week.coerceIn(1, 4), day))
            }
        }
        return month.clampedDay(monthDay ?: fallbackDay)
    }

    /**
     * First date strictly after [after], counting cycles from [from] (a date the rule lands on, or
     * the day to start from).
     */
    internal fun nextScheduledDate(from: LocalDate, after: LocalDate): LocalDate {
        val step = interval.coerceAtLeast(1).toLong()
        return when (unit) {
            RepeatUnit.DAY -> {
                val behind = ChronoUnit.DAYS.between(from, after)
                val cycles = if (behind < 0) 0 else behind / step + 1
                from.plusDays(cycles * step)
            }
            RepeatUnit.WEEK -> {
                val mask = dayMask.takeIf { it != 0 } ?: weekdayBit(from.dayOfWeek)
                val startWeek = from.with(DayOfWeek.MONDAY)
                // Weeks count Monday to Sunday: "every 2 weeks on Mon, Thu" is both days, then a week off.
                generateSequence(if (after.isBefore(from)) from else after.plusDays(1)) { it.plusDays(1) }.first { date ->
                    val weeks = ChronoUnit.WEEKS.between(startWeek, date.with(DayOfWeek.MONDAY))
                    mask and weekdayBit(date.dayOfWeek) != 0 && Math.floorMod(weeks, step) == 0L
                }
            }
            RepeatUnit.MONTH, RepeatUnit.YEAR -> {
                val stepMonths = if (unit == RepeatUnit.YEAR) step * 12 else step
                var cycle = YearMonth.from(from)
                if (unit == RepeatUnit.YEAR) cycle = cycle.withMonth(month ?: from.monthValue)
                // Start a cycle back so an early date in [from]'s own cycle isn't skipped.
                cycle = cycle.minusMonths(stepMonths)
                var date = dateInMonth(cycle, from.dayOfMonth)
                while (!date.isAfter(after)) {
                    cycle = cycle.plusMonths(stepMonths)
                    date = dateInMonth(cycle, from.dayOfMonth)
                }
                date
            }
        }
    }

    /**
     * The first date on or after [date] that the rule lands on, in any cycle. That date then
     * starts the cycle: picking next week's Wednesday for "every 2 weeks on Wed" makes it the
     * other week, and December for "every 3 months on the 15th" makes it Dec, Mar, Jun, Sep.
     */
    fun firstOnOrAfter(date: LocalDate): LocalDate =
        if (unit == RepeatUnit.DAY || !onSetDays) date else copy(interval = 1).nextScheduledDate(from = date, after = date.minusDays(1))

    /**
     * Next due date after finishing at [completedAt]. [previousDue] is the due date of the cycle
     * you just finished (null = it was due now).
     *
     * - After finishing: N units later at the same time of day (so DST doesn't shift it). A
     *   month after Jan 31 is Feb 28.
     * - On set days: the rule's next date after both the old due date and today, at the start of
     *   the day, counting cycles from the old due date. Finishing early doesn't bring the next
     *   one forward, finishing late doesn't push it back, and missed ones don't pile up.
     */
    fun nextDue(previousDue: Long?, completedAt: Long, zone: ZoneId): Long {
        val completed = Instant.ofEpochMilli(completedAt).atZone(zone)
        if (!onSetDays) {
            val n = interval.coerceAtLeast(1).toLong()
            val next = when (unit) {
                RepeatUnit.DAY -> completed.plusDays(n)
                RepeatUnit.WEEK -> completed.plusWeeks(n)
                RepeatUnit.MONTH -> completed.plusMonths(n)
                RepeatUnit.YEAR -> completed.plusYears(n)
            }
            return next.toInstant().toEpochMilli()
        }
        val completedDate = completed.toLocalDate()
        val dueDate = previousDue?.localDate(zone)
        val after = if (dueDate != null && dueDate.isAfter(completedDate)) dueDate else completedDate
        return nextScheduledDate(from = dueDate ?: completedDate, after = after).startMillis(zone)
    }

    /**
     * Starting due date. Null = due now (after finishing). On set days it's the first of the
     * rule's dates from today, kept as a date (even if it's today) so it can go overdue.
     */
    fun initialDue(now: Long, zone: ZoneId): Long? =
        if (onSetDays) firstOnOrAfter(now.localDate(zone)).startMillis(zone) else null

    /**
     * The first due date when you've picked when it starts: on set days the rule's first date from
     * [chosen], after finishing that day itself.
     */
    fun firstDueFrom(chosen: Long, zone: ZoneId): Long = firstOnOrAfter(chosen.localDate(zone)).startMillis(zone)
}

/**
 * Longest sensible "show it early": less than the shortest gap between two due dates, so a job
 * has some time out of the jar. Weekly on one day allows up to 6, Mon + Thu up to 2.
 */
fun maxLeadDays(rule: RepeatRule): Int {
    val n = rule.interval.coerceAtLeast(1)
    val shortestGap = when (rule.unit) {
        RepeatUnit.DAY -> n
        RepeatUnit.WEEK -> {
            val days = weekdaysOf((rule.weekdays ?: 0) and EVERY_DAY_MASK).map { it.value }
            if (!rule.onSetDays || days.size < 2) {
                7 * n
            } else {
                val inWeek = days.zipWithNext { a, b -> b - a }.min()
                minOf(inWeek, days.first() + 7 * n - days.last())
            }
        }
        RepeatUnit.MONTH -> 28 * n
        RepeatUnit.YEAR -> 365 * n
    }
    return (shortestGap - 1).coerceAtLeast(0)
}

/**
 * The job's rule, with the older shapes folded in:
 * - rows from before units: every N days after finishing, or (with weekdays) weekly on those days;
 * - "weekly on set days" saved without days by the first version of this editor: the weekday it
 *   was next due on (or made on);
 * - a lead window longer than the rule allows is cut down to fit.
 */
fun Job.repeatRule(zone: ZoneId = ZoneId.systemDefault()): RepeatRule? {
    val count = recurrenceDays ?: return null
    val mask = repeatWeekdays?.let { it and EVERY_DAY_MASK }?.takeIf { it != 0 }
    val base = when {
        mask != null && (repeatUnit == RepeatUnit.DAY || repeatUnit == RepeatUnit.WEEK) -> RepeatRule(
            // Before units, weekday jobs were saved as "7 days" on those days: weekly.
            interval = if (repeatUnit == RepeatUnit.DAY) 1 else count.coerceAtLeast(1),
            unit = RepeatUnit.WEEK,
            weekdays = mask,
            anchor = RepeatAnchor.SCHEDULE
        )
        repeatUnit == RepeatUnit.WEEK && repeatAnchor == RepeatAnchor.SCHEDULE -> {
            val day = (nextDueAt ?: createdAt).localDate(zone).dayOfWeek
            RepeatRule(interval = count.coerceAtLeast(1), unit = RepeatUnit.WEEK, weekdays = weekdayBit(day), anchor = RepeatAnchor.SCHEDULE)
        }
        else -> RepeatRule(
            interval = count.coerceAtLeast(1),
            unit = repeatUnit,
            anchor = repeatAnchor,
            weekdays = mask?.takeIf { repeatUnit == RepeatUnit.MONTH && repeatWeekOfMonth != null },
            monthDay = repeatMonthDay,
            month = repeatMonth,
            weekOfMonth = repeatWeekOfMonth?.takeIf { mask != null }
        )
    }
    return base.copy(leadDays = leadDays.coerceIn(0, maxLeadDays(base)))
}

/** Stores [rule] in the job's columns, dropping parts that don't apply. Null = doesn't repeat. */
fun Job.withRepeatRule(rule: RepeatRule?): Job {
    val cleared = copy(
        recurrenceDays = null, repeatWeekdays = null, repeatUnit = RepeatUnit.DAY,
        repeatAnchor = RepeatAnchor.COMPLETION, repeatMonthDay = null, repeatMonth = null,
        repeatWeekOfMonth = null, leadDays = 0
    )
    if (rule == null) return cleared
    val mask = (rule.weekdays ?: 0) and EVERY_DAY_MASK
    val base = cleared.copy(
        recurrenceDays = rule.interval.coerceAtLeast(1),
        repeatUnit = rule.unit,
        repeatAnchor = rule.anchor,
        leadDays = rule.leadDays.coerceIn(0, maxLeadDays(rule))
    )
    if (!rule.onSetDays) return base
    return when (rule.unit) {
        RepeatUnit.DAY -> base
        RepeatUnit.WEEK -> base.copy(repeatWeekdays = mask.takeIf { it != 0 })
        RepeatUnit.MONTH -> {
            val week = rule.weekOfMonth?.takeIf { it == LAST_DAY_OF_MONTH || it in 1..4 }
            val day = weekdaysOf(mask).firstOrNull()
            if (week != null && day != null) {
                base.copy(repeatWeekOfMonth = week, repeatWeekdays = weekdayBit(day))
            } else {
                base.copy(repeatMonthDay = rule.monthDay?.takeIf { it == LAST_DAY_OF_MONTH || it in 1..31 })
            }
        }
        RepeatUnit.YEAR -> base.copy(
            repeatMonth = rule.month?.takeIf { it in 1..12 },
            repeatMonthDay = rule.monthDay?.takeIf { it in 1..31 }
        )
    }
}

private fun LocalDate.startMillis(zone: ZoneId): Long = atStartOfDay(zone).toInstant().toEpochMilli()

private fun Long.localDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/** [day] in that month, pulled back to the month's last day (the 31st in April is the 30th). */
private fun YearMonth.clampedDay(day: Int): LocalDate =
    if (day == LAST_DAY_OF_MONTH) atEndOfMonth() else atDay(day.coerceIn(1, lengthOfMonth()))

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

/** The editor's "show it early" default: none for frequent things, more the rarer it is. */
fun suggestedLeadDays(rule: RepeatRule): Int {
    val days = when (rule.unit) {
        RepeatUnit.DAY -> rule.interval
        RepeatUnit.WEEK -> rule.interval * 7
        RepeatUnit.MONTH -> rule.interval * 30
        RepeatUnit.YEAR -> rule.interval * 365
    }
    val suggestion = when {
        days < 28 -> 0
        days < 80 -> 3
        days < 300 -> 7
        else -> 30
    }
    return suggestion.coerceAtMost(maxLeadDays(rule))
}
