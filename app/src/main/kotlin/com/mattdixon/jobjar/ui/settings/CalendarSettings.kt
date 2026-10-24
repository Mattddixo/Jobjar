package com.mattdixon.jobjar.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.CALENDAR_ALERT_CHOICES
import com.mattdixon.jobjar.data.CalendarOption
import com.mattdixon.jobjar.data.CalendarPreferences
import com.mattdixon.jobjar.data.CalendarScheduler
import com.mattdixon.jobjar.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CALENDAR_PERMISSIONS = arrayOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)

/** Settings > Calendar: which calendar bookings go to, and the alert they get. */
@Composable
fun CalendarSettings() {
    val context = LocalContext.current
    fun permitted() = CALENDAR_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    var hasPermission by remember { mutableStateOf(permitted()) }
    var calendars by remember { mutableStateOf<List<CalendarOption>>(emptyList()) }
    var chosenId by remember { mutableStateOf(CalendarPreferences.calendarId(context)) }
    var alertMinutes by remember { mutableStateOf(CalendarPreferences.alertMinutes(context)) }
    var showCalendarPicker by remember { mutableStateOf(false) }
    var showAlertPicker by remember { mutableStateOf(false) }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            calendars = withContext(Dispatchers.IO) { CalendarScheduler(context).writableCalendars() }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = permitted()
        if (hasPermission) showCalendarPicker = true
    }

    // A calendar that's since been removed or hidden falls back to the main one.
    val chosen = calendars.find { it.id == chosenId }
    SettingRow(
        title = stringResource(R.string.settings_calendar_target),
        value = chosen?.name ?: stringResource(R.string.settings_calendar_automatic),
        onClick = {
            if (hasPermission) showCalendarPicker = true else permissionLauncher.launch(CALENDAR_PERMISSIONS)
        }
    )
    SettingRow(
        title = stringResource(R.string.settings_calendar_alert),
        value = alertLabel(alertMinutes),
        onClick = { showAlertPicker = true }
    )
    Text(
        stringResource(R.string.settings_calendar_explainer),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (showCalendarPicker) {
        ChoiceDialog(
            title = stringResource(R.string.settings_calendar_target),
            choices = listOf<Pair<Long?, Pair<String, String?>>>(null to (stringResource(R.string.settings_calendar_automatic) to null)) +
                calendars.map { it.id to (it.name to it.account.takeIf { account -> account.isNotBlank() && account != it.name }) },
            selected = chosen?.id,
            onSelect = { id ->
                chosenId = id
                CalendarPreferences.setCalendarId(context, id)
                showCalendarPicker = false
            },
            onDismiss = { showCalendarPicker = false }
        )
    }

    if (showAlertPicker) {
        val choices = listOf<Int?>(null) + CALENDAR_ALERT_CHOICES
        ChoiceDialog(
            title = stringResource(R.string.settings_calendar_alert),
            choices = choices.map { it to (alertLabel(it) to null) },
            selected = alertMinutes,
            onSelect = { minutes ->
                alertMinutes = minutes
                CalendarPreferences.setAlertMinutes(context, minutes)
                showAlertPicker = false
            },
            onDismiss = { showAlertPicker = false }
        )
    }
}

@Composable
fun alertLabel(minutes: Int?): String = when (minutes) {
    null -> stringResource(R.string.alert_none)
    0 -> stringResource(R.string.alert_at_start)
    60 -> stringResource(R.string.alert_hour_before)
    1440 -> stringResource(R.string.alert_day_before)
    else -> stringResource(R.string.alert_minutes_before, minutes)
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** A list of radio choices: each is (value, (label, optional second line)). */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    choices: List<Pair<T, Pair<String, String?>>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                choices.forEach { (value, labels) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = value == selected, role = Role.RadioButton) { onSelect(value) }
                            .padding(vertical = Spacing.xs)
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Column(modifier = Modifier.padding(start = Spacing.md)) {
                            Text(labels.first, style = MaterialTheme.typography.bodyLarge)
                            labels.second?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
