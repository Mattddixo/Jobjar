package com.mattdixon.jobjar.ui.addedit

import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.RepeatAnchor
import com.mattdixon.jobjar.data.RepeatRule
import com.mattdixon.jobjar.data.RepeatUnit
import com.mattdixon.jobjar.data.weekdayBit
import com.mattdixon.jobjar.data.withRepeatRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RepeatFormTest {
    private val zone = ZoneId.of("Europe/London")
    private val thursday = LocalDate.of(2026, 10, 29)
    private fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 0, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun presetsStartOnSetDaysFromToday() {
        val weekly = RepeatForm().withPreset(RepeatPreset.WEEKLY, thursday)
        assertEquals(
            RepeatRule(interval = 1, unit = RepeatUnit.WEEK, anchor = RepeatAnchor.SCHEDULE, weekdays = weekdayBit(DayOfWeek.THURSDAY)),
            weekly.rule
        )
        val yearly = RepeatForm().withPreset(RepeatPreset.YEARLY, thursday)
        assertEquals(RepeatRule(interval = 1, unit = RepeatUnit.YEAR, anchor = RepeatAnchor.SCHEDULE, monthDay = 29, month = 10, leadDays = 30), yearly.rule)
    }

    @Test
    fun aPickedDayStaysWhenYouChangeThePreset() {
        val wednesday = RepeatForm().withPreset(RepeatPreset.WEEKLY, thursday)
            .togglingWeekday(DayOfWeek.WEDNESDAY)
            .togglingWeekday(DayOfWeek.THURSDAY)
        val fortnightly = wednesday.withPreset(RepeatPreset.FORTNIGHTLY, thursday)
        assertEquals(weekdayBit(DayOfWeek.WEDNESDAY), fortnightly.rule?.weekdays)
        assertEquals(2, fortnightly.rule?.interval)
        assertEquals("the last day can't be unticked", fortnightly, fortnightly.togglingWeekday(DayOfWeek.WEDNESDAY))
    }

    @Test
    fun afterFinishingDropsTheDays() {
        val form = RepeatForm().withPreset(RepeatPreset.MONTHLY, thursday).withMode(RepeatAnchor.COMPLETION, thursday)
        assertEquals(RepeatRule(interval = 1, unit = RepeatUnit.MONTH, anchor = RepeatAnchor.COMPLETION, leadDays = 3), form.rule)
        assertNull("no Next line after finishing", form.nextDue(at(2026, 10, 29), zone))
    }

    @Test
    fun leadOnlyOffersWhatFits() {
        val weekly = RepeatForm().withPreset(RepeatPreset.WEEKLY, thursday)
        assertEquals(listOf(0, 1, 3), weekly.leadChoices)
        // Chosen for a monthly job, then switched to weekly: cut down to fit.
        val shrunk = RepeatForm().withPreset(RepeatPreset.MONTHLY, thursday).withLead(14).withUnit(RepeatUnit.WEEK, thursday)
        assertEquals(6, shrunk.leadDays)
        assertEquals(listOf(0), RepeatForm().withPreset(RepeatPreset.DAILY, thursday).leadChoices)
    }

    @Test
    fun nextShowsThePickedStartOrTheKeptDueDate() {
        val bins = RepeatForm().withPreset(RepeatPreset.FORTNIGHTLY, thursday)
        assertEquals(at(2026, 10, 29), bins.nextDue(at(2026, 10, 29), zone))
        assertEquals("picked Monday: that week's Thursday", at(2026, 11, 5), bins.withFirstDue(at(2026, 11, 2)).nextDue(at(2026, 10, 29), zone))

        val saved = Job(title = "Bins", estimatedMinutes = 5)
            .withRepeatRule(bins.rule)
            .copy(nextDueAt = at(2026, 11, 12))
        val loaded = RepeatForm.from(saved, zone)
        assertEquals("an edit that keeps the days keeps the date", at(2026, 11, 12), loaded.withInterval(3).nextDue(at(2026, 10, 29), zone))
        assertEquals(loaded.originalRule, RepeatForm.decodeRule(RepeatForm.encodeRule(loaded.originalRule)))
    }

    @Test
    fun monthlyByWeekday() {
        val form = RepeatForm().withPreset(RepeatPreset.MONTHLY, thursday)
            .withMonthByWeekday(true, thursday)
        assertEquals("starts from today's: the last Thursday", -1, form.weekOfMonth)
        val firstMonday = form
            .withWeekOfMonth(1)
            .withMonthWeekday(DayOfWeek.MONDAY)
        assertEquals(
            RepeatRule(
                interval = 1, unit = RepeatUnit.MONTH, anchor = RepeatAnchor.SCHEDULE, weekdays = weekdayBit(DayOfWeek.MONDAY),
                weekOfMonth = 1, leadDays = 3
            ),
            firstMonday.rule
        )
        assertEquals(at(2026, 11, 2), firstMonday.nextDue(at(2026, 10, 29), zone))
    }
}
