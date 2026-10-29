import Foundation

/// Ranges offered for Coming up, in days.
let comingUpRanges = [7, 14, 30]
let defaultComingUpRange = 14

/// Settings > "Show Coming up on the Jar tab" and its range (UserDefaults keys).
let showComingUpKey = "coming_up_show"
let comingUpRangeKey = "coming_up_range"

enum ComingUpGroup: CaseIterable {
    case overdue, today, soon, later
}

struct ComingUpItem: Identifiable {
    let job: Job
    /// 0 = today (or due now), negative = overdue.
    let daysUntil: Int
    let group: ComingUpGroup

    var id: UUID { job.id }
}

/// Every repeating job, most urgent first: overdue, today, the next `rangeDays` days, then
/// later. A job that's simply due now counts as today.
func comingUp(_ jobs: [Job], rangeDays: Int, now: Date = .now, calendar: Calendar = .current) -> [ComingUpItem] {
    jobs.filter { $0.parentId == nil && $0.recurrenceDays != nil }
        .map { job in
            let days = job.daysUntilDue(now: now, calendar: calendar) ?? 0
            let group: ComingUpGroup
            if days < 0 {
                group = .overdue
            } else if days == 0 {
                group = .today
            } else if days <= rangeDays {
                group = .soon
            } else {
                group = .later
            }
            return ComingUpItem(job: job, daysUntil: days, group: group)
        }
        .sorted { a, b in
            if a.daysUntil != b.daysUntil { return a.daysUntil < b.daysUntil }
            let aDue = a.job.nextDueAt ?? .distantPast
            let bDue = b.job.nextDueAt ?? .distantPast
            if aDue != bDue { return aDue < bDue }
            return a.job.title.localizedCaseInsensitiveCompare(b.job.title) == .orderedAscending
        }
}

struct ComingUpCounts: Equatable {
    var overdue = 0
    var today = 0
    var soon = 0

    var total: Int { overdue + today + soon }
}

func comingUpCounts(_ items: [ComingUpItem]) -> ComingUpCounts {
    ComingUpCounts(
        overdue: items.filter { $0.group == .overdue }.count,
        today: items.filter { $0.group == .today }.count,
        soon: items.filter { $0.group == .soon }.count
    )
}
