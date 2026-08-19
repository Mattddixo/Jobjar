package com.mattdixon.jobjar.ui.components

import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mattdixon.jobjar.util.formatTodayLabel

/**
 * A small "Aug 19"-style label meant to sit directly above a main tab's title, inside that same
 * title slot (see how DrawScreen/JobListScreen/StatsScreen stack it in a Column with the screen
 * title below) rather than off in the TopAppBar's separate navigationIcon area - putting the two
 * side by side on one line read as crowded, since navigationIcon reserves a fixed-width slot the
 * title then has to squeeze around regardless of how compact this label actually is. Tapping it
 * jumps straight into the device's own Calendar app on today - the same "launch the real Calendar
 * app rather than build one in this app" boundary already drawn for scheduling itself (see
 * SchedulePickerDialog's doc comment), just the way in rather than the way out.
 */
@Composable
fun TodayDateButton() {
    val context = LocalContext.current
    Text(
        text = formatTodayLabel(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable {
            val uri = CalendarContract.CONTENT_URI.buildUpon()
                .appendPath("time")
                .appendPath(System.currentTimeMillis().toString())
                .build()
            context.startActivity(Intent(Intent.ACTION_VIEW).setData(uri))
        }
    )
}
