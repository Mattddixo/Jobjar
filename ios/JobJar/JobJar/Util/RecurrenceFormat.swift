import Foundation

/// Bit for a weekday (Monday = bit 0). `calendarWeekday` uses Calendar's numbering, Sunday = 1.
func weekdayBit(calendarWeekday: Int) -> Int { 1 << ((calendarWeekday + 5) % 7) }

let weekdaysMask = 0b0011111
let weekendMask = 0b1100000
let everyDayMask = 0b1111111

/// Calendar weekday numbers, Monday first.
let mondayFirstWeekdays = [2, 3, 4, 5, 6, 7, 1]

/// 1st, 2nd, 3rd, 4th... 11th, 12th, 13th... 21st.
func ordinal(_ n: Int) -> String {
    let suffix: String
    if (11...13).contains(n % 100) {
        suffix = "th"
    } else {
        switch n % 10 {
        case 1: suffix = "st"
        case 2: suffix = "nd"
        case 3: suffix = "rd"
        default: suffix = "th"
        }
    }
    return "\(n)\(suffix)"
}

/// "Mar 3".
func formatMonthDay(month: Int, day: Int, calendar: Calendar = .current) -> String {
    "\(calendar.shortMonthSymbols[month - 1]) \(day)"
}

/// Days in a month, Feb counted as 29 (a yearly date can be Feb 29).
func maxDaysInMonth(_ month: Int) -> Int {
    switch month {
    case 2: return 29
    case 4, 6, 9, 11: return 30
    default: return 31
    }
}

/// "first", "second"... "last", as in "the first Monday".
func formatWeekOfMonth(_ week: Int) -> String {
    switch week {
    case 1: return "first"
    case 2: return "second"
    case 3: return "third"
    case 4: return "fourth"
    default: return "last"
    }
}

/// "Weekly", "Every 3 days", "Mon, Thu", "Every 2 weeks on Wednesday", "Monthly on the 15th",
/// "Monthly on the first Monday", "Yearly on Mar 3" etc.
func formatRecurrence(_ rule: RepeatRule) -> String {
    let calendar = Calendar.current
    let n = rule.interval
    let onSchedule = rule.onSetDays
    if onSchedule, rule.unit == .week, let mask = rule.weekdays, mask & everyDayMask != 0, n > 1 {
        let picked = mondayFirstWeekdays.filter { mask & weekdayBit(calendarWeekday: $0) != 0 }
        let names = picked.count == 1
            ? calendar.weekdaySymbols[picked[0] - 1]
            : picked.map { calendar.shortWeekdaySymbols[$0 - 1] }.joined(separator: ", ")
        return "Every \(n) weeks on \(names)"
    }
    if onSchedule, rule.unit == .week, let mask = rule.weekdays, mask & everyDayMask != 0 {
        switch mask & everyDayMask {
        case everyDayMask: return "Every day"
        case weekdaysMask: return "Weekdays"
        case weekendMask: return "Weekends"
        default:
            let picked = mondayFirstWeekdays.filter { mask & weekdayBit(calendarWeekday: $0) != 0 }
            if picked.count == 1 { return "Every \(calendar.weekdaySymbols[picked[0] - 1])" }
            return picked.map { calendar.shortWeekdaySymbols[$0 - 1] }.joined(separator: ", ")
        }
    }
    switch rule.unit {
    case .day:
        switch n {
        case 1: return "Daily"
        case 7: return "Weekly"
        case 14: return "Every 2 weeks"
        default: return "Every \(n) days"
        }
    case .week:
        return n == 1 ? "Weekly" : "Every \(n) weeks"
    case .month:
        let every = n == 1 ? "Monthly" : "Every \(n) months"
        if rule.byWeekdayOfMonth, let week = rule.weekOfMonth, let mask = rule.weekdays,
           let weekday = mondayFirstWeekdays.first(where: { mask & weekdayBit(calendarWeekday: $0) != 0 }) {
            return "\(every) on the \(formatWeekOfMonth(week)) \(calendar.weekdaySymbols[weekday - 1])"
        }
        guard onSchedule, let day = rule.monthDay else { return every }
        return day == lastDayOfMonth ? "\(every) on the last day" : "\(every) on the \(ordinal(day))"
    case .year:
        let every = n == 1 ? "Yearly" : "Every \(n) years"
        guard onSchedule, let day = rule.monthDay, let month = rule.month else { return every }
        let dayOfMonth = day == lastDayOfMonth ? maxDaysInMonth(month) : day
        return "\(every) on \(formatMonthDay(month: month, day: dayOfMonth))"
    }
}

/// The job's rule as a label. Empty if it doesn't repeat.
func formatRecurrence(_ job: Job) -> String { job.repeatRule.map { formatRecurrence($0) } ?? "" }

/// Where a repeating job is in its cycle.
///
/// - "Overdue 2 days" once the due date has passed.
/// - Jobs that count from when you finish, with no lead window: "Due now", "Back later today",
///   "Back tomorrow", "Back in 5 days", as before.
/// - Everything else: "Due today", "Due tomorrow", "Due Fri" within a week, then "Due Mar 3".
func formatDueStatus(_ job: Job, now: Date = .now, calendar: Calendar = .current) -> String {
    guard let due = job.nextDueAt, let rule = job.repeatRule else { return "Due now" }
    let days = job.daysUntilDue(now: now, calendar: calendar) ?? 0
    if days < 0 { return -days == 1 ? "Overdue 1 day" : "Overdue \(-days) days" }
    let countsFromFinish = !rule.onSetDays && rule.leadDays == 0
    if countsFromFinish {
        if due <= now { return "Due now" }
        switch days {
        case 0: return "Back later today"
        case 1: return "Back tomorrow"
        default: return "Back in \(days) days"
        }
    }
    switch days {
    case 0: return "Due today"
    case 1: return "Due tomorrow"
    case 2..<7: return "Due \(calendar.shortWeekdaySymbols[calendar.component(.weekday, from: due) - 1])"
    default:
        let parts = calendar.dateComponents([.year, .month, .day], from: due)
        let monthDay = formatMonthDay(month: parts.month ?? 1, day: parts.day ?? 1, calendar: calendar)
        let sameYear = parts.year == calendar.component(.year, from: now)
        return sameYear ? "Due \(monthDay)" : "Due \(monthDay), \(parts.year ?? 0)"
    }
}

func countLabel(_ count: Int, _ singular: String, _ plural: String) -> String {
    "\(count) \(count == 1 ? singular : plural)"
}
