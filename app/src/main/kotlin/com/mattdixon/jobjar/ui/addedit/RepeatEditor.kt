package com.mattdixon.jobjar.ui.addedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.LAST_DAY_OF_MONTH
import com.mattdixon.jobjar.data.RepeatAnchor
import com.mattdixon.jobjar.data.RepeatUnit
import com.mattdixon.jobjar.data.weekdayBit
import com.mattdixon.jobjar.ui.components.ordinal
import com.mattdixon.jobjar.ui.components.plural
import com.mattdixon.jobjar.ui.components.recurrenceLabel
import com.mattdixon.jobjar.ui.components.weekOfMonthLabel
import com.mattdixon.jobjar.ui.theme.Spacing
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** Weeks of the month offered for "on the first Monday". */
private val WEEKS_OF_MONTH = listOf(1, 2, 3, 4, LAST_DAY_OF_MONTH)

/**
 * The repeat settings, shown once Repeat is on:
 *
 * 1. Quick picks, which fill everything in.
 * 2. On set days / After I finish it.
 * 3. "Every [2] [weeks]", then whatever set days need: the days, the date, or the weekday of
 *    the month, and when it's next due (tap Change to pick when it starts).
 * 4. Show it early, limited to what fits the repeat.
 */
@Composable
fun RepeatEditor(form: RepeatForm, onChange: ((RepeatForm) -> RepeatForm) -> Unit) {
    val rule = form.rule ?: return
    val interval = form.interval ?: return

    Text(
        if (interval > 0) recurrenceLabel(rule) else stringResource(R.string.repeat_every),
        style = MaterialTheme.typography.headlineSmall
    )

    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        items(RepeatPreset.entries) { preset ->
            FilterChip(
                selected = preset.matches(form),
                onClick = { onChange { it.withPreset(preset) } },
                label = { Text(presetLabel(preset)) }
            )
        }
    }

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val options = listOf(
            RepeatAnchor.SCHEDULE to R.string.repeat_mode_set_days,
            RepeatAnchor.COMPLETION to R.string.repeat_mode_after_finish
        )
        options.forEachIndexed { index, (anchor, label) ->
            SegmentedButton(
                selected = form.anchor == anchor,
                onClick = { onChange { it.withMode(anchor) } },
                shape = SegmentedButtonDefaults.itemShape(index, options.size)
            ) { Text(stringResource(label)) }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(stringResource(R.string.repeat_every), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = if (interval == 0) "" else interval.toString(),
            onValueChange = { text ->
                val value = text.filter { it.isDigit() }.take(3).toIntOrNull() ?: 0
                onChange { it.withInterval(value) }
            },
            label = { Text(stringResource(R.string.cd_repeat_count)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.width(96.dp)
        )
        DropdownField(
            label = unitLabel(form.unit, interval),
            options = RepeatUnit.entries,
            optionLabel = { unitLabel(it, interval) },
            onSelect = { unit -> onChange { it.withUnit(unit) } }
        )
    }

    if (form.onSetDays) {
        when (form.unit) {
            RepeatUnit.DAY -> Unit
            RepeatUnit.WEEK -> {
                Text(stringResource(R.string.repeat_on_label), style = MaterialTheme.typography.labelLarge)
                WeekdayPicker(mask = form.weekdays, onToggle = { day -> onChange { it.togglingWeekday(day) } })
            }
            RepeatUnit.MONTH -> MonthlyPickers(form, onChange)
            RepeatUnit.YEAR -> YearlyPickers(form, onChange)
        }
        NextDueRow(form, onChange)
        Explainer(stringResource(R.string.repeat_set_days_explainer))
    } else {
        Explainer(stringResource(R.string.repeat_after_finish_explainer))
    }

    // Daily on set days has nowhere to show it early, so the row would only ever say "On the day".
    if (form.leadChoices.size > 1) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.repeat_lead_label), style = MaterialTheme.typography.labelLarge)
            DropdownField(
                label = leadLabel(form.leadDays),
                options = form.leadChoices,
                optionLabel = { leadLabel(it) },
                onSelect = { days -> onChange { it.withLead(days) } }
            )
        }
        if (form.leadDays > 0) Explainer(stringResource(R.string.repeat_lead_explainer))
    }
}

/** "Same date / Same weekday", then "On the [15th]" or "On the [first] [Monday]". */
@Composable
private fun MonthlyPickers(form: RepeatForm, onChange: ((RepeatForm) -> RepeatForm) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val options = listOf(false to R.string.repeat_month_by_date, true to R.string.repeat_month_by_weekday)
        options.forEachIndexed { index, (byWeekday, label) ->
            SegmentedButton(
                selected = form.monthByWeekday == byWeekday,
                onClick = { onChange { it.withMonthByWeekday(byWeekday) } },
                shape = SegmentedButtonDefaults.itemShape(index, options.size)
            ) { Text(stringResource(label)) }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(stringResource(R.string.repeat_on_the), style = MaterialTheme.typography.bodyLarge)
        if (form.monthByWeekday) {
            DropdownField(
                label = weekOfMonthLabel(form.weekOfMonth),
                options = WEEKS_OF_MONTH,
                optionLabel = { weekOfMonthLabel(it) },
                onSelect = { week -> onChange { it.withWeekOfMonth(week) } }
            )
            DropdownField(
                label = DayOfWeek.of(form.monthWeekday).getDisplayName(TextStyle.FULL, locale),
                options = DayOfWeek.entries,
                optionLabel = { it.getDisplayName(TextStyle.FULL, locale) },
                onSelect = { day -> onChange { it.withMonthWeekday(day) } }
            )
        } else {
            DropdownField(
                label = monthDayLabel(form.monthDay ?: 1),
                options = (1..31).toList() + LAST_DAY_OF_MONTH,
                optionLabel = { monthDayLabel(it) },
                onSelect = { day -> onChange { it.withMonthDay(day) } }
            )
        }
    }
}

