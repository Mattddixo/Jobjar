package com.mattdixon.jobjar.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.EVERY_DAY_MASK
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.LAST_DAY_OF_MONTH
import com.mattdixon.jobjar.data.RepeatRule
import com.mattdixon.jobjar.data.RepeatUnit
import com.mattdixon.jobjar.data.WEEKDAYS_MASK
import com.mattdixon.jobjar.data.WEEKEND_MASK
import com.mattdixon.jobjar.data.daysUntilDue
import com.mattdixon.jobjar.data.repeatRule
import com.mattdixon.jobjar.data.weekdaysOf
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** 1st, 2nd, 3rd, 4th... 11th, 12th, 13th... 21st. */
fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}

/** "Mar 3". */
fun monthDayLabel(month: Int, day: Int, locale: Locale): String =
    "${Month.of(month).getDisplayName(TextStyle.SHORT, locale)} $day"

/** "first", "second"... "last", as in "the first Monday". */
@Composable
fun weekOfMonthLabel(week: Int): String = stringResource(
    when (week) {
        1 -> R.string.repeat_week_first
        2 -> R.string.repeat_week_second
        3 -> R.string.repeat_week_third
        4 -> R.string.repeat_week_fourth
        else -> R.string.repeat_week_last
    }
)

/**
 * "Weekly", "Every 3 days", "Mon, Thu", "Every 2 weeks on Wednesday", "Monthly on the 15th",
 * "Monthly on the first Monday", "Yearly on Mar 3" etc.
 */
@Composable
fun recurrenceLabel(rule: RepeatRule): String {
    val locale = LocalConfiguration.current.locales[0]
    val weekdays = rule.weekdays
    val onSchedule = rule.onSetDays
    val n = rule.interval
    if (onSchedule && rule.unit == RepeatUnit.WEEK && weekdays != null && n > 1) {
        val days = weekdaysOf(weekdays)
        val names = if (days.size == 1) {
            days.single().getDisplayName(TextStyle.FULL, locale)
        } else {
            days.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
        }
        return plural(R.plurals.repeat_weeks_on_days, n, n, names)
    }
    if (onSchedule && rule.unit == RepeatUnit.WEEK && weekdays != null) {
        return when (weekdays and EVERY_DAY_MASK) {
            EVERY_DAY_MASK -> stringResource(R.string.repeat_every_day)
            WEEKDAYS_MASK -> stringResource(R.string.repeat_weekdays)
            WEEKEND_MASK -> stringResource(R.string.repeat_weekends)
            else -> {
                val days = weekdaysOf(weekdays)
                if (days.size == 1) {
                    stringResource(R.string.repeat_every_named_day, days.single().getDisplayName(TextStyle.FULL, locale))
                } else {
                    days.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
                }
            }
        }
    }
    return when (rule.unit) {
        RepeatUnit.DAY -> when (n) {
            1 -> stringResource(R.string.repeat_daily)
            7 -> stringResource(R.string.repeat_weekly)
            14 -> stringResource(R.string.repeat_biweekly)
            else -> plural(R.plurals.repeat_every_n_days, n, n)
        }
        RepeatUnit.WEEK -> plural(R.plurals.repeat_every_n_weeks, n, n)
        RepeatUnit.MONTH -> {
            val day = rule.monthDay
            val week = rule.weekOfMonth
            val weekday = weekdays?.let { weekdaysOf(it).firstOrNull() }
            when {
                rule.byWeekdayOfMonth && week != null && weekday != null -> plural(
                    R.plurals.repeat_months_on_weekday, n, n, weekOfMonthLabel(week), weekday.getDisplayName(TextStyle.FULL, locale)
                )
                !onSchedule || day == null -> plural(R.plurals.repeat_every_n_months, n, n)
                day == LAST_DAY_OF_MONTH -> plural(R.plurals.repeat_months_on_last_day, n, n)
                else -> plural(R.plurals.repeat_months_on_day, n, n, ordinal(day))
            }
        }
        RepeatUnit.YEAR -> {
            val day = rule.monthDay
            val month = rule.month
            if (!onSchedule || day == null || month == null) {
                plural(R.plurals.repeat_every_n_years, n, n)
            } else {
                val dayOfMonth = if (day == LAST_DAY_OF_MONTH) Month.of(month).maxLength() else day
                plural(R.plurals.repeat_years_on_date, n, n, monthDayLabel(month, dayOfMonth, locale))
            }
        }
    }
}

/** The job's rule as a label. Blank if it doesn't repeat. */
@Composable
fun recurrenceLabel(job: Job): String = job.repeatRule()?.let { recurrenceLabel(it) } ?: ""

/**
 * Where a repeating job is in its cycle.
 *
 * - "Overdue 2 days" once the due date has passed.
 * - Jobs that count from when you finish, with no lead window: "Due now", "Back later today",
 *   "Back tomorrow", "Back in 5 days", as before.
 * - Everything else: "Due today", "Due tomorrow", "Due Fri" within a week, then "Due Mar 3".
 */
@Composable
fun dueStatusLabel(job: Job, now: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val due = job.nextDueAt ?: return stringResource(R.string.due_now)
    val rule = job.repeatRule() ?: return stringResource(R.string.due_now)
    val days = job.daysUntilDue(now, zone) ?: 0
    if (days < 0) return plural(R.plurals.overdue_n_days, -days, -days)
    val countsFromFinish = !rule.onSetDays && rule.leadDays == 0
    if (countsFromFinish) {
        if (due <= now) return stringResource(R.string.due_now)
        return when (days) {
            0 -> stringResource(R.string.due_later_today)
            1 -> stringResource(R.string.due_tomorrow)
            else -> plural(R.plurals.due_in_n_days, days, days)
        }
    }
    return when (days) {
        0 -> stringResource(R.string.due_today)
        1 -> stringResource(R.string.due_tomorrow_plain)
        else -> stringResource(R.string.due_on, dueDateLabel(due, days, zone))
    }
}

/** "Fri" within the week, "Mar 3" this year, "Mar 3, 2028" after that. */
@Composable
private fun dueDateLabel(due: Long, daysAway: Int, zone: ZoneId): String {
    val locale = LocalConfiguration.current.locales[0]
    val date = Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
    return when {
        daysAway < 7 -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        date.year == LocalDate.now(zone).year -> monthDayLabel(date.monthValue, date.dayOfMonth, locale)
        else -> date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", locale))
    }
}
