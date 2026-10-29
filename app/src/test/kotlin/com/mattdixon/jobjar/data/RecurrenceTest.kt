package com.mattdixon.jobjar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RecurrenceTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()
    private fun local(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()
    private fun day(millis: Long?) = millis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    private val days7 = RepeatRule(interval = 7)
    private fun weekdays(mask: Int) = RepeatRule(interval = 1, unit = RepeatUnit.WEEK, weekdays = mask)
    private fun monthly(day: Int, every: Int = 1) =
        RepeatRule(interval = every, unit = RepeatUnit.MONTH, anchor = RepeatAnchor.SCHEDULE, monthDay = day)
    private fun yearly(month: Int, day: Int, every: Int = 1) =
        RepeatRule(interval = every, unit = RepeatUnit.YEAR, anchor = RepeatAnchor.SCHEDULE, monthDay = day, month = month)

    // --- From when you finish ---

    @Test
    fun intervalKeepsLocalTimeAcrossClockChange() {
        // UK clocks go back on 25 October 2026.
        val next = days7.nextDue(null, at(2026, 10, 20, 18, 30), zone)
        assertEquals(LocalDateTime.of(2026, 10, 27, 18, 30), local(next))
    }

    @Test
    fun monthsFromFinishingClampToShortMonths() {
        val rule = RepeatRule(interval = 1, unit = RepeatUnit.MONTH)
        assertEquals(LocalDateTime.of(2027, 2, 28, 10, 0), local(rule.nextDue(null, at(2027, 1, 31, 10, 0), zone)))
        val weeks = RepeatRule(interval = 2, unit = RepeatUnit.WEEK)
        assertEquals(LocalDate.of(2026, 11, 3), day(weeks.nextDue(null, at(2026, 10, 20, 9), zone)))
    }

    @Test
    fun finishingEarlyMovesAFromFinishRule() {
        // Due on the 10th, finished on the 5th: the next one counts from the 5th.
        val rule = RepeatRule(interval = 3, unit = RepeatUnit.MONTH)
        assertEquals(LocalDate.of(2026, 8, 5), day(rule.nextDue(at(2026, 5, 10), at(2026, 5, 5, 12), zone)))
    }

    // --- Weekdays ---

    @Test
    fun weekdaysPickTheNextMatchingDayAtMidnight() {
        val wednesday = weekdays(weekdayBit(DayOfWeek.WEDNESDAY))
        // Completed on Wednesday 21 October 2026 -> next Wednesday, not today.
        val next = wednesday.nextDue(null, at(2026, 10, 21, 22, 0), zone)
        assertEquals(LocalDateTime.of(2026, 10, 28, 0, 0), local(next))
    }

    @Test
    fun lateCompletionStillLandsOnTheScheduledDay() {
        val monday = weekdays(weekdayBit(DayOfWeek.MONDAY))
        // Monday's job finished late on Tuesday 20 October -> Monday 26 October.
        val next = monday.nextDue(at(2026, 10, 19), at(2026, 10, 20, 12, 0), zone)
        assertEquals(LocalDateTime.of(2026, 10, 26, 0, 0), local(next))
    }

    @Test
    fun weekdayStartIsNowOnAMatchingDayOtherwiseTheNextOne() {
        val wednesday = at(2026, 10, 21, 9, 0)
        assertNull(weekdays(weekdayBit(DayOfWeek.WEDNESDAY)).initialDue(wednesday, zone))
        assertEquals(LocalDateTime.of(2026, 10, 24, 0, 0), local(weekdays(WEEKEND_MASK).initialDue(wednesday, zone)!!))
        assertNull("from-finish rules start due now", days7.initialDue(wednesday, zone))
    }

    // --- On a schedule ---

    @Test
    fun monthlyOnTheFifteenthStartsOnTheNextFifteenth() {
        assertEquals(LocalDate.of(2026, 10, 15), day(monthly(15).initialDue(at(2026, 10, 3, 9), zone)))
        assertEquals(LocalDate.of(2026, 11, 15), day(monthly(15).initialDue(at(2026, 10, 20, 9), zone)))
        assertEquals(
            "today counts, and is kept as a date so it can go overdue",
            LocalDate.of(2026, 10, 15),
            day(monthly(15).initialDue(at(2026, 10, 15, 9), zone))
        )
    }

    @Test
    fun theThirtyFirstFallsBackInShortMonths() {
        val rule = monthly(31)
        var due = rule.initialDue(at(2027, 1, 31, 8), zone)
        val dates = mutableListOf(day(due))
        repeat(3) {
            due = rule.nextDue(due, due!! + 3_600_000, zone)
            dates += day(due)
        }
        assertEquals(
            listOf(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31), LocalDate.of(2027, 4, 30)),
            dates
        )
    }

    @Test
    fun lastDayOfTheMonth() {
        val rule = monthly(LAST_DAY_OF_MONTH)
        assertEquals(LocalDate.of(2028, 2, 29), day(rule.initialDue(at(2028, 2, 3), zone)))
        assertEquals(LocalDate.of(2028, 3, 31), day(rule.nextDue(at(2028, 2, 29), at(2028, 2, 29, 10), zone)))
    }

    @Test
    fun yearlyIgnoresEarlyAndLateFinishes() {
        val rego = yearly(3, 3)
        val due = at(2027, 3, 3)
        // In the lead window, a week early: next year's date stays put.
        assertEquals(LocalDate.of(2028, 3, 3), day(rego.nextDue(due, at(2027, 2, 24, 10), zone)))
        // A month late: still March 3.
        assertEquals(LocalDate.of(2028, 3, 3), day(rego.nextDue(due, at(2027, 4, 2, 10), zone)))
        // Two years late: the next one after today, not one that's already gone.
        assertEquals(LocalDate.of(2030, 3, 3), day(rego.nextDue(due, at(2029, 5, 1, 10), zone)))
    }

    @Test
    fun everyTwoYearsKeepsItsCycle() {
        val licence = yearly(6, 10, every = 2)
        assertEquals(LocalDate.of(2028, 6, 10), day(licence.nextDue(at(2026, 6, 10), at(2026, 6, 12), zone)))
    }

    @Test
    fun leapDayUsesFeb28InOtherYears() {
        val rule = yearly(2, 29)
        assertEquals(LocalDate.of(2027, 2, 28), day(rule.initialDue(at(2026, 10, 1), zone)))
        assertEquals(LocalDate.of(2028, 2, 29), day(rule.nextDue(at(2027, 2, 28), at(2027, 2, 28, 9), zone)))
    }

    @Test
    fun everyThreeMonthsFromItsDate() {
        val rule = monthly(15, every = 3)
        assertEquals(LocalDate.of(2026, 10, 15), day(rule.initialDue(at(2026, 10, 2), zone)))
        assertEquals(LocalDate.of(2027, 1, 15), day(rule.nextDue(at(2026, 10, 15), at(2026, 10, 16), zone)))
    }

    @Test
    fun daysOnAScheduleKeepTheirRhythm() {
        val rule = RepeatRule(interval = 3, anchor = RepeatAnchor.SCHEDULE)
        // Due on the 1st, done late on the 5th: next on the 7th (1, 4, 7), not the 8th.
        assertEquals(LocalDate.of(2026, 3, 7), day(rule.nextDue(at(2026, 3, 1), at(2026, 3, 5, 18), zone)))
        assertEquals(LocalDate.of(2026, 3, 4), day(rule.nextDue(at(2026, 3, 1), at(2026, 3, 1, 18), zone)))
        assertEquals("starts today", LocalDate.of(2026, 3, 1), day(rule.initialDue(at(2026, 3, 1, 9), zone)))
    }

    // --- Lead window and overdue ---

    @Test
    fun leadWindowStartsAtMidnightThatManyDaysBefore() {
        assertEquals(at(2027, 2, 1), showFrom(at(2027, 3, 3), 30, zone))
        assertEquals("no lead = the due time itself", at(2027, 3, 3, 15), showFrom(at(2027, 3, 3, 15), 0, zone))
    }

    @Test
    fun pendingAndOverdue() {
        val job = Job(title = "Rego", estimatedMinutes = 20)
            .withRepeatRule(yearly(3, 3).copy(leadDays = 30))
            .copy(nextDueAt = at(2027, 3, 3))
        assertFalse(job.isPending(at(2027, 1, 31, 23), zone))
        assertTrue(job.isPending(at(2027, 2, 1, 0), zone))
        assertFalse(job.isOverdue(at(2027, 3, 3, 23), zone))
        assertTrue(job.isOverdue(at(2027, 3, 4, 0), zone))
        assertEquals(-2, job.daysUntilDue(at(2027, 3, 5, 12), zone))
        assertNull("due now isn't a date", job.copy(nextDueAt = null).daysUntilDue(at(2027, 3, 5), zone))
    }

    // --- Columns ---

    @Test
    fun oldRowsReadAsDaysFromFinishing() {
        val old = Job(title = "Plants", estimatedMinutes = 5, recurrenceDays = 7)
        assertEquals(RepeatRule(interval = 7), old.repeatRule())
        assertEquals(weekdays(WEEKEND_MASK), old.copy(repeatWeekdays = WEEKEND_MASK).repeatRule())
        assertNull(Job(title = "Once", estimatedMinutes = 5).repeatRule())
    }

    @Test
    fun storingARuleDropsPartsThatDontApply() {
        val base = Job(title = "Thing", estimatedMinutes = 5)
        val fromFinish = base.withRepeatRule(monthly(15).copy(anchor = RepeatAnchor.COMPLETION))
        assertNull("a from-finish rule has no date", fromFinish.repeatMonthDay)
        val stored = base.withRepeatRule(yearly(3, 3).copy(leadDays = 30))
        assertEquals(yearly(3, 3).copy(leadDays = 30), stored.repeatRule())
        val off = stored.withRepeatRule(null)
        assertNull(off.recurrenceDays)
        assertNull(off.repeatMonth)
        assertEquals(0, off.leadDays)
    }

    @Test
    fun shapeChanges() {
        assertTrue("days to weeks is just a number", days7.sameShapeAs(RepeatRule(interval = 1, unit = RepeatUnit.WEEK)))
        assertTrue(monthly(15).sameShapeAs(monthly(15, every = 3).copy(leadDays = 7)))
        assertFalse(monthly(15).sameShapeAs(monthly(16)))
        assertFalse(days7.sameShapeAs(days7.copy(anchor = RepeatAnchor.SCHEDULE)))
        assertFalse(days7.sameShapeAs(weekdays(WEEKEND_MASK)))
        assertFalse(weekdays(WEEKEND_MASK).sameShapeAs(weekdays(WEEKDAYS_MASK)))
    }

    @Test
    fun suggestedLeads() {
        assertEquals(0, suggestedLeadDays(RepeatRule(interval = 1, unit = RepeatUnit.WEEK)))
        assertEquals(3, suggestedLeadDays(monthly(1)))
        assertEquals(7, suggestedLeadDays(monthly(1, every = 3)))
        assertEquals(30, suggestedLeadDays(yearly(3, 3)))
    }

    @Test
    fun masks() {
        assertEquals(DayOfWeek.entries.take(5), weekdaysOf(WEEKDAYS_MASK))
        assertEquals(listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), weekdaysOf(WEEKEND_MASK))
        assertEquals(EVERY_DAY_MASK, WEEKDAYS_MASK or WEEKEND_MASK)
    }
}
