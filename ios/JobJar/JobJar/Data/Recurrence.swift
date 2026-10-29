import Foundation

/// `repeatMonthDay` for "the last day of the month".
let lastDayOfMonth = -1

/// Raw values match Android and the backup file.
enum RepeatUnit: String, CaseIterable, Identifiable {
    case day = "DAY", week = "WEEK", month = "MONTH", year = "YEAR"

    var id: String { rawValue }
}

/// What the next due date counts from. Completion: N units after you finish. Schedule: the next
/// date on the rule's own calendar (the 15th, Mar 3...), however early or late you were.
enum RepeatAnchor: String, CaseIterable, Identifiable {
    case completion = "COMPLETION", schedule = "SCHEDULE"

    var id: String { rawValue }
}

/// A repeating job's rule. Same maths as the Android app.
struct RepeatRule: Equatable {
    var interval: Int
    var unit: RepeatUnit = .day
    /// Weekly only. Set = due on these days (Monday is bit 0) and `anchor` doesn't apply.
    var weekdays: Int? = nil
    var anchor: RepeatAnchor = .completion
    /// Monthly/yearly on a schedule: 1...31, or `lastDayOfMonth`.
    var monthDay: Int? = nil
    /// Yearly on a schedule: 1...12.
    var month: Int? = nil
    /// Back in the jar (and "coming up") this many days before it's due.
    var leadDays: Int = 0

    var onWeekdays: Bool { (weekdays ?? 0) & everyDayMask != 0 }

    /// Same kind of cycle, so an edit can keep the current due date. Days and weeks count the
    /// same way, so "every 7 days" to "every week" isn't a change.
    func sameShape(as other: RepeatRule) -> Bool {
        if onWeekdays || other.onWeekdays { return weekdays == other.weekdays }
        func family(_ unit: RepeatUnit) -> RepeatUnit { unit == .week ? .day : unit }
        return family(unit) == family(other.unit) && anchor == other.anchor &&
            monthDay == other.monthDay && month == other.month
    }

    /// Next due date after finishing at `completedAt`. `previousDue` is the due date of the cycle
    /// just finished (nil = it was due now).
    ///
    /// - Set weekdays: start of the next chosen day, even if you were late this time.
    /// - From when you finish: N units later at the same time of day (DST-safe). A month after
    ///   Jan 31 is Feb 28.
    /// - On a schedule: the rule's next date after both the old due date and today, at the start
    ///   of the day. Finishing early doesn't bring the next one forward; finishing late doesn't
    ///   push it back.
    func nextDue(previousDue: Date?, completedAt: Date, calendar: Calendar = .current) -> Date {
        let completedDay = calendar.startOfDay(for: completedAt)
        if onWeekdays, let mask = weekdays {
            return nextMatchingDay(after: completedAt, mask: mask, calendar: calendar)
        }
        if anchor == .completion {
            let n = max(interval, 1)
            let component: Calendar.Component
            switch unit {
            case .day: component = .day
            case .week: component = .weekOfYear
            case .month: component = .month
            case .year: component = .year
            }
            return calendar.date(byAdding: component, value: n, to: completedAt) ?? completedAt
        }
        let dueDay = previousDue.map { calendar.startOfDay(for: $0) }
        let after = dueDay.map { max($0, completedDay) } ?? completedDay
        return nextScheduledDate(from: dueDay ?? completedDay, after: after, calendar: calendar)
    }

    /// Starting due date for a new rule. Nil = due now.
    ///
    /// - Weekdays: now if today's one of the days, otherwise the next one.
    /// - On a schedule: the next date on the rule's calendar, today included. Today's is kept as
    ///   the start of today (not nil) so it can go overdue.
    /// - From when you finish: due now.
    func initialDue(now: Date = .now, calendar: Calendar = .current) -> Date? {
        if onWeekdays, let mask = weekdays {
            if mask & weekdayBit(calendarWeekday: calendar.component(.weekday, from: now)) != 0 { return nil }
            return nextMatchingDay(after: now, mask: mask, calendar: calendar)
        }
        guard anchor == .schedule else { return nil }
        let today = calendar.startOfDay(for: now)
        let yesterday = calendar.date(byAdding: .day, value: -1, to: today) ?? today
        return nextScheduledDate(from: today, after: yesterday, calendar: calendar)
    }

