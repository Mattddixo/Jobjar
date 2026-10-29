import Foundation

/// `repeatMonthDay` for "the last day of the month", and `repeatWeekOfMonth` for "the last <weekday>".
let lastDayOfMonth = -1

/// Raw values match Android and the backup file.
enum RepeatUnit: String, CaseIterable, Identifiable {
    case day = "DAY", week = "WEEK", month = "MONTH", year = "YEAR"

    var id: String { rawValue }
}

/// The two kinds of repeat. Completion ("after I finish it"): N units after the day you tick it
/// off. Schedule ("on set days"): the rule's own dates (Wednesdays, the 15th, Mar 3...), however
/// early or late you were.
enum RepeatAnchor: String, CaseIterable, Identifiable {
    case completion = "COMPLETION", schedule = "SCHEDULE"

    var id: String { rawValue }
}

/// A repeating job's rule. Same maths as the Android app.
///
/// On set days, the unit says what the dates are:
/// - day: every N days from the first due date.
/// - week: every N weeks on `weekdays`.
/// - month: every N months on `monthDay`, or on the `weekOfMonth`th of the one day in `weekdays`.
/// - year: every N years on `month` `monthDay`.
struct RepeatRule: Equatable {
    var interval: Int
    var unit: RepeatUnit = .day
    /// Bitmask, Monday = bit 0. Weekly: the days. Monthly by weekday: exactly one day.
    var weekdays: Int? = nil
    var anchor: RepeatAnchor = .completion
    /// Monthly by date and yearly: 1...31, or `lastDayOfMonth` (monthly only).
    var monthDay: Int? = nil
    /// Yearly: 1...12.
    var month: Int? = nil
    /// Monthly by weekday: 1...4, or `lastDayOfMonth` for the last one.
    var weekOfMonth: Int? = nil
    /// Back in the jar (and "coming up") this many days before it's due. See `maxLeadDays`.
    var leadDays: Int = 0

    var onSetDays: Bool { anchor == .schedule }

    /// Monthly on "the first Monday" rather than a date.
    var byWeekdayOfMonth: Bool { onSetDays && unit == .month && weekOfMonth != nil }

    fileprivate var dayMask: Int { (weekdays ?? 0) & everyDayMask }

    /// Same kind of cycle, so an edit can keep the current due date: same mode, and on set days
    /// the same days or dates. The number and the lead window don't count. After finishing, days
    /// and weeks count the same way.
    func sameShape(as other: RepeatRule) -> Bool {
        guard anchor == other.anchor else { return false }
        func family(_ unit: RepeatUnit) -> RepeatUnit { unit == .week ? .day : unit }
        guard onSetDays else { return family(unit) == family(other.unit) }
        return unit == other.unit && dayMask == other.dayMask && monthDay == other.monthDay &&
            month == other.month && weekOfMonth == other.weekOfMonth
    }

