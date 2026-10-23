package com.mattdixon.jobjar.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mattdixon.jobjar.util.formatTodayLabel

/** Today's date above the screen title. Tap to open the calendar app. */
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
            // Not every phone has a calendar app that handles this.
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW).setData(uri))
            } catch (e: ActivityNotFoundException) {
            }
        }
    )
}
