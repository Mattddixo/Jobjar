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
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import com.mattdixon.jobjar.ui.theme.Spacing
import java.time.DayOfWeek
import java.time.Month
import java.time.format.TextStyle

/** Choices for "Show it early", in days. */
private val LEAD_CHOICES = listOf(0, 1, 3, 7, 14, 30, 60)

/**
 * The repeat settings, shown once Repeat is on: quick picks, then the rule spelled out as
 * "Every [2] [weeks]" with whatever that rule needs underneath. Everything has a sensible
 * default, so most people only ever tap a preset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeatEditor(state: AddEditFormState, viewModel: AddEditJobViewModel) {
    val rule = state.rule ?: return
    val interval = state.repeatInterval ?: return

    Text(
        if (interval > 0) recurrenceLabel(rule) else stringResource(R.string.repeat_every),
        style = MaterialTheme.typography.headlineSmall
    )

    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        items(RepeatPreset.entries) { preset ->
            FilterChip(
                selected = preset.matches(state),
                onClick = { viewModel.applyPreset(preset) },
                label = { Text(presetLabel(preset)) }
            )
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(stringResource(R.string.repeat_every), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = if (interval == 0) "" else interval.toString(),
            onValueChange = { text -> viewModel.setRepeatInterval(text.filter { it.isDigit() }.take(3).toIntOrNull() ?: 0) },
            label = { Text(stringResource(R.string.cd_repeat_count)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.width(96.dp)
        )
        DropdownField(
            label = unitLabel(state.repeatUnit, interval),
            options = RepeatUnit.entries,
            optionLabel = { unitLabel(it, interval) },
            onSelect = viewModel::setRepeatUnit
        )
    }

    if (state.canPickWeekdays) {
        Text(stringResource(R.string.repeat_on_days_label), style = MaterialTheme.typography.labelLarge)
        WeekdayPicker(mask = state.repeatWeekdays ?: 0, onToggle = viewModel::toggleWeekday)
    }

    if (rule.onWeekdays) {
        Explainer(stringResource(R.string.repeat_weekdays_explainer))
    } else {
        val needsDate = state.repeatAnchor == RepeatAnchor.SCHEDULE &&
            (state.repeatUnit == RepeatUnit.MONTH || state.repeatUnit == RepeatUnit.YEAR)
        if (needsDate) DatePickers(state, viewModel)

        Text(stringResource(R.string.repeat_counts_from), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val options = listOf(
                RepeatAnchor.SCHEDULE to R.string.repeat_anchor_schedule,
                RepeatAnchor.COMPLETION to R.string.repeat_anchor_completion
            )
            options.forEachIndexed { index, (anchor, label) ->
                SegmentedButton(
                    selected = state.repeatAnchor == anchor,
                    onClick = { viewModel.setRepeatAnchor(anchor) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size)
                ) { Text(stringResource(label)) }
            }
        }
        Explainer(
            stringResource(
                if (state.repeatAnchor == RepeatAnchor.SCHEDULE) R.string.repeat_schedule_explainer else R.string.repeat_interval_explainer
            )
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(stringResource(R.string.repeat_lead_label), style = MaterialTheme.typography.labelLarge)
        DropdownField(
            label = leadLabel(state.leadDays),
            options = (LEAD_CHOICES + state.leadDays).distinct().sorted(),
            optionLabel = { leadLabel(it) },
            onSelect = viewModel::setLeadDays
        )
    }
    if (state.leadDays > 0) Explainer(stringResource(R.string.repeat_lead_explainer))
}

/** "On the [15th]" for months, "On [Mar] [3]" for years. */
@Composable
private fun DatePickers(state: AddEditFormState, viewModel: AddEditJobViewModel) {
    val locale = LocalConfiguration.current.locales[0]
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(stringResource(R.string.repeat_on_label), style = MaterialTheme.typography.bodyLarge)
        if (state.repeatUnit == RepeatUnit.YEAR) {
            val month = state.repeatMonth ?: 1
            DropdownField(
                label = Month.of(month).getDisplayName(TextStyle.FULL, locale),
                options = (1..12).toList(),
                optionLabel = { Month.of(it).getDisplayName(TextStyle.FULL, locale) },
                onSelect = viewModel::setRepeatMonth
            )
            DropdownField(
                label = (state.repeatMonthDay ?: 1).toString(),
                options = (1..Month.of(month).maxLength()).toList(),
                optionLabel = { it.toString() },
                onSelect = viewModel::setRepeatMonthDay
            )
        } else {
            DropdownField(
                label = monthDayLabel(state.repeatMonthDay ?: 1),
                options = (1..31).toList() + LAST_DAY_OF_MONTH,
                optionLabel = { monthDayLabel(it) },
                onSelect = viewModel::setRepeatMonthDay
            )
        }
    }
}

@Composable
private fun monthDayLabel(day: Int): String =
    if (day == LAST_DAY_OF_MONTH) stringResource(R.string.repeat_last_day) else stringResource(R.string.repeat_on_the_day, ordinal(day))

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

/** Monday first. Any combination. */
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
