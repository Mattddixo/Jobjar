package com.mattdixon.jobjar.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SCHEDULED_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d, h:mm a")
private val TODAY_LABEL_FORMATTER = DateTimeFormatter.ofPattern("MMM d")

/** "Nov 4, 2:00 PM", in local time. */
fun formatScheduledDateTime(millis: Long): String =
    SCHEDULED_DATE_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** "Nov 4" for today. */
fun formatTodayLabel(): String = TODAY_LABEL_FORMATTER.format(LocalDate.now())
