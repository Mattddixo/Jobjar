package com.mattdixon.jobjar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class RecurrenceTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()
    private fun local(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

    @Test
    fun intervalKeepsLocalTimeAcrossClockChange() {
        // UK clocks go back on 25 October 2026.
        val next = nextDueAfterCompletion(7, null, at(2026, 10, 20, 18, 30), zone)
        assertEquals(LocalDateTime.of(2026, 10, 27, 18, 30), local(next))
    }

    @Test
    fun weekdaysPickTheNextMatchingDayAtMidnight() {
        val wednesday = weekdayBit(DayOfWeek.WEDNESDAY)
        // Completed on Wednesday 21 October 2026 -> next Wednesday, not today.
        val next = nextDueAfterCompletion(7, wednesday, at(2026, 10, 21, 22, 0), zone)
        assertEquals(LocalDateTime.of(2026, 10, 28, 0, 0), local(next))
    }

    @Test
    fun lateCompletionStillLandsOnTheScheduledDay() {
        val monday = weekdayBit(DayOfWeek.MONDAY)
        // Monday's job finished late on Tuesday 20 October -> Monday 26 October.
        val next = nextDueAfterCompletion(7, monday, at(2026, 10, 20, 12, 0), zone)
        assertEquals(LocalDateTime.of(2026, 10, 26, 0, 0), local(next))
    }

    @Test
    fun initialNextDueIsNowOnAMatchingDayOtherwiseTheNextOne() {
        val wednesday = at(2026, 10, 21, 9, 0)
        assertNull(initialNextDue(weekdayBit(DayOfWeek.WEDNESDAY), wednesday, zone))
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 0, 0),
            local(initialNextDue(WEEKEND_MASK, wednesday, zone)!!)
        )
        assertNull("interval schedules start due now", initialNextDue(null, wednesday, zone))
    }

    @Test
    fun masks() {
        assertEquals(DayOfWeek.entries.take(5), weekdaysOf(WEEKDAYS_MASK))
        assertEquals(listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), weekdaysOf(WEEKEND_MASK))
        assertEquals(EVERY_DAY_MASK, WEEKDAYS_MASK or WEEKEND_MASK)
    }
}
