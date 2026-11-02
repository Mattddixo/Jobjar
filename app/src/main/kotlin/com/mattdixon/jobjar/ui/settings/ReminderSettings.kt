package com.mattdixon.jobjar.ui.settings

import android.Manifest
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.ReminderPreferences
import com.mattdixon.jobjar.reminders.ReminderScheduler
import com.mattdixon.jobjar.ui.theme.Spacing
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Settings > Reminders: due repeating jobs, a daily nudge, and when they go out. Turning one on
 * asks for notification permission (Android 13+) if it isn't there yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSettings() {
    val context = LocalContext.current
    var due by remember { mutableStateOf(ReminderPreferences.dueReminders(context)) }
    var nudge by remember { mutableStateOf(ReminderPreferences.dailyNudge(context)) }
    var minutes by remember { mutableIntStateOf(ReminderPreferences.timeMinutes(context)) }
    var allowed by remember { mutableStateOf(ReminderScheduler.canNotify(context)) }
    var pickingTime by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
    }
    fun changed() {
        ReminderScheduler.schedule(context)
        if (!allowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        SwitchRow(
            title = stringResource(R.string.settings_reminders_due),
            explainer = stringResource(R.string.settings_reminders_due_explainer),
            checked = due,
            onChange = {
                due = it
                ReminderPreferences.setDueReminders(context, it)
                changed()
            }
        )
        SwitchRow(
            title = stringResource(R.string.settings_reminders_nudge),
            explainer = stringResource(R.string.settings_reminders_nudge_explainer),
            checked = nudge,
            onChange = {
                nudge = it
                ReminderPreferences.setDailyNudge(context, it)
                changed()
            }
        )
        if (due || nudge) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.settings_reminders_time), style = MaterialTheme.typography.bodyLarge)
                val time = LocalTime.of(minutes / 60, minutes % 60)
                val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
                OutlinedButton(onClick = { pickingTime = true }) { Text(time.format(DateTimeFormatter.ofPattern(pattern))) }
            }
            if (!allowed) {
                Text(
                    stringResource(R.string.settings_reminders_blocked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (pickingTime) {
        val state = rememberTimePickerState(
            initialHour = minutes / 60,
            initialMinute = minutes % 60,
            is24Hour = DateFormat.is24HourFormat(context)
        )
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    minutes = state.hour * 60 + state.minute
                    ReminderPreferences.setTimeMinutes(context, minutes)
                    ReminderScheduler.schedule(context)
                    pickingTime = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun SwitchRow(title: String, explainer: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(explainer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
