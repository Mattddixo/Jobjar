package com.mattdixon.jobjar.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SCHEDULED_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d, h:mm a")
private val TODAY_LABEL_FORMATTER = DateTimeFormatter.ofPattern("MMM d")

/** "Nov 4, 2:00 PM"-style label for a scheduled job's date/time, converted from the stored UTC
 * epoch millis into the device's current local zone. Compact enough for a badge, specific
 * enough for the job detail screen - used in both rather than keeping two formats in sync. */
fun formatScheduledDateTime(millis: Long): String =
    SCHEDULED_DATE_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** "Nov 4"-style label for today's date, in the device's current local zone - shown in each main
 * tab's top bar as the entry point into the device's own Calendar app (see TodayDateButton). */
fun formatTodayLabel(): String = TODAY_LABEL_FORMATTER.format(LocalDate.now())
