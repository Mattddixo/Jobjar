import Foundation

enum JobSortOrder: String, CaseIterable, Identifiable {
    case timeAsc, timeDesc, priority, newest, category

    var id: String { rawValue }

    var label: String {
        switch self {
        case .timeAsc: return "Shortest first"
        case .timeDesc: return "Longest first"
        case .priority: return "Priority"
        case .newest: return "Newest"
        case .category: return "Category"
        }
    }
}

/// A top-level job plus how many of its subtasks (if any) are done, for the list row's badge.
struct JobListItem: Identifiable {
    let job: Job
    var id: UUID { job.id }
    let displayMinutes: Int
    let subtaskDone: Int
    let subtaskTotal: Int
    /// "Weekly" etc, or nil if this job doesn't repeat.
    let recurrenceLabel: String?
    /// "Due now" / "Next: in 3 days", or nil if this job doesn't repeat.
    let dueStatus: String?
}

@Observable
final class JobListViewModel {
    var showCompleted = false
    var selectedCategory: String? = nil
    var showRepeatingOnly = false
    var sortOrder: JobSortOrder = .newest

    /// A repeating job never persists isDone, so "completed" for it means "resting until its
    /// next cycle" - isPending() (not isDone) is what decides Active vs Completed for it, and
    /// for everything else it's equivalent to !isDone. When filtering to repeating jobs only,
    /// the Active/Completed split is bypassed entirely and both due and resting ones show
    /// together, so this is the one place you can see the full set regardless of state.
    func items(allJobs: [Job]) -> [JobListItem] {
        let subtasksByParent = Dictionary(grouping: allJobs.filter { $0.parentId != nil }) { $0.parentId! }
        let topLevel = allJobs.filter { $0.parentId == nil }

        let mapped = topLevel.map { job -> JobListItem in
            let subtasks = subtasksByParent[job.id] ?? []
            return JobListItem(
                job: job,
                displayMinutes: subtasks.isEmpty ? job.estimatedMinutes : job.remainingMinutes(subtasks: subtasks),
                subtaskDone: subtasks.filter { $0.isDone }.count,
                subtaskTotal: subtasks.count,
                recurrenceLabel: job.recurrenceDays.map(formatRecurrenceInterval),
                dueStatus: job.recurrenceDays != nil ? formatDueStatus(job.nextDueAt) : nil
            )
        }

        let filtered = mapped
            .filter { selectedCategory == nil || $0.job.category == selectedCategory }
            .filter { !showRepeatingOnly || $0.job.recurrenceDays != nil }
            .filter { showRepeatingOnly || $0.job.isPending() != showCompleted }

        switch sortOrder {
        case .timeAsc: return filtered.sorted { $0.displayMinutes < $1.displayMinutes }
        case .timeDesc: return filtered.sorted { $0.displayMinutes > $1.displayMinutes }
        case .priority: return filtered.sorted { $0.job.priority.ordinal > $1.job.priority.ordinal }
        case .newest: return filtered.sorted { $0.job.createdAt > $1.job.createdAt }
        case .category: return filtered.sorted { $0.job.category < $1.job.category }
        }
    }
}
