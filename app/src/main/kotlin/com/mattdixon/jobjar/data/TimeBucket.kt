package com.mattdixon.jobjar.data

/** Groups jobs by how long they take, so they can be browsed or drawn "by time available". */
enum class TimeBucket(val displayName: String, val maxMinutes: Int) {
    QUICK("Quick · ≤15 min", 15),
    SHORT("Short · ≤30 min", 30),
    MEDIUM("Medium · ≤1 hr", 60),
    LONG("Long · ≤2 hr", 120),
    EXTENDED("Extended · 2 hr+", Int.MAX_VALUE);

    companion object {
        fun fromMinutes(minutes: Int): TimeBucket =
            entries.firstOrNull { minutes <= it.maxMinutes } ?: EXTENDED
    }
}
