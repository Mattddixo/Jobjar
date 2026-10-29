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
import java.time.Month
import java.time.ZoneId

/** Choices for "Show it early", in days. Only the ones shorter than the repeat are offered. */
private val LEAD_CHOICES = listOf(0, 1, 3, 7, 14, 30, 60)

/** The quick picks at the top. All of them start on set days, from today. */
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
 * The repeat part of the job form. Everything the rule might need is kept (so switching between
 * set days and after finishing, or units, doesn't lose what you'd picked) and [rule] takes the
 * parts that apply. Every change fills in sensible defaults, keeps "show it early" shorter than
 * the repeat, and suggests one until you pick it yourself.
 */
data class RepeatForm(
    /** Null = doesn't repeat. 0 while the number is being retyped. */
    val interval: Int? = null,
    val unit: RepeatUnit = RepeatUnit.WEEK,
    val anchor: RepeatAnchor = RepeatAnchor.SCHEDULE,
    /** Weekly on set days. Bitmask, see [weekdayBit]. Never empty once set. */
    val weekdays: Int = 0,
    /** Monthly by date and yearly. See [Job.repeatMonthDay]. */
    val monthDay: Int? = null,
    /** Monthly on "the first Monday" instead of a date. */
    val monthByWeekday: Boolean = false,
    /** 1..4, or [LAST_DAY_OF_MONTH]. */
    val weekOfMonth: Int = 1,
    /** ISO day number, Monday = 1. */
    val monthWeekday: Int = 1,
    val month: Int? = null,
    val leadDays: Int = 0,
    val leadChosen: Boolean = false,
    /** Start of the day picked with "Change". Null = from today, or keep the current due date. */
    val firstDue: Long? = null,
    /** The rule and due date as loaded, to show what an edit keeps. */
    val originalRule: RepeatRule? = null,
    val originalNextDue: Long? = null
) {
    val repeats: Boolean get() = interval != null

    val onSetDays: Boolean get() = anchor == RepeatAnchor.SCHEDULE

    val isValid: Boolean get() = (interval ?: 1) > 0

    val rule: RepeatRule?
        get() {
            val n = interval ?: return null
            if (!onSetDays) return RepeatRule(interval = n, unit = unit, anchor = anchor, leadDays = leadDays)
            return when (unit) {
                RepeatUnit.DAY -> RepeatRule(interval = n, unit = unit, anchor = anchor, leadDays = leadDays)
                RepeatUnit.WEEK -> RepeatRule(interval = n, unit = unit, anchor = anchor, weekdays = weekdays.takeIf { it != 0 }, leadDays = leadDays)
                RepeatUnit.MONTH -> if (monthByWeekday) {
                    RepeatRule(
                        interval = n, unit = unit, anchor = anchor, weekdays = weekdayBit(DayOfWeek.of(monthWeekday)),
                        weekOfMonth = weekOfMonth, leadDays = leadDays
                    )
                } else {
                    RepeatRule(interval = n, unit = unit, anchor = anchor, monthDay = monthDay, leadDays = leadDays)
                }
                RepeatUnit.YEAR -> RepeatRule(interval = n, unit = unit, anchor = anchor, monthDay = monthDay, month = month, leadDays = leadDays)
            }
        }

    /** "Show it early" choices that fit the repeat (always includes "on the day"). */
    val leadChoices: List<Int>
        get() {
            val max = rule?.takeIf { (interval ?: 0) > 0 }?.let(::maxLeadDays) ?: 0
            return LEAD_CHOICES.filter { it <= max }
        }

    fun turnedOn(today: LocalDate = LocalDate.now()): RepeatForm = if (repeats) this else withPreset(RepeatPreset.WEEKLY, today)

    fun turnedOff(): RepeatForm = copy(interval = null)

    /** Keeps days or dates you'd already picked; otherwise they start from today. */
    fun withPreset(preset: RepeatPreset, today: LocalDate = LocalDate.now()): RepeatForm =
        copy(interval = preset.interval, unit = preset.unit, anchor = RepeatAnchor.SCHEDULE).normalized(today)

    fun withInterval(value: Int): RepeatForm = copy(interval = value).normalized(LocalDate.now())

    fun withUnit(value: RepeatUnit, today: LocalDate = LocalDate.now()): RepeatForm = copy(unit = value).normalized(today)

    fun withMode(value: RepeatAnchor, today: LocalDate = LocalDate.now()): RepeatForm = copy(anchor = value).normalized(today)

    /** Won't untick the last day: a weekly job on set days needs at least one. */
    fun togglingWeekday(day: DayOfWeek): RepeatForm {
        val next = weekdays xor weekdayBit(day)
        return if (next == 0) this else copy(weekdays = next).normalized(LocalDate.now())
    }

    fun withMonthDay(day: Int): RepeatForm = copy(monthDay = day).normalized(LocalDate.now())

    /** Switching to "same weekday" starts from today's: the 29th, a Thursday, is the last Thursday. */
    fun withMonthByWeekday(value: Boolean, today: LocalDate = LocalDate.now()): RepeatForm {
        if (!value || monthByWeekday) return copy(monthByWeekday = value).normalized(today)
        val week = (today.dayOfMonth - 1) / 7 + 1
        return copy(
            monthByWeekday = true,
            weekOfMonth = if (week > 4) LAST_DAY_OF_MONTH else week,
            monthWeekday = today.dayOfWeek.value
        ).normalized(today)
    }

    fun withWeekOfMonth(value: Int): RepeatForm = copy(weekOfMonth = value).normalized(LocalDate.now())

    fun withMonthWeekday(value: DayOfWeek): RepeatForm = copy(monthWeekday = value.value).normalized(LocalDate.now())

    /** Keeps the day valid for the new month: the 31st becomes the 30th in April. Feb 29 is allowed. */
    fun withMonth(value: Int): RepeatForm =
        copy(month = value, monthDay = monthDay?.coerceAtMost(Month.of(value).maxLength())).normalized(LocalDate.now())

    fun withLead(days: Int): RepeatForm = copy(leadDays = days, leadChosen = true)

    fun withFirstDue(startOfDay: Long): RepeatForm = copy(firstDue = startOfDay)

    /**
     * When it'll next be due, for the "Next:" line (set days only): the picked start, else the
     * current due date if the edit keeps it, else the first of the rule's dates from today. Null
     * means due now.
     */
    fun nextDue(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long? {
        val current = rule?.takeIf { onSetDays && (interval ?: 0) > 0 } ?: return null
        firstDue?.let { return current.firstDueFrom(it, zone) }
        val original = originalRule
        if (original != null && current.sameShapeAs(original)) return originalNextDue
        return current.initialDue(now, zone)
    }

    /** Defaults for whatever the current unit needs, then the lead window. */
    private fun normalized(today: LocalDate): RepeatForm {
        var form = this
        if (form.weekdays == 0) form = form.copy(weekdays = weekdayBit(today.dayOfWeek))
        if (form.monthDay == null) form = form.copy(monthDay = today.dayOfMonth)
        if (form.month == null) form = form.copy(month = today.monthValue)
        if (form.unit == RepeatUnit.YEAR) {
            // Yearly picks a real date, so "last day" from a monthly rule becomes that month's last.
            val maxDay = Month.of(form.month ?: 1).maxLength()
            val day = form.monthDay ?: 1
            form = form.copy(monthDay = if (day == LAST_DAY_OF_MONTH) maxDay else day.coerceIn(1, maxDay))
        }
        val current = form.rule?.takeIf { (form.interval ?: 0) > 0 } ?: return form
        val max = maxLeadDays(current)
        val lead = if (form.leadChosen) form.leadDays else suggestedLeadDays(current)
        return form.copy(leadDays = lead.coerceIn(0, max))
    }

    companion object {
        /** The job's repeat as the form shows it. Doesn't repeat = an empty form. */
        fun from(job: Job, zone: ZoneId = ZoneId.systemDefault()): RepeatForm {
            val rule = job.repeatRule(zone) ?: return RepeatForm()
            val today = job.nextDueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: LocalDate.now(zone)
            val monthly = rule.byWeekdayOfMonth
            return RepeatForm(
                interval = rule.interval,
                unit = rule.unit,
                anchor = rule.anchor,
                weekdays = if (rule.unit == RepeatUnit.WEEK) rule.weekdays ?: 0 else 0,
                monthDay = rule.monthDay,
                monthByWeekday = monthly,
                weekOfMonth = rule.weekOfMonth ?: 1,
                monthWeekday = if (monthly) weekdaysOf(rule.weekdays ?: 0).firstOrNull()?.value ?: 1 else today.dayOfWeek.value,
                month = rule.month,
                leadDays = rule.leadDays,
                leadChosen = true,
                originalRule = rule,
                originalNextDue = job.nextDueAt
            ).normalized(today)
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
