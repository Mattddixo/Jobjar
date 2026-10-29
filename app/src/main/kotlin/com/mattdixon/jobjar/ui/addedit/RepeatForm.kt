package com.mattdixon.jobjar.ui.addedit

import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.LAST_DAY_OF_MONTH
import com.mattdixon.jobjar.data.RepeatAnchor
import com.mattdixon.jobjar.data.RepeatRule
import com.mattdixon.jobjar.data.RepeatUnit
import com.mattdixon.jobjar.data.maxLeadDays
import com.mattdixon.jobjar.data.repeatRule
import com.mattdixon.jobjar.data.suggestedLeadDays
import com.mattdixon.jobjar.data.weekdayBit
import com.mattdixon.jobjar.data.weekdaysOf
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Choices for "Show it early", in days. Only the ones shorter than the repeat are offered. */
private val LEAD_CHOICES = listOf(0, 1, 3, 7, 14, 30, 60)

/** The quick picks at the top. All of them start on set days, from the start date. */
enum class RepeatPreset(val interval: Int, val unit: RepeatUnit) {
    DAILY(1, RepeatUnit.DAY),
    WEEKLY(1, RepeatUnit.WEEK),
    FORTNIGHTLY(2, RepeatUnit.WEEK),
    MONTHLY(1, RepeatUnit.MONTH),
    QUARTERLY(3, RepeatUnit.MONTH),
    YEARLY(1, RepeatUnit.YEAR);

    /** "Every 14 days" counts as every 2 weeks, so older jobs still light up their chip. */
    fun matches(form: RepeatForm): Boolean {
        val interval = form.interval ?: return false
        if (form.unit == unit) return interval == this.interval
        return unit == RepeatUnit.WEEK && form.unit == RepeatUnit.DAY && interval == this.interval * 7
    }
}

/**
 * Which day of the month a monthly job lands on: a date ("the 29th", or the last day), or a
 * weekday ("the fifth Thursday" is always "the last Thursday").
 */
data class MonthOption(val monthDay: Int? = null, val weekOfMonth: Int? = null, val weekday: DayOfWeek? = null) {
    val byWeekday: Boolean get() = weekOfMonth != null

    companion object {
        /**
         * What [date] could mean: always its date, the last day if it is one, its weekday of the
         * month (first to fourth), and "the last <weekday>" if it is the last one.
         */
        fun choicesFor(date: LocalDate): List<MonthOption> {
            val length = date.lengthOfMonth()
            val week = (date.dayOfMonth - 1) / 7 + 1
            return buildList {
                add(MonthOption(monthDay = date.dayOfMonth))
                if (date.dayOfMonth == length) add(MonthOption(monthDay = LAST_DAY_OF_MONTH))
                if (week <= 4) add(MonthOption(weekOfMonth = week, weekday = date.dayOfWeek))
                if (date.dayOfMonth + 7 > length) add(MonthOption(weekOfMonth = LAST_DAY_OF_MONTH, weekday = date.dayOfWeek))
            }
        }
    }
}

/**
 * The repeat part of the job form.
 *
 * One date drives everything: [startDay], the first (or next) due date. Monthly and yearly dates
 * come from it, and weekly on one day moves to its weekday, so there are no long lists of day
 * numbers to scroll. New jobs start today; picking a date changes it.
 *
 * Everything the rule might need is kept, so switching modes or units doesn't lose what you'd
 * picked, and [rule] takes the parts that apply. "Show it early" stays shorter than the repeat
 * and is suggested until you pick it.
 */