    /// The rule's dates counted from `from` in steps of the interval: the first one strictly after
    /// `after`. Both are starts of days. Months and years land on `monthDay` (and `month`),
    /// falling back to `from`'s.
    private func nextScheduledDate(from: Date, after: Date, calendar: Calendar) -> Date {
        let step = max(interval, 1)
        switch unit {
        case .day, .week:
            let stepDays = unit == .week ? step * 7 : step
            let behind = calendar.dateComponents([.day], from: from, to: after).day ?? 0
            let cycles = behind < 0 ? 0 : behind / stepDays + 1
            return calendar.date(byAdding: .day, value: cycles * stepDays, to: from) ?? from
        case .month, .year:
            let stepMonths = unit == .year ? step * 12 : step
            let parts = calendar.dateComponents([.year, .month, .day], from: from)
            let day = monthDay ?? parts.day ?? 1
            var cycle = monthIndex(year: parts.year ?? 2000, month: unit == .year ? (month ?? parts.month ?? 1) : (parts.month ?? 1))
            // Start a cycle back so an early date in `from`'s own cycle isn't skipped.
            cycle -= stepMonths
            var date = clampedDate(monthIndex: cycle, day: day, calendar: calendar)
            while date <= after {
                cycle += stepMonths
                date = clampedDate(monthIndex: cycle, day: day, calendar: calendar)
            }
            return date
        }
    }
}

/// Months since year 0, so stepping by months is plain arithmetic.
private func monthIndex(year: Int, month: Int) -> Int { year * 12 + (month - 1) }

/// `day` in that month, pulled back to the month's last day (the 31st in April is the 30th).
private func clampedDate(monthIndex: Int, day: Int, calendar: Calendar) -> Date {
    let year = monthIndex / 12
    let month = monthIndex % 12 + 1
    let first = calendar.date(from: DateComponents(year: year, month: month, day: 1)) ?? .now
    let length = calendar.range(of: .day, in: .month, for: first)?.count ?? 28
    let target = day == lastDayOfMonth ? length : min(max(day, 1), length)
    return calendar.date(byAdding: .day, value: target - 1, to: first) ?? first
}

private func nextMatchingDay(after date: Date, mask: Int, calendar: Calendar) -> Date {
    var day = calendar.startOfDay(for: date)
    repeat {
        day = calendar.date(byAdding: .day, value: 1, to: day) ?? day.addingTimeInterval(86_400)
    } while mask & weekdayBit(calendarWeekday: calendar.component(.weekday, from: day)) == 0
    return day
}

/// When a resting job comes back into the jar: the due time itself, or with a lead window the
/// start of the day that many days before.
func showFrom(_ nextDueAt: Date, leadDays: Int, calendar: Calendar = .current) -> Date {
    guard leadDays > 0 else { return nextDueAt }
    let dueDay = calendar.startOfDay(for: nextDueAt)
    return calendar.date(byAdding: .day, value: -leadDays, to: dueDay) ?? dueDay
}

/// The editor's "show it early" default: none for frequent things, more the rarer it is.
func suggestedLeadDays(_ rule: RepeatRule) -> Int {
    if rule.onWeekdays { return 0 }
    let days: Int
    switch rule.unit {
    case .day: days = rule.interval
    case .week: days = rule.interval * 7
    case .month: days = rule.interval * 30
    case .year: days = rule.interval * 365
    }
    switch days {
    case ..<28: return 0
    case ..<80: return 3
    case ..<300: return 7
    default: return 30
    }
}

/// The editor's default for "counts from": days and weeks from when you finish, months and years
/// on the calendar.
func defaultAnchor(_ unit: RepeatUnit) -> RepeatAnchor {
    unit == .month || unit == .year ? .schedule : .completion
}

/// The repeat columns as stored on a job, a JobRecord and in backups.
struct RepeatFields: Equatable {
    /// Counts units, despite the name. Nil = doesn't repeat.
    var recurrenceDays: Int?
    var repeatWeekdays: Int?
    var repeatUnit: RepeatUnit = .day
    var repeatAnchor: RepeatAnchor = .completion
    var repeatMonthDay: Int?
    var repeatMonth: Int?
    var leadDays: Int = 0

