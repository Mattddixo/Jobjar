package com.mattdixon.jobjar.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()
    private val now = at(2026, 11, 2, 9)

    private fun repeating(title: String, due: Long?, lead: Int = 0) = Job(title = title, estimatedMinutes = 10)
        .withRepeatRule(RepeatRule(interval = 1, unit = RepeatUnit.YEAR, anchor = RepeatAnchor.SCHEDULE, monthDay = 1, month = 1, leadDays = lead))
        .copy(nextDueAt = due)

    @Test
    fun mentionsOverdueTodayAndWindowsStartingToday() {
        val jobs = listOf(
            repeating("Bins", at(2026, 11, 2)),
            repeating("Filter", at(2026, 10, 30)),
            repeating("Rego", at(2026, 11, 9), lead = 7),
            repeating("Licence", at(2026, 11, 10), lead = 7),
            repeating("Plants", null),
            Job(title = "One-off", estimatedMinutes = 5)
        )
        assertEquals(
            listOf(DueReminder("Filter", -3), DueReminder("Bins", 0), DueReminder("Rego", 7)),
            dueReminders(jobs, now, zone)
        )
    }

    @Test
    fun nextReminderIsTodayIfStillAheadElseTomorrow() {
        assertEquals(at(2026, 11, 2, 18, 30), nextReminderAt(now, 18 * 60 + 30, zone))
        assertEquals(at(2026, 11, 3, 8), nextReminderAt(now, 8 * 60, zone))
    }
}
