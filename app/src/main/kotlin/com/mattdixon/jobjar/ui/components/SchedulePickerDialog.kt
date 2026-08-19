package com.mattdixon.jobjar.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private val CALENDAR_PERMISSIONS = arrayOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)

/**
 * The full "Schedule" flow, shared by every screen that offers it (the Jar tab's drawn card, a
 * Jobs list row, the job detail screen): request calendar permission if it isn't already
 * granted, then a date, then a time, then hand the caller back one epoch-millis instant. The
 * caller is responsible for the actual [com.mattdixon.jobjar.data.JobRepository.scheduleJob]
 * call - this composable only collects the "when," it doesn't know about a specific job.
 *
 * Composed only while the caller wants it shown (same pattern as this app's other on-demand
 * dialogs) - there's no `visible` parameter, the caller just conditionally includes this in its
 * own composition.
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
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
                }
            )
        }
        !hasPermission -> {
            // Fires once, right as this dialog is first composed; nothing to render while the
            // system's own permission prompt is up - the launcher's callback above moves this
            // on to the date step (or to the denied dialog) once the user answers it.
            LaunchedEffect(Unit) { permissionLauncher.launch(CALENDAR_PERMISSIONS) }
        }
        pickedDateMillis == null -> {
            val datePickerState = rememberDatePickerState()
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
            // A sensible default rather than "now": most scheduling happens in advance, so
            // defaulting to the current minute would usually just get typed over anyway.
            val timePickerState = rememberTimePickerState(initialHour = 9, initialMinute = 0)
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.dialog_pick_time_title)) },
                text = { TimePicker(state = timePickerState) },
                confirmButton = {
                    TextButton(onClick = {
                        // DatePicker reports its selection as UTC midnight for the chosen
                        // calendar date (a deliberate Material3 design choice, to keep date
                        // selection itself independent of the device's local zone) - re-anchor
                        // that date to the device's real zone once combined with the picked
                        // time, rather than the millis value drifting a day off in some zones.
                        val date = Instant.ofEpochMilli(pickedDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                        val dateTime = LocalDateTime.of(date, LocalTime.of(timePickerState.hour, timePickerState.minute))
                        onConfirm(dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    }) { Text(stringResource(R.string.action_schedule)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }
    }
}
