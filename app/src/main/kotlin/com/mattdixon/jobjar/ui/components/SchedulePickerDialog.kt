package com.mattdixon.jobjar.ui.components

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.mattdixon.jobjar.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private val CALENDAR_PERMISSIONS = arrayOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)

/**
 * Schedule flow: calendar permission if needed, then date, then time. Hands back the chosen
 * time; the caller does the actual scheduling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (dateTimeMillis: Long) -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(CALENDAR_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    var permissionDenied by remember { mutableStateOf(false) }
    var pickedDateMillis by remember { mutableStateOf<Long?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) hasPermission = true else permissionDenied = true
    }

    when {
        permissionDenied -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.dialog_calendar_permission_title)) },
                text = { Text(stringResource(R.string.dialog_calendar_permission_body)) },
                confirmButton = {
                    // After two refusals Android stops asking, so settings is the only way.
                    TextButton(onClick = {
                        onDismiss()
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            )
                        } catch (e: ActivityNotFoundException) {
                        }
                    }) { Text(stringResource(R.string.action_open_settings)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_not_now)) }
                }
            )
        }
        !hasPermission -> {
            // Ask for permission straight away. The callback moves us on.
            LaunchedEffect(Unit) { permissionLauncher.launch(CALENDAR_PERMISSIONS) }
        }
        pickedDateMillis == null -> {
            // No past dates. The picker works in UTC midnights, so compare against today's.
            val todayUtcMillis = remember {
                LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }
            val datePickerState = rememberDatePickerState(
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtcMillis
                    override fun isSelectableYear(year: Int) = year >= LocalDate.now().year
                }
            )
            DatePickerDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(onClick = {
                        val selected = datePickerState.selectedDateMillis
                        if (selected != null) pickedDateMillis = selected else onDismiss()
                    }) { Text(stringResource(R.string.action_next)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }
        else -> {
            // DatePicker returns UTC midnight for the picked date. Convert to a local date first or it
            // can land a day out depending on the time zone.
            val date = Instant.ofEpochMilli(pickedDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
            // 9am for another day, since most things get scheduled ahead. For today, the next whole
            // hour, so the default is never already gone.
            val initialTime = remember(date) {
                if (date == LocalDate.now()) LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
                else LocalTime.of(9, 0)
            }
            val timePickerState = rememberTimePickerState(initialHour = initialTime.hour, initialMinute = initialTime.minute)
            val startMillis = LocalDateTime.of(date, LocalTime.of(timePickerState.hour, timePickerState.minute))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val inPast = startMillis <= System.currentTimeMillis()
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.dialog_pick_time_title)) },
                text = {
                    Column {
                        TimePicker(state = timePickerState)
                        if (inPast) {
                            Text(
                                stringResource(R.string.schedule_time_passed),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onConfirm(startMillis) }, enabled = !inPast) {
                        Text(stringResource(R.string.action_schedule))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }
    }
}

/**
 * Says so when a booking was saved but its calendar event couldn't be written (no calendar on the
 * phone, or access turned off), instead of failing silently. Pass the result of scheduleJob.
 */
@Composable
fun rememberCalendarResultNotice(): (wroteEvent: Boolean) -> Unit {
    val context = LocalContext.current.applicationContext
    val message = stringResource(R.string.schedule_no_event)
    return remember(context, message) {
        { wroteEvent -> if (!wroteEvent) Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }
}
