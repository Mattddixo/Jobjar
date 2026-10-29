package com.mattdixon.jobjar.ui.addedit

import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.LAST_DAY_OF_MONTH
import com.mattdixon.jobjar.data.RepeatAnchor
import com.mattdixon.jobjar.data.RepeatRule
import com.mattdixon.jobjar.data.RepeatUnit
import com.mattdixon.jobjar.data.weekdayBit
import com.mattdixon.jobjar.data.withRepeatRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RepeatFormTest {
    private val zone = ZoneId.of("Europe/London")
    private val thursday = LocalDate.of(2026, 10, 29)
    private val now = at(2026, 10, 29, 9)
    private fun at(y: Int, m: Int, d: Int, h: Int = 0) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private fun fresh() = RepeatForm(startDay = thursday.toEpochDay())

    @Test
    fun presetsComeFromTheStartDate() {
        assertEquals(
            RepeatRule(interval = 1, unit = RepeatUnit.WEEK, anchor = RepeatAnchor.SCHEDULE, weekdays = weekdayBit(DayOfWeek.THURSDAY)),
            fresh().withPreset(RepeatPreset.WEEKLY).rule
        )
        assertEquals(
            RepeatRule(interval = 1, unit = RepeatUnit.YEAR, anchor = RepeatAnchor.SCHEDULE, monthDay = 29, month = 10, leadDays = 30),
            fresh().withPreset(RepeatPreset.YEARLY).rule
        )
        assertEquals(29, fresh().withPreset(RepeatPreset.MONTHLY).rule?.monthDay)
    }

    @Test
    fun pickingAStartDateSetsTheDates() {
        val nov15 = LocalDate.of(2026, 11, 15)
        // Yearly: the date is the rule.
        val yearly = fresh().withPreset(RepeatPreset.YEARLY).withStartDate(nov15)
        assertEquals(11, yearly.rule?.month)
        assertEquals(15, yearly.rule?.monthDay)
        assertEquals(at(2026, 11, 15), yearly.firstDueToSave(now, zone))
        // Monthly: the 15th from now on.
        val monthly = fresh().withPreset(RepeatPreset.MONTHLY).withStartDate(nov15)
        assertEquals(15, monthly.rule?.monthDay)
        assertEquals(at(2026, 11, 15), monthly.nextDue(now, zone))
        // Weekly on one day: moves to the picked date's weekday (Nov 15 is a Sunday).
        val weekly = fresh().withPreset(RepeatPreset.FORTNIGHTLY).withStartDate(nov15)
        assertEquals(weekdayBit(DayOfWeek.SUNDAY), weekly.rule?.weekdays)
        assertEquals(at(2026, 11, 15), weekly.firstDueToSave(now, zone))
    }

    @Test
    fun weeklyOnSeveralDaysSnapsToTheNextOne() {
        val monThu = fresh().withPreset(RepeatPreset.WEEKLY).togglingWeekday(DayOfWeek.MONDAY)
        // Picked a Saturday: Mon + Thu stay, and it starts the Monday after.
        val picked = monThu.withStartDate(LocalDate.of(2026, 10, 31))
        assertEquals(weekdayBit(DayOfWeek.MONDAY) or weekdayBit(DayOfWeek.THURSDAY), picked.rule?.weekdays)
        assertEquals(at(2026, 11, 2), picked.firstDueToSave(now, zone))
        val thursdayOnly = picked.togglingWeekday(DayOfWeek.MONDAY)
        assertEquals("the last day can't be unticked", thursdayOnly, thursdayOnly.togglingWeekday(DayOfWeek.THURSDAY))
    }

    @Test
    fun monthlyChoicesComeFromTheDate() {
        // Oct 29 2026 is the fifth (so last) Thursday, not the last day.
        assertEquals(
            listOf(MonthOption(monthDay = 29), MonthOption(weekOfMonth = LAST_DAY_OF_MONTH, weekday = DayOfWeek.THURSDAY)),
            MonthOption.choicesFor(thursday)
        )
        // Nov 30 2026: the 30th, the last day, the fifth Monday = last Monday.
        assertEquals(
            listOf(
                MonthOption(monthDay = 30), MonthOption(monthDay = LAST_DAY_OF_MONTH),
                MonthOption(weekOfMonth = LAST_DAY_OF_MONTH, weekday = DayOfWeek.MONDAY)
            ),
            MonthOption.choicesFor(LocalDate.of(2026, 11, 30))
        )
        // Nov 26: the 26th, the fourth Thursday, and also the last one.
        assertEquals(
            listOf(
                MonthOption(monthDay = 26), MonthOption(weekOfMonth = 4, weekday = DayOfWeek.THURSDAY),
                MonthOption(weekOfMonth = LAST_DAY_OF_MONTH, weekday = DayOfWeek.THURSDAY)
            ),
            MonthOption.choicesFor(LocalDate.of(2026, 11, 26))
        )
    }

    @Test
    fun aMonthlyChoiceKeepsItsKindWhenTheDateChanges() {
        val lastThursday = fresh().withPreset(RepeatPreset.MONTHLY)
            .withMonthOption(MonthOption(weekOfMonth = LAST_DAY_OF_MONTH, weekday = DayOfWeek.THURSDAY))
        // A Tuesday that isn't the last one: becomes "the first Tuesday".
        val moved = lastThursday.withStartDate(LocalDate.of(2026, 12, 1))
        assertEquals(MonthOption(weekOfMonth = 1, weekday = DayOfWeek.TUESDAY), moved.monthOption)
        val firstMonday = moved.withStartDate(LocalDate.of(2026, 12, 7))
        assertEquals(MonthOption(weekOfMonth = 1, weekday = DayOfWeek.MONDAY), firstMonday.monthOption)
    }

    @Test
    fun afterFinishingStartsNowUnlessYouPickADay() {
        val form = fresh().withPreset(RepeatPreset.MONTHLY).withMode(RepeatAnchor.COMPLETION)
        assertEquals(RepeatRule(interval = 1, unit = RepeatUnit.MONTH, anchor = RepeatAnchor.COMPLETION, leadDays = 3), form.rule)
        assertNull("due now", form.firstDueToSave(now, zone))
        assertNull(form.nextDue(now, zone))
        assertEquals(at(2026, 11, 3), form.withStartDate(LocalDate.of(2026, 11, 3)).firstDueToSave(now, zone))
    }

    @Test
    fun leadOnlyOffersWhatFits() {
        assertEquals(listOf(0, 1, 3), fresh().withPreset(RepeatPreset.WEEKLY).leadChoices)
        val shrunk = fresh().withPreset(RepeatPreset.MONTHLY).withLead(14).withUnit(RepeatUnit.WEEK)
        assertEquals(6, shrunk.leadDays)
        assertEquals(listOf(0), fresh().withPreset(RepeatPreset.DAILY).leadChoices)
    }

    @Test
    fun editingKeepsTheDueDateUnlessTheRuleOrDateChanges() {
        val bins = fresh().withPreset(RepeatPreset.FORTNIGHTLY)
        val saved = Job(title = "Bins", estimatedMinutes = 5).withRepeatRule(bins.rule).copy(nextDueAt = at(2026, 11, 12))
        val loaded = RepeatForm.from(saved, zone, thursday)
        assertEquals("starts from the next due date", LocalDate.of(2026, 11, 12), loaded.startDate)
        assertNull("nothing changed: keep it", loaded.firstDueToSave(now, zone))
        assertEquals(at(2026, 11, 12), loaded.withInterval(3).nextDue(now, zone))
        assertEquals(loaded.originalRule, RepeatForm.decodeRule(RepeatForm.encodeRule(loaded.originalRule)))
        // A monthly job on the 31st, next due Feb 28: its own choice is kept and offered first.
        val the31st = Job(title = "Rent", estimatedMinutes = 5)
            .withRepeatRule(RepeatRule(interval = 1, unit = RepeatUnit.MONTH, anchor = RepeatAnchor.SCHEDULE, monthDay = 31))
            .copy(nextDueAt = at(2027, 2, 28))
        val rent = RepeatForm.from(the31st, zone, thursday)
        assertEquals(MonthOption(monthDay = 31), rent.monthOption)
        assertEquals(MonthOption(monthDay = 31), rent.monthChoices.first())
        assertTrue(MonthOption(monthDay = LAST_DAY_OF_MONTH) in rent.monthChoices)
        assertNull(rent.firstDueToSave(now, zone))
    }
}