data class RepeatForm(
    /** Null = doesn't repeat. 0 while the number is being retyped. */
    val interval: Int? = null,
    val unit: RepeatUnit = RepeatUnit.WEEK,
    val anchor: RepeatAnchor = RepeatAnchor.SCHEDULE,
    /** Weekly on set days. Bitmask, see [weekdayBit]. Never empty once set. */
    val weekdays: Int = 0,
    /** Monthly. See [MonthOption]. Null until filled in from [startDay]. */
    val monthOption: MonthOption? = null,
    /** Yearly: month and day. */
    val month: Int? = null,
    val yearDay: Int? = null,
    val leadDays: Int = 0,
    val leadChosen: Boolean = false,
    /** The date the repeat starts from (epoch day): today for new jobs, the next due date when editing. */
    val startDay: Long = LocalDate.now().toEpochDay(),
    /** You picked [startDay] yourself. */
    val startPicked: Boolean = false,
    /** The rule and due date as loaded, to tell what an edit keeps. */
    val originalRule: RepeatRule? = null,
    val originalNextDue: Long? = null
) {
    val repeats: Boolean get() = interval != null

    val onSetDays: Boolean get() = anchor == RepeatAnchor.SCHEDULE

    val isValid: Boolean get() = (interval ?: 1) > 0

    val startDate: LocalDate get() = LocalDate.ofEpochDay(startDay)

    val rule: RepeatRule?
        get() {
            val n = interval ?: return null
            if (!onSetDays) return RepeatRule(interval = n, unit = unit, anchor = anchor, leadDays = leadDays)
            val base = RepeatRule(interval = n, unit = unit, anchor = anchor, leadDays = leadDays)
            return when (unit) {
                RepeatUnit.DAY -> base
                RepeatUnit.WEEK -> base.copy(weekdays = weekdays.takeIf { it != 0 })
                RepeatUnit.MONTH -> {
                    val option = monthOption ?: MonthOption(monthDay = startDate.dayOfMonth)
                    base.copy(
                        monthDay = option.monthDay,
                        weekOfMonth = option.weekOfMonth,
                        weekdays = option.weekday?.let(::weekdayBit)
                    )
                }
                RepeatUnit.YEAR -> base.copy(month = month ?: startDate.monthValue, monthDay = yearDay ?: startDate.dayOfMonth)
            }
        }

    /**
     * Monthly choices for the start date, plus the job's current one if the date doesn't suggest
     * it (a job on the 31st whose next due date is Feb 28).
     */
    val monthChoices: List<MonthOption>
        get() {
            val choices = MonthOption.choicesFor(startDate)
            val current = monthOption
            return if (current == null || current in choices) choices else listOf(current) + choices
        }

    /** "Show it early" choices that fit the repeat (always includes "on the day"). */
    val leadChoices: List<Int>
        get() {
            val max = rule?.takeIf { (interval ?: 0) > 0 }?.let(::maxLeadDays) ?: 0
            return LEAD_CHOICES.filter { it <= max }
        }

    fun turnedOn(): RepeatForm = if (repeats) this else withPreset(RepeatPreset.WEEKLY)

    fun turnedOff(): RepeatForm = copy(interval = null)

    /** Keeps the days or date you'd picked; otherwise they come from the start date. */
    fun withPreset(preset: RepeatPreset): RepeatForm =
        copy(interval = preset.interval, unit = preset.unit, anchor = RepeatAnchor.SCHEDULE).normalized()

    fun withInterval(value: Int): RepeatForm = copy(interval = value).normalized()

    fun withUnit(value: RepeatUnit): RepeatForm = copy(unit = value).normalized()

    fun withMode(value: RepeatAnchor): RepeatForm = copy(anchor = value).normalized()

    /** Won't untick the last day: a weekly job on set days needs at least one. */
    fun togglingWeekday(day: DayOfWeek): RepeatForm {
        val next = weekdays xor weekdayBit(day)
        return if (next == 0) this else copy(weekdays = next).normalized()
    }

    fun withMonthOption(option: MonthOption): RepeatForm = copy(monthOption = option).normalized()

    fun withLead(days: Int): RepeatForm = copy(leadDays = days, leadChosen = true)

    /**
     * Picks when it starts. The dates follow: monthly keeps its kind (date, last day, weekday)
     * but from the new date, yearly becomes that date, and weekly on one day moves to its weekday.
     */
    fun withStartDate(date: LocalDate): RepeatForm {
        val current = monthOption
        val choices = MonthOption.choicesFor(date)
        // Every date has at least one weekday choice: first to fourth, or the last.
        val month = current?.let { kept ->
            when {
                kept.byWeekday && kept.weekOfMonth == LAST_DAY_OF_MONTH ->
                    choices.firstOrNull { it.weekOfMonth == LAST_DAY_OF_MONTH } ?: choices.first { it.byWeekday }
                kept.byWeekday -> choices.first { it.byWeekday }
                kept.monthDay == LAST_DAY_OF_MONTH -> choices.firstOrNull { it.monthDay == LAST_DAY_OF_MONTH } ?: choices.first()
                else -> choices.first()
            }
        }
        val oneDay = weekdaysOf(weekdays).size == 1
        return copy(
            startDay = date.toEpochDay(),
            startPicked = true,
            monthOption = month,
            month = date.monthValue,
            yearDay = date.dayOfMonth,
            weekdays = if (oneDay) weekdayBit(date.dayOfWeek) else weekdays
        ).normalized()
    }

    /**
     * The first due date to save, or null to leave it to the repository: an edit that keeps the
     * kind of repeat keeps its due date, and a new "after I finish it" job without a picked date is
     * due now. Otherwise it's the rule's first date from the start date (today if that's passed).
     */
    fun firstDueToSave(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long? {
        val current = rule?.takeIf { (interval ?: 0) > 0 } ?: return null
        if (!startPicked) {
            val original = originalRule
            if (original != null && current.sameShapeAs(original)) return null
            if (!onSetDays && (original == null || originalNextDue == null)) return null
        }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val from = if (startDate.isBefore(today)) today else startDate
        return current.firstDueFrom(from.atStartOfDay(zone).toInstant().toEpochMilli(), zone)
    }

    /** When it'll next be due, for the "Next due" line. Null = due now. */
    fun nextDue(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long? {
        val current = rule?.takeIf { (interval ?: 0) > 0 } ?: return null
        val original = originalRule
        if (!startPicked && original != null && current.sameShapeAs(original)) return originalNextDue
        return firstDueToSave(now, zone) ?: current.initialDue(now, zone)
    }

    /** Fills in whatever the unit needs from the start date, then the lead window. */
    private fun normalized(): RepeatForm {
        var form = this
        val start = startDate
        if (form.weekdays == 0) form = form.copy(weekdays = weekdayBit(start.dayOfWeek))
        if (form.monthOption == null) form = form.copy(monthOption = MonthOption(monthDay = start.dayOfMonth))
        if (form.month == null || form.yearDay == null) form = form.copy(month = start.monthValue, yearDay = start.dayOfMonth)
        val current = form.rule?.takeIf { (form.interval ?: 0) > 0 } ?: return form
        val lead = if (form.leadChosen) form.leadDays else suggestedLeadDays(current)
        return form.copy(leadDays = lead.coerceIn(0, maxLeadDays(current)))
    }

    companion object {
        /** The job's repeat as the form shows it. Doesn't repeat = an empty form starting today. */
        fun from(job: Job, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): RepeatForm {
            val rule = job.repeatRule(zone) ?: return RepeatForm(startDay = today.toEpochDay())
            // Start from the next due date: one the rule lands on, so its own choices include it.
            val start = job.nextDueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                ?: (if (rule.onSetDays) rule.firstOnOrAfter(today) else today)
            val monthly = rule.unit == RepeatUnit.MONTH && rule.onSetDays
            return RepeatForm(
                interval = rule.interval,
                unit = rule.unit,
                anchor = rule.anchor,
                weekdays = if (rule.unit == RepeatUnit.WEEK) rule.weekdays ?: 0 else 0,
                monthOption = if (!monthly) null else if (rule.byWeekdayOfMonth) {
                    MonthOption(weekOfMonth = rule.weekOfMonth, weekday = weekdaysOf(rule.weekdays ?: 0).firstOrNull())
                } else {
                    MonthOption(monthDay = rule.monthDay ?: start.dayOfMonth)
                },
                month = rule.month,
                yearDay = rule.monthDay?.takeIf { rule.unit == RepeatUnit.YEAR },
                leadDays = rule.leadDays,
                leadChosen = true,
                startDay = start.toEpochDay(),
                originalRule = rule,
                originalNextDue = job.nextDueAt
            ).normalized()
        }

        /** For saved state: the original rule as text. */
        fun encodeRule(rule: RepeatRule?): String? = rule?.let {
            listOf(it.interval, it.unit.name, it.weekdays, it.anchor.name, it.monthDay, it.month, it.weekOfMonth, it.leadDays)
                .joinToString("|") { value -> value?.toString() ?: "" }
        }

        fun decodeRule(text: String?): RepeatRule? {
            val parts = text?.split("|")?.takeIf { it.size == 8 } ?: return null
            return RepeatRule(
                interval = parts[0].toIntOrNull() ?: return null,
                unit = RepeatUnit.entries.find { it.name == parts[1] } ?: return null,
                weekdays = parts[2].toIntOrNull(),
                anchor = RepeatAnchor.entries.find { it.name == parts[3] } ?: return null,
                monthDay = parts[4].toIntOrNull(),
                month = parts[5].toIntOrNull(),
                weekOfMonth = parts[6].toIntOrNull(),
                leadDays = parts[7].toIntOrNull() ?: 0
            )
        }
    }
}
