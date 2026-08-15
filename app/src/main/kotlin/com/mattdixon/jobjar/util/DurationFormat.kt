package com.mattdixon.jobjar.util

fun formatMinutes(minutes: Int): String {
    if (minutes <= 0) return "0 min"
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        hours == 0 -> "$mins min"
        mins == 0 -> "$hours hr"
        else -> "${hours}h ${mins}m"
    }
}