    /// The rule, with the old shapes folded in: weekday jobs are "every week on these days",
    /// and old rows are "every N days from when you finish".
    var rule: RepeatRule? {
        guard let count = recurrenceDays else { return nil }
        if let mask = repeatWeekdays, mask & everyDayMask != 0 {
            return RepeatRule(interval: 1, unit: .week, weekdays: mask, leadDays: leadDays)
        }
        return RepeatRule(
            interval: max(count, 1), unit: repeatUnit, anchor: repeatAnchor,
            monthDay: repeatMonthDay, month: repeatMonth, leadDays: leadDays
        )
    }

    /// Stores `rule`, dropping parts that don't apply. Nil = doesn't repeat.
    init(rule: RepeatRule?) {
        guard let rule else { return }
        leadDays = max(rule.leadDays, 0)
        if rule.onWeekdays, let mask = rule.weekdays {
            recurrenceDays = 1
            repeatWeekdays = mask & everyDayMask
            repeatUnit = .week
            repeatAnchor = .schedule
            return
        }
        let onCalendar = rule.anchor == .schedule
        recurrenceDays = max(rule.interval, 1)
        repeatUnit = rule.unit
        repeatAnchor = rule.anchor
        if onCalendar && (rule.unit == .month || rule.unit == .year),
           let day = rule.monthDay, day == lastDayOfMonth || (1...31).contains(day) {
            repeatMonthDay = day
        }
        if onCalendar && rule.unit == .year, let month = rule.month, (1...12).contains(month) {
            repeatMonth = month
        }
    }

    init(
        recurrenceDays: Int?, repeatWeekdays: Int?, repeatUnit: RepeatUnit = .day,
        repeatAnchor: RepeatAnchor = .completion, repeatMonthDay: Int? = nil, repeatMonth: Int? = nil,
        leadDays: Int = 0
    ) {
        self.recurrenceDays = recurrenceDays
        self.repeatWeekdays = repeatWeekdays
        self.repeatUnit = repeatUnit
        self.repeatAnchor = repeatAnchor
        self.repeatMonthDay = repeatMonthDay
        self.repeatMonth = repeatMonth
        self.leadDays = leadDays
    }
}

extension Job {
    var repeatUnit: RepeatUnit {
        get { RepeatUnit(rawValue: repeatUnitRaw ?? "") ?? .day }
        set { repeatUnitRaw = newValue == .day ? nil : newValue.rawValue }
    }

    var repeatAnchor: RepeatAnchor {
        get { RepeatAnchor(rawValue: repeatAnchorRaw ?? "") ?? .completion }
        set { repeatAnchorRaw = newValue == .completion ? nil : newValue.rawValue }
    }

    var leadDays: Int {
        get { leadDaysValue ?? 0 }
        set { leadDaysValue = newValue == 0 ? nil : newValue }
    }

    var repeatFields: RepeatFields {
        get {
            RepeatFields(
                recurrenceDays: recurrenceDays, repeatWeekdays: repeatWeekdays, repeatUnit: repeatUnit,
                repeatAnchor: repeatAnchor, repeatMonthDay: repeatMonthDay, repeatMonth: repeatMonth, leadDays: leadDays
            )
        }
        set {
            recurrenceDays = newValue.recurrenceDays
            repeatWeekdays = newValue.repeatWeekdays
            repeatUnit = newValue.repeatUnit
            repeatAnchor = newValue.repeatAnchor
            repeatMonthDay = newValue.repeatMonthDay
            repeatMonth = newValue.repeatMonth
            leadDays = newValue.leadDays
        }
    }

    var repeatRule: RepeatRule? { repeatFields.rule }

    /// Calendar days from today to the due date: 0 = today, negative = overdue. Nil = due now
    /// (or doesn't repeat).
    func daysUntilDue(now: Date = .now, calendar: Calendar = .current) -> Int? {
        guard recurrenceDays != nil, let nextDueAt else { return nil }
        return calendar.dateComponents([.day], from: calendar.startOfDay(for: now), to: calendar.startOfDay(for: nextDueAt)).day
    }

    /// Past its due date (by the calendar: due yesterday is overdue, due earlier today isn't).
    func isOverdue(now: Date = .now, calendar: Calendar = .current) -> Bool {
        (daysUntilDue(now: now, calendar: calendar) ?? 0) < 0
    }
}
