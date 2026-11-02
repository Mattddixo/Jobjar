import Foundation
import SwiftData
import WidgetKit

/// Keeps the home screen widgets up to date: writes what they show and asks them to reload.
enum WidgetSync {
    @MainActor
    static func update(modelContext: ModelContext, now: Date = .now, defaults: UserDefaults = .standard) {
        let jobs = (try? modelContext.fetch(FetchDescriptor<Job>())) ?? []
        guard snapshot(jobs: jobs, now: now, defaults: defaults).save() else { return }
        WidgetCenter.shared.reloadAllTimelines()
    }

    static func snapshot(jobs: [Job], now: Date = .now, defaults: UserDefaults = .standard, calendar: Calendar = .current) -> WidgetSnapshot {
        let range = defaults.object(forKey: comingUpRangeKey) as? Int ?? defaultComingUpRange
        // A few days past the range, since the widget keeps showing this across the next midnights.
        let due = comingUp(jobs, rangeDays: range, now: now, calendar: calendar)
            .filter { $0.daysUntil <= range + 3 }
            .prefix(12)
            .map { item in
                WidgetSnapshot.DueJob(
                    id: item.job.id,
                    title: item.job.title,
                    dueDay: item.job.nextDueAt.map { calendar.startOfDay(for: $0) }
                )
            }
        return WidgetSnapshot(
            inJar: jobs.filter { $0.isAvailableToDraw(now: now) }.count,
            comingUp: Array(due),
            rangeDays: range
        )
    }
}
