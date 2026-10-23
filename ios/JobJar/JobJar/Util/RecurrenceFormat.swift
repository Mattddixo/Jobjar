import Foundation

/// Bit for a weekday (Monday = bit 0). `calendarWeekday` uses Calendar's numbering, Sunday = 1.
func weekdayBit(calendarWeekday: Int) -> Int { 1 << ((calendarWeekday + 5) % 7) }

let weekdaysMask = 0b0011111
let weekendMask = 0b1100000
let everyDayMask = 0b1111111

/// Calendar weekday numbers, Monday first.
let mondayFirstWeekdays = [2, 3, 4, 5, 6, 7, 1]

private func nextMatchingDay(after date: Date, mask: Int, calendar: Calendar) -> Date {
    var day = calendar.startOfDay(for: date)
    repeat {
        day = calendar.date(byAdding: .day, value: 1, to: day) ?? day.addingTimeInterval(86_400)
    } while mask & weekdayBit(calendarWeekday: calendar.component(.weekday, from: day)) == 0
    return day
}

/// Next due date after finishing at `completedAt`.
/// Every N days: N calendar days later, same time (DST-safe).
/// Set weekdays: start of the next chosen day, even if you were late.
func nextDueAfterCompletion(recurrenceDays: Int, repeatWeekdays: Int?, completedAt: Date, calendar: Calendar = .current) -> Date {
    if let mask = repeatWeekdays, mask & everyDayMask != 0 {
        return nextMatchingDay(after: completedAt, mask: mask, calendar: calendar)
    }
    return calendar.date(byAdding: .day, value: recurrenceDays, to: completedAt)
        ?? completedAt.addingTimeInterval(Double(recurrenceDays) * 86_400)
}

/// Starting due date for a weekday schedule: now if today's one of the days, else the next one.
func initialNextDue(repeatWeekdays: Int?, now: Date = .now, calendar: Calendar = .current) -> Date? {
    guard let mask = repeatWeekdays, mask & everyDayMask != 0 else { return nil }
    if mask & weekdayBit(calendarWeekday: calendar.component(.weekday, from: now)) != 0 { return nil }
    return nextMatchingDay(after: now, mask: mask, calendar: calendar)
}

/// "Weekly", "Every 3 days", "Mon, Thu", "Weekdays" etc.
func formatRecurrence(days: Int, weekdays: Int? = nil) -> String {
    if let mask = weekdays, mask & everyDayMask != 0 {
        switch mask & everyDayMask {
        case everyDayMask: return "Every day"
        case weekdaysMask: return "Weekdays"
        case weekendMask: return "Weekends"
        default:
            let calendar = Calendar.current
            let picked = mondayFirstWeekdays.filter { mask & weekdayBit(calendarWeekday: $0) != 0 }
            if picked.count == 1 { return "Every \(calendar.weekdaySymbols[picked[0] - 1])" }
            return picked.map { calendar.shortWeekdaySymbols[$0 - 1] }.joined(separator: ", ")
        }
    }
    switch days {
    case 1: return "Daily"
    case 7: return "Weekly"
    case 14: return "Every 2 weeks"
    case 30: return "Monthly"
    default: return "Every \(days) days"
    }
}

func formatRecurrenceInterval(_ days: Int) -> String { formatRecurrence(days: days) }

/// "Due now", or how many calendar days until it's back.
func formatDueStatus(_ nextDueAt: Date?, now: Date = .now, calendar: Calendar = .current) -> String {
    guard let nextDueAt, nextDueAt > now else { return "Due now" }
    let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: now), to: calendar.startOfDay(for: nextDueAt)).day ?? 0
    switch days {
    case ...0: return "Back later today"
    case 1: return "Back tomorrow"
    default: return "Back in \(days) days"
    }
}

func countLabel(_ count: Int, _ singular: String, _ plural: String) -> String {
    "\(count) \(count == 1 ? singular : plural)"
}