    /// Next due date after finishing at `completedAt`. `previousDue` is the due date of the cycle
    /// just finished (nil = it was due now).
    ///
    /// - After finishing: N units later at the same time of day (DST-safe). A month after Jan 31
    ///   is Feb 28.
    /// - On set days: the rule's next date after both the old due date and today, at the start of
    ///   the day, counting cycles from the old due date. Finishing early doesn't bring the next one
    ///   forward, finishing late doesn't push it back, and missed ones don't pile up.
    func nextDue(previousDue: Date?, completedAt: Date, calendar: Calendar = .current) -> Date {
        guard onSetDays else {
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
        let completedDay = calendar.startOfDay(for: completedAt)
        let dueDay = previousDue.map { calendar.startOfDay(for: $0) }
        let after = dueDay.map { max($0, completedDay) } ?? completedDay
        return nextScheduledDate(from: dueDay ?? completedDay, after: after, calendar: calendar)
    }

    /// Starting due date. Nil = due now (after finishing). On set days it's the first of the
    /// rule's dates from today, kept as a date (even if it's today) so it can go overdue.
    func initialDue(now: Date = .now, calendar: Calendar = .current) -> Date? {
        onSetDays ? firstOnOrAfter(now, calendar: calendar) : nil
    }

    /// The first date on or after `date` that the rule lands on, in any cycle. That date then
    /// starts the cycle: picking next week's Wednesday for "every 2 weeks on Wed" makes it the
    /// other week, and December for "every 3 months on the 15th" makes it Dec, Mar, Jun, Sep.
    func firstOnOrAfter(_ date: Date, calendar: Calendar = .current) -> Date {
        let day = calendar.startOfDay(for: date)
        if unit == .day || !onSetDays { return day }
        var single = self
        single.interval = 1
        return single.nextScheduledDate(from: day, after: addDays(-1, to: day, calendar), calendar: calendar)
    }

    /// First date strictly after `after`, counting cycles from `from` (a date the rule lands on,
    /// or the day to start from). Both are starts of days.
    fileprivate func nextScheduledDate(from: Date, after: Date, calendar: Calendar) -> Date {
        let step = max(interval, 1)
        switch unit {
        case .day:
            let behind = daysBetween(from, after, calendar)
            let cycles = behind < 0 ? 0 : behind / step + 1
            return addDays(cycles * step, to: from, calendar)
        case .week:
            let mask = dayMask != 0 ? dayMask : weekdayBit(calendarWeekday: calendar.component(.weekday, from: from))
            let startWeek = monday(of: from, calendar)
            // Weeks count Monday to Sunday: "every 2 weeks on Mon, Thu" is both days, then a week off.
            var date = after < from ? from : addDays(1, to: after, calendar)
            for _ in 0..<(7 * step + 7) {
                let weeks = daysBetween(startWeek, monday(of: date, calendar), calendar) / 7
                let onWeek = ((weeks % step) + step) % step == 0
                if onWeek && mask & weekdayBit(calendarWeekday: calendar.component(.weekday, from: date)) != 0 { return date }
                date = addDays(1, to: date, calendar)
            }
            return date
        case .month, .year:
            let stepMonths = unit == .year ? step * 12 : step
            let parts = calendar.dateComponents([.year, .month, .day], from: from)
            let fallbackDay = parts.day ?? 1
            var cycle = monthIndex(year: parts.year ?? 2000, month: unit == .year ? (month ?? parts.month ?? 1) : (parts.month ?? 1))
            // Start a cycle back so an early date in `from`'s own cycle isn't skipped.
            cycle -= stepMonths
            var date = dateInMonth(cycle, fallbackDay: fallbackDay, calendar: calendar)
            while date <= after {
                cycle += stepMonths
                date = dateInMonth(cycle, fallbackDay: fallbackDay, calendar: calendar)
            }
            return date
        }
    }

    /// The rule's date in that month: "the first Monday", or the day (`fallbackDay` if none is set).
    private func dateInMonth(_ monthIndex: Int, fallbackDay: Int, calendar: Calendar) -> Date {
        let first = firstOfMonth(monthIndex, calendar)
        let length = calendar.range(of: .day, in: .month, for: first)?.count ?? 28
        if byWeekdayOfMonth, let week = weekOfMonth,
           let target = mondayFirstWeekdays.first(where: { dayMask & weekdayBit(calendarWeekday: $0) != 0 }) {
            if week == lastDayOfMonth {
                let last = addDays(length - 1, to: first, calendar)
                let back = (calendar.component(.weekday, from: last) - target + 7) % 7
                return addDays(-back, to: last, calendar)
            }
            let ahead = (target - calendar.component(.weekday, from: first) + 7) % 7
            return addDays(ahead + 7 * (min(max(week, 1), 4) - 1), to: first, calendar)
        }
        let day = monthDay ?? fallbackDay
        let target = day == lastDayOfMonth ? length : min(max(day, 1), length)
        return addDays(target - 1, to: first, calendar)
    }
}

/// Longest sensible "show it early": less than the shortest gap between two due dates, so a job
/// has some time out of the jar. Weekly on one day allows up to 6, Mon + Thu up to 2.
func maxLeadDays(_ rule: RepeatRule) -> Int {
    let n = max(rule.interval, 1)
    let shortestGap: Int
    switch rule.unit {
    case .day:
        shortestGap = n
    case .week:
        // Monday = 1 ... Sunday = 7.
        let days = (0..<7).filter { rule.dayMask & (1 << $0) != 0 }.map { $0 + 1 }
        if !rule.onSetDays || days.count < 2 {
            shortestGap = 7 * n
        } else {
            let inWeek = zip(days, days.dropFirst()).map { $1 - $0 }.min() ?? 7
            shortestGap = min(inWeek, days[0] + 7 * n - days[days.count - 1])
        }
    case .month:
        shortestGap = 28 * n
    case .year:
        shortestGap = 365 * n
    }
    return max(shortestGap - 1, 0)
}

/// Months since year 0, so stepping by months is plain arithmetic.
private func monthIndex(year: Int, month: Int) -> Int { year * 12 + (month - 1) }

private func firstOfMonth(_ index: Int, _ calendar: Calendar) -> Date {
    calendar.date(from: DateComponents(year: index / 12, month: index % 12 + 1, day: 1)) ?? .now
}

private func addDays(_ days: Int, to date: Date, _ calendar: Calendar) -> Date {
    calendar.date(byAdding: .day, value: days, to: date) ?? date.addingTimeInterval(Double(days) * 86_400)
}

private func daysBetween(_ a: Date, _ b: Date, _ calendar: Calendar) -> Int {
    calendar.dateComponents([.day], from: calendar.startOfDay(for: a), to: calendar.startOfDay(for: b)).day ?? 0
}

/// The Monday starting `date`'s week (weeks run Monday to Sunday, whatever the phone's setting).
private func monday(of date: Date, _ calendar: Calendar) -> Date {
    let sinceMonday = (calendar.component(.weekday, from: date) + 5) % 7
    return addDays(-sinceMonday, to: calendar.startOfDay(for: date), calendar)
}

/// When a resting job comes back into the jar: the due time itself, or with a lead window the
/// start of the day that many days before.
func showFrom(_ nextDueAt: Date, leadDays: Int, calendar: Calendar = .current) -> Date {
    guard leadDays > 0 else { return nextDueAt }
    return addDays(-leadDays, to: calendar.startOfDay(for: nextDueAt), calendar)
}

/// The editor's "show it early" default: none for frequent things, more the rarer it is.
func suggestedLeadDays(_ rule: RepeatRule) -> Int {
    let days: Int
    switch rule.unit {
    case .day: days = rule.interval
    case .week: days = rule.interval * 7
    case .month: days = rule.interval * 30
    case .year: days = rule.interval * 365
    }
    let suggestion: Int
    switch days {
    case ..<28: suggestion = 0
    case ..<80: suggestion = 3
    case ..<300: suggestion = 7
    default: suggestion = 30
    }
    return min(suggestion, maxLeadDays(rule))
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
    var repeatWeekOfMonth: Int?
    var leadDays: Int = 0

    var rule: RepeatRule? { rule(fallbackDate: nil) }

    /// The rule, with the older shapes folded in:
    /// - rows from before units: every N days after finishing, or (with weekdays) weekly on those days;
    /// - "weekly on set days" saved without days by the first version of this editor: the weekday
    ///   of `fallbackDate` (the job's due date or creation date);
    /// - a lead window longer than the rule allows is cut down to fit.
    func rule(fallbackDate: Date?, calendar: Calendar = .current) -> RepeatRule? {
        guard let count = recurrenceDays else { return nil }
        let mask = repeatWeekdays.map { $0 & everyDayMask }.flatMap { $0 != 0 ? $0 : nil }
        var base: RepeatRule
        if let mask, repeatUnit == .day || repeatUnit == .week {
            // Before units, weekday jobs were saved as "7 days" on those days: weekly.
            base = RepeatRule(interval: repeatUnit == .day ? 1 : max(count, 1), unit: .week, weekdays: mask, anchor: .schedule)
        } else if repeatUnit == .week && repeatAnchor == .schedule {
            let weekday = calendar.component(.weekday, from: fallbackDate ?? .now)
            base = RepeatRule(interval: max(count, 1), unit: .week, weekdays: weekdayBit(calendarWeekday: weekday), anchor: .schedule)
        } else {
            let byWeekday = repeatUnit == .month && repeatWeekOfMonth != nil && mask != nil
            base = RepeatRule(
                interval: max(count, 1), unit: repeatUnit, weekdays: byWeekday ? mask : nil, anchor: repeatAnchor,
                monthDay: repeatMonthDay, month: repeatMonth, weekOfMonth: byWeekday ? repeatWeekOfMonth : nil
            )
        }
        base.leadDays = min(max(leadDays, 0), maxLeadDays(base))
        return base
    }

    /// Stores `rule`, dropping parts that don't apply. Nil = doesn't repeat.
    init(rule: RepeatRule?) {
        guard let rule else { return }
        recurrenceDays = max(rule.interval, 1)
        repeatUnit = rule.unit
        repeatAnchor = rule.anchor
        leadDays = min(max(rule.leadDays, 0), maxLeadDays(rule))
        guard rule.onSetDays else { return }
        let mask = rule.dayMask
        switch rule.unit {
        case .day:
            break
        case .week:
            repeatWeekdays = mask != 0 ? mask : nil
        case .month:
            let week = rule.weekOfMonth.flatMap { $0 == lastDayOfMonth || (1...4).contains($0) ? $0 : nil }
            let day = mondayFirstWeekdays.first { mask & weekdayBit(calendarWeekday: $0) != 0 }
            if let week, let day {
                repeatWeekOfMonth = week
                repeatWeekdays = weekdayBit(calendarWeekday: day)
            } else if let monthDay = rule.monthDay, monthDay == lastDayOfMonth || (1...31).contains(monthDay) {
                repeatMonthDay = monthDay
            }
        case .year:
            repeatMonth = rule.month.flatMap { (1...12).contains($0) ? $0 : nil }
            repeatMonthDay = rule.monthDay.flatMap { (1...31).contains($0) ? $0 : nil }
        }
    }

    init(
        recurrenceDays: Int?, repeatWeekdays: Int?, repeatUnit: RepeatUnit = .day,
        repeatAnchor: RepeatAnchor = .completion, repeatMonthDay: Int? = nil, repeatMonth: Int? = nil,
        repeatWeekOfMonth: Int? = nil, leadDays: Int = 0
    ) {
        self.recurrenceDays = recurrenceDays
        self.repeatWeekdays = repeatWeekdays
        self.repeatUnit = repeatUnit
        self.repeatAnchor = repeatAnchor
        self.repeatMonthDay = repeatMonthDay
        self.repeatMonth = repeatMonth
        self.repeatWeekOfMonth = repeatWeekOfMonth
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
                repeatAnchor: repeatAnchor, repeatMonthDay: repeatMonthDay, repeatMonth: repeatMonth,
                repeatWeekOfMonth: repeatWeekOfMonth, leadDays: leadDays
            )
        }
        set {
            recurrenceDays = newValue.recurrenceDays
            repeatWeekdays = newValue.repeatWeekdays
            repeatUnit = newValue.repeatUnit
            repeatAnchor = newValue.repeatAnchor
            repeatMonthDay = newValue.repeatMonthDay
            repeatMonth = newValue.repeatMonth
            repeatWeekOfMonth = newValue.repeatWeekOfMonth
            leadDays = newValue.leadDays
        }
    }

    var repeatRule: RepeatRule? { repeatFields.rule(fallbackDate: nextDueAt ?? createdAt) }

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
