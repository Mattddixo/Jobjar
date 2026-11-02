import Foundation
import UIKit
import UserNotifications

/// Settings > Reminders (UserDefaults keys). Both off unless turned on; 9am unless changed.
let remindersDueKey = "reminders_due"
let remindersNudgeKey = "reminders_nudge"
/// Minutes after midnight.
let remindersTimeKey = "reminders_time"
let defaultReminderMinutes = 9 * 60

/// One notification to schedule.
struct PlannedReminder: Equatable {
    var id: String
    var date: Date
    var title: String
    var body: String
}

/// The due reminders for the next `days` days, one notification per day with something in it, at
/// the reminder time. Same rules as Android's daily reminder: repeating jobs due that day, ones
/// whose "show it early" window starts that day, and (in the first one) anything overdue. Jobs
/// that are simply due now with no date aren't mentioned.
func planDueReminders(
    jobs: [Job], now: Date = .now, minutes: Int, days: Int = 30, calendar: Calendar = .current
) -> [PlannedReminder] {
    let today = calendar.startOfDay(for: now)
    func reminderTime(on day: Date) -> Date {
        calendar.date(byAdding: .minute, value: minutes, to: day) ?? day
    }
    // The first reminder still to come: today's if it's ahead, otherwise tomorrow's.
    let firstDay = reminderTime(on: today) > now ? today : calendar.date(byAdding: .day, value: 1, to: today) ?? today
    let lastDay = calendar.date(byAdding: .day, value: days, to: today) ?? today

    var byDay: [Date: [(title: String, dueDay: Date)]] = [:]
    for job in jobs where job.parentId == nil && job.recurrenceDays != nil {
        guard let due = job.nextDueAt else { continue }
        let dueDay = calendar.startOfDay(for: due)
        if dueDay < firstDay {
            byDay[firstDay, default: []].append((job.title, dueDay))
        } else if dueDay <= lastDay {
            byDay[dueDay, default: []].append((job.title, dueDay))
        }
        let lead = job.repeatRule?.leadDays ?? 0
        if lead > 0, let windowDay = calendar.date(byAdding: .day, value: -lead, to: dueDay),
           windowDay >= firstDay, windowDay <= lastDay {
            byDay[windowDay, default: []].append((job.title, dueDay))
        }
    }

    return byDay.keys.sorted().map { day in
        let items = (byDay[day] ?? []).sorted { a, b in
            a.dueDay != b.dueDay ? a.dueDay < b.dueDay : a.title.localizedCaseInsensitiveCompare(b.title) == .orderedAscending
        }
        let lines = items.map { "\($0.title) · \(reminderDueText(dueDay: $0.dueDay, on: day, calendar: calendar))" }
        let title = lines.count == 1 ? lines[0] : "\(lines.count) repeating jobs coming due"
        return PlannedReminder(
            id: "reminder-\(Int(day.timeIntervalSince1970))",
            date: reminderTime(on: day),
            title: title,
            body: lines.count == 1 ? "" : lines.joined(separator: "\n")
        )
    }
}

/// "Due today", "Overdue 2 days", "Due tomorrow", "Due Fri", "Due Mar 3", as seen on `day`.
private func reminderDueText(dueDay: Date, on day: Date, calendar: Calendar) -> String {
    let days = calendar.dateComponents([.day], from: day, to: dueDay).day ?? 0
    switch days {
    case ..<0: return -days == 1 ? "Overdue 1 day" : "Overdue \(-days) days"
    case 0: return "Due today"
    case 1: return "Due tomorrow"
    case 2..<7: return "Due \(calendar.shortWeekdaySymbols[calendar.component(.weekday, from: dueDay) - 1])"
    default: return "Due \(dueDay.formatted(.dateTime.month(.abbreviated).day()))"
    }
}

/// Puts the reminders in the phone's notification schedule. iPhone apps can't wake up daily to
/// check, so the next 30 days of due reminders are scheduled ahead and redone whenever the app
/// opens or goes to the background. The nudge is one repeating notification.
enum ReminderScheduler {
    private static let nudgeId = "nudge"

    static func reschedule(jobs: [Job], defaults: UserDefaults = .standard) {
        let center = UNUserNotificationCenter.current()
        let due = defaults.bool(forKey: remindersDueKey)
        let nudge = defaults.bool(forKey: remindersNudgeKey)
        let minutes = defaults.object(forKey: remindersTimeKey) as? Int ?? defaultReminderMinutes
        let planned = due ? planDueReminders(jobs: jobs, minutes: minutes) : []

        center.getPendingNotificationRequests { pending in
            let ours = pending.map(\.identifier).filter { $0.hasPrefix("reminder-") || $0 == nudgeId }
            center.removePendingNotificationRequests(withIdentifiers: ours)
            for reminder in planned {
                let content = UNMutableNotificationContent()
                content.title = reminder.title
                content.body = reminder.body
                content.sound = .default
                let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: reminder.date)
                let trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)
                center.add(UNNotificationRequest(identifier: reminder.id, content: content, trigger: trigger))
            }
            if nudge {
                let content = UNMutableNotificationContent()
                content.title = "Got a few minutes?"
                content.body = "Tap to draw a job."
                content.sound = .default
                content.userInfo = ["url": "jobjar://draw"]
                let trigger = UNCalendarNotificationTrigger(
                    dateMatching: DateComponents(hour: minutes / 60, minute: minutes % 60), repeats: true
                )
                center.add(UNNotificationRequest(identifier: nudgeId, content: content, trigger: trigger))
            }
        }
    }

    /// Asks once; later calls just report the answer.
    static func requestPermission(_ done: @escaping (Bool) -> Void) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, _ in
            DispatchQueue.main.async { done(granted) }
        }
    }
}

/// Shows reminders while the app is open, and opens a notification's link (the nudge's
/// `jobjar://draw`) when it's tapped.
final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationDelegate()

    func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if let link = response.notification.request.content.userInfo["url"] as? String, let url = URL(string: link) {
            DispatchQueue.main.async { UIApplication.shared.open(url) }
        }
        completionHandler()
    }
}
