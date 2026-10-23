import Foundation

private let scheduledFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.setLocalizedDateFormatFromTemplate("MMMd jmm")
    return formatter
}()

private let todayFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.setLocalizedDateFormatFromTemplate("MMMd")
    return formatter
}()

/// "Nov 4, 2:00 PM", in local time.
func formatScheduledDateTime(_ date: Date) -> String { scheduledFormatter.string(from: date) }

/// "Nov 4" for today.
func formatTodayLabel(_ now: Date = .now) -> String { todayFormatter.string(from: now) }

/// 9am on the next day. Most things get scheduled ahead of time.
func defaultScheduleTime(now: Date = .now, calendar: Calendar = .current) -> Date {
    let tomorrow = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: now)) ?? now
    return calendar.date(bySettingHour: 9, minute: 0, second: 0, of: tomorrow) ?? tomorrow
}