/** "On [March] [3]". */
@Composable
private fun YearlyPickers(form: RepeatForm, onChange: ((RepeatForm) -> RepeatForm) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val month = form.month ?: 1
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(stringResource(R.string.repeat_on_label), style = MaterialTheme.typography.bodyLarge)
        DropdownField(
            label = Month.of(month).getDisplayName(TextStyle.FULL, locale),
            options = (1..12).toList(),
            optionLabel = { Month.of(it).getDisplayName(TextStyle.FULL, locale) },
            onSelect = { m -> onChange { it.withMonth(m) } }
        )
        DropdownField(
            label = (form.monthDay ?: 1).toString(),
            options = (1..Month.of(month).maxLength()).toList(),
            optionLabel = { it.toString() },
            onSelect = { day -> onChange { it.withMonthDay(day) } }
        )
    }
}

/** "Next: Wed, Nov 4  [Change]". Change picks when it starts; the rule snaps it to its own dates. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NextDueRow(form: RepeatForm, onChange: ((RepeatForm) -> RepeatForm) -> Unit) {
    val zone = ZoneId.systemDefault()
    val next = form.nextDue(zone = zone)
    var picking by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            if (next == null) stringResource(R.string.repeat_next_now) else stringResource(R.string.repeat_next, nextDueText(next, zone)),
            style = MaterialTheme.typography.bodyLarge
        )
        TextButton(onClick = { picking = true }) { Text(stringResource(R.string.repeat_change)) }
    }
    if (picking) {
        // The picker works in UTC midnights; convert both ways so it can't land a day out.
        val today = LocalDate.now(zone)
        val todayUtc = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val shown = next?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }?.takeIf { !it.isBefore(today) } ?: today
        val state = rememberDatePickerState(
            initialSelectedDateMillis = shown.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc
                override fun isSelectableYear(year: Int) = year >= today.year
            }
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { utc ->
                        val date = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange { it.withFirstDue(date.atStartOfDay(zone).toInstant().toEpochMilli()) }
                    }
                    picking = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) } }
        ) {
            DatePicker(state = state)
        }
    }
}

/** "Today", "Tomorrow", "Wed, Nov 4", or with the year if it isn't this year. */
@Composable
private fun nextDueText(millis: Long, zone: ZoneId): String {
    val locale = LocalConfiguration.current.locales[0]
    val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (date) {
        today -> stringResource(R.string.repeat_today)
        today.plusDays(1) -> stringResource(R.string.repeat_tomorrow)
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "EEE, MMM d" else "EEE, MMM d, yyyy", locale))
    }
}

@Composable
private fun monthDayLabel(day: Int): String =
    if (day == LAST_DAY_OF_MONTH) stringResource(R.string.repeat_last_day) else ordinal(day)

@Composable
private fun presetLabel(preset: RepeatPreset): String = when (preset) {
    RepeatPreset.DAILY -> stringResource(R.string.repeat_daily)
    RepeatPreset.WEEKLY -> stringResource(R.string.repeat_weekly)
    RepeatPreset.FORTNIGHTLY -> stringResource(R.string.repeat_biweekly)
    RepeatPreset.MONTHLY -> stringResource(R.string.repeat_monthly)
    RepeatPreset.QUARTERLY -> stringResource(R.string.repeat_every_3_months)
    RepeatPreset.YEARLY -> stringResource(R.string.repeat_yearly)
}

@Composable
private fun unitLabel(unit: RepeatUnit, count: Int): String {
    // "Every [0] days" while retyping reads better than "day".
    val n = if (count == 1) 1 else 2
    return when (unit) {
        RepeatUnit.DAY -> plural(R.plurals.repeat_unit_day, n)
        RepeatUnit.WEEK -> plural(R.plurals.repeat_unit_week, n)
        RepeatUnit.MONTH -> plural(R.plurals.repeat_unit_month, n)
        RepeatUnit.YEAR -> plural(R.plurals.repeat_unit_year, n)
    }
}

@Composable
private fun leadLabel(days: Int): String =
    if (days == 0) stringResource(R.string.repeat_lead_none) else plural(R.plurals.repeat_lead_days, days, days)

@Composable
private fun Explainer(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A button showing the current choice; tap for the list. */
@Composable
private fun <T> DropdownField(
    label: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(label)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** Monday first. At least one stays ticked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayPicker(mask: Int, onToggle: (DayOfWeek) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        DayOfWeek.entries.forEach { day ->
            FilterChip(
                selected = mask and weekdayBit(day) != 0,
                onClick = { onToggle(day) },
                label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) }
            )
        }
    }
}
