package com.mattdixon.jobjar.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.EVERY_DAY_MASK
import com.mattdixon.jobjar.data.WEEKDAYS_MASK
import com.mattdixon.jobjar.data.WEEKEND_MASK
import com.mattdixon.jobjar.data.weekdaysOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit

/** "Weekly", "Every 3 days", "Mon, Thu", "Weekdays" etc. */
@Composable
fun recurrenceLabel(recurrenceDays: Int, repeatWeekdays: Int? = null): String {
    if (repeatWeekdays != null && repeatWeekdays != 0) {
        return when (repeatWeekdays and EVERY_DAY_MASK) {
            EVERY_DAY_MASK -> stringResource(R.string.repeat_every_day)
            WEEKDAYS_MASK -> stringResource(R.string.repeat_weekdays)
            WEEKEND_MASK -> stringResource(R.string.repeat_weekends)
            else -> {
                val locale = LocalConfiguration.current.locales[0]
                val days = weekdaysOf(repeatWeekdays)
                if (days.size == 1) {
                    stringResource(R.string.repeat_every_named_day, days.single().getDisplayName(TextStyle.FULL, locale))
                } else {
                    days.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
                }
            }
        }
    }
    return when (recurrenceDays) {
        1 -> stringResource(R.string.repeat_daily)
        7 -> stringResource(R.string.repeat_weekly)
        14 -> stringResource(R.string.repeat_biweekly)
        30 -> stringResource(R.string.repeat_monthly)
        else -> plural(R.plurals.repeat_every_n_days, recurrenceDays, recurrenceDays)
    }
}

/** "Due now", or how many calendar days until it's back. */
@Composable
fun dueStatusLabel(nextDueAt: Long?, now: Long = System.currentTimeMillis()): String {
    if (nextDueAt == null || nextDueAt <= now) return stringResource(R.string.due_now)
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val dueDate: LocalDate = Instant.ofEpochMilli(nextDueAt).atZone(zone).toLocalDate()
    return when (val days = ChronoUnit.DAYS.between(today, dueDate).toInt()) {
        0 -> stringResource(R.string.due_later_today)
        1 -> stringResource(R.string.due_tomorrow)
        else -> plural(R.plurals.due_in_n_days, days, days)
    }
}
