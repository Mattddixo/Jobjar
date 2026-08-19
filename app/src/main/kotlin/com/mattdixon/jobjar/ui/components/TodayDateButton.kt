package com.mattdixon.jobjar.ui.components

import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.mattdixon.jobjar.ui.theme.Spacing
import com.mattdixon.jobjar.util.formatTodayLabel

/**
 * Sits in the navigationIcon slot of each main tab's TopAppBar (Jar / Jobs / Stats): today's
 * date, tapped to jump straight into the device's own Calendar app on today. This is the same
 * "launch the real Calendar app rather than build one in this app" boundary already drawn for
 * scheduling itself (see SchedulePickerDialog's doc comment) - a way in from every tab, not just
 * a way out from scheduling one job.
 */
@Composable
fun TodayDateButton() {
    val context = LocalContext.current
    // Tighter than TextButton's own default content padding - this sits in a top bar's
    // navigationIcon slot, which sizes itself around a compact icon button rather than a
    // regular button, so the roomier default padding would crowd the title next to it.
    TextButton(
        onClick = {
            val uri = CalendarContract.CONTENT_URI.buildUpon()
                .appendPath("time")
                .appendPath(System.currentTimeMillis().toString())
                .build()
            context.startActivity(Intent(Intent.ACTION_VIEW).setData(uri))
        },
        contentPadding = PaddingValues(horizontal = Spacing.md)
    ) {
        Text(formatTodayLabel(), style = MaterialTheme.typography.labelLarge)
    }
}
