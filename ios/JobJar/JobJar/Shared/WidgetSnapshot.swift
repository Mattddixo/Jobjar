import Foundation

/// What the home screen widgets show, written by the app into the shared app group folder
/// whenever it comes and goes. The widgets can't open the app's database, so this is all they see.
/// If the app group isn't available (some sideloading tools drop it), there's nothing to read and
/// the widgets show a placeholder instead.
struct WidgetSnapshot: Codable, Equatable {
    struct DueJob: Codable, Equatable {
        let id: UUID
        let title: String
        /// The day it's due (start of day). Nil means it's due now.
        let dueDay: Date?
    }

    /// How many jobs the jar could hand out when this was written.
    var inJar: Int
    /// Repeating jobs, most urgent first.
    var comingUp: [DueJob]
    /// Coming up's range in days, from Settings.
    var rangeDays: Int

    static let appGroup = "group.com.mattdixon.jobjar"

    static var fileURL: URL? {
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
            .appendingPathComponent("widget.json")
    }

    static func load() -> WidgetSnapshot? {
        guard let url = fileURL, let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(WidgetSnapshot.self, from: data)
    }

    /// False when there's no app group to write to.
    @discardableResult
    func save() -> Bool {
        guard let url = Self.fileURL, let data = try? JSONEncoder().encode(self) else { return false }
        if Self.load() == self { return true }
        return (try? data.write(to: url, options: .atomic)) != nil
    }

    /// Days from `now`'s day to the due day: negative when overdue, 0 when due today or now.
    static func daysUntil(_ job: DueJob, now: Date, calendar: Calendar = .current) -> Int {
        guard let dueDay = job.dueDay else { return 0 }
        return calendar.dateComponents([.day], from: calendar.startOfDay(for: now), to: calendar.startOfDay(for: dueDay)).day ?? 0
    }

    /// "Overdue 2 days", "Due now", "Due today", "Due tomorrow", "Due Fri", "Due Mar 3": as the app says it.
    static func dueText(_ job: DueJob, now: Date, calendar: Calendar = .current) -> String {
        guard let dueDay = job.dueDay else { return "Due now" }
        let days = daysUntil(job, now: now, calendar: calendar)
        switch days {
        case ..<0: return -days == 1 ? "Overdue 1 day" : "Overdue \(-days) days"
        case 0: return "Due today"
        case 1: return "Due tomorrow"
        case 2..<7: return "Due \(calendar.shortWeekdaySymbols[calendar.component(.weekday, from: dueDay) - 1])"
        default:
            let parts = calendar.dateComponents([.month, .day], from: dueDay)
            return "Due \(calendar.shortMonthSymbols[(parts.month ?? 1) - 1]) \(parts.day ?? 1)"
        }
    }
}
