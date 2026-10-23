import Foundation

enum JobSortOrder: String, CaseIterable, Identifiable {
    case timeAsc, timeDesc, priority, newest, category

    var id: String { rawValue }

    var label: String {
        switch self {
        case .timeAsc: return "Quickest first"
        case .timeDesc: return "Longest first"
        case .priority: return "Priority"
        case .newest: return "Newest"
        case .category: return "Category"
        }
    }
}

/// Tabs on the Jobs screen. Scheduled jobs get their own tab since they're neither to-do nor done.
enum JobsView: String, CaseIterable, Identifiable {
    case active, completed, scheduled

    var id: String { rawValue }

    var label: String {
        switch self {
        case .active: return "To do"
        case .completed: return "Done"
        case .scheduled: return "Scheduled"
        }
    }
}

/// One row: a job or a subtask (subtasks are listed too so they can be found and filtered).
struct JobListItem: Identifiable {
    let id: UUID
    let job: Job
    let displayMinutes: Int
    var parentTitle: String? = nil
    var subtaskDone: Int = 0
    var subtaskTotal: Int = 0
    var waitingOnTitle: String? = nil

    var isSubtask: Bool { job.parentId != nil }
}

@Observable
final class JobListViewModel {
    var view: JobsView = .active
    /// Empty = all.
    var selectedCategories: Set<String> = []
    var showRepeatingOnly = false
    var showInProgressOnly = false
    var sortOrder: JobSortOrder = .newest
    var searchQuery = ""
    /// Parents whose subtasks are showing.
    var expandedParentIds: Set<UUID> = []

    var hasActiveFilters: Bool { !selectedCategories.isEmpty || showRepeatingOnly || showInProgressOnly }

    func toggleCategory(_ category: String) {
        if selectedCategories.contains(category) {
            selectedCategories.remove(category)
        } else {
            selectedCategories.insert(category)
        }
    }

    func toggleExpanded(_ parentId: UUID) {
        if expandedParentIds.contains(parentId) {
            expandedParentIds.remove(parentId)
        } else {
            expandedParentIds.insert(parentId)
        }
    }

    var emptyStateText: String {
        let query = searchQuery.trimmingCharacters(in: .whitespaces)
        if !query.isEmpty { return "Nothing matches \"\(query)\"." }
        if hasActiveFilters { return "Nothing matches those filters." }
        switch view {
        case .scheduled: return "Nothing scheduled."
        case .completed: return "Nothing done yet."
        case .active: return "The jar's empty. Tap + to add a job."
        }
    }

    /// Repeating jobs never stay done, so isPending() decides To do vs Done. The Repeating filter
    /// ignores that split and shows them all. Scheduled is just scheduledDate != nil.
    func items(allJobs: [Job]) -> [JobListItem] {
        let byId = Dictionary(allJobs.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let subtasksByParent = Dictionary(grouping: allJobs.filter { $0.parentId != nil }) { $0.parentId! }

        // Subtasks get their own rows too, same as the Jar and Stats count them.
        let mapped = allJobs.map { job -> JobListItem in
            if let parentId = job.parentId {
                let siblings = subtasksByParent[parentId] ?? []
                let siblingsById = Dictionary(siblings.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
                let waitingOn = job.isUnblocked(siblingsById: siblingsById) ? nil : job.dependsOnSubtaskId.flatMap { siblingsById[$0]?.title }
                return JobListItem(
                    id: job.id,
                    job: job,
                    displayMinutes: job.estimatedMinutes,
                    parentTitle: byId[parentId]?.title,
                    waitingOnTitle: waitingOn
                )
            }
            let subtasks = subtasksByParent[job.id] ?? []
            return JobListItem(
                id: job.id,
                job: job,
                displayMinutes: subtasks.isEmpty ? job.estimatedMinutes : job.remainingMinutes(subtasks: subtasks),
                subtaskDone: subtasks.filter { $0.isDone }.count,
                subtaskTotal: subtasks.count
            )
        }

        let query = searchQuery.trimmingCharacters(in: .whitespaces)
        let filtered = mapped.filter { item in
            let job = item.job
            if !selectedCategories.isEmpty && !selectedCategories.contains(job.category) { return false }
            if showRepeatingOnly && job.recurrenceDays == nil { return false }
            if showInProgressOnly && !job.isInProgress { return false }
            switch view {
            case .scheduled: if job.scheduledDate == nil { return false }
            case .active: if !showRepeatingOnly && !job.isPending() { return false }
            case .completed: if !showRepeatingOnly && job.isPending() { return false }
            }
            if !query.isEmpty {
                return job.title.localizedCaseInsensitiveContains(query) || job.notes.localizedCaseInsensitiveContains(query)
            }
            return true
        }

        // Subtasks sit under their parent. If the parent is filtered out, the subtask sorts like a
        // normal row.
        let visibleParentIds = Set(filtered.filter { !$0.isSubtask }.map(\.id))
        let subtasksByVisibleParent = Dictionary(
            grouping: filtered.filter { $0.job.parentId.map(visibleParentIds.contains) == true }
        ) { $0.job.parentId! }
            .mapValues { $0.sorted { $0.job.createdAt < $1.job.createdAt } }
        let topLevelAndStandalone = filtered.filter { $0.job.parentId.map(visibleParentIds.contains) != true }

        // Groups start collapsed, but searching or filtering opens them so matches aren't hidden.
        let narrowing = hasActiveFilters || !query.isEmpty

        return sortTopLevel(topLevelAndStandalone).flatMap { entry -> [JobListItem] in
            let subs = subtasksByVisibleParent[entry.id] ?? []
            if subs.isEmpty || narrowing || expandedParentIds.contains(entry.id) {
                return [entry] + subs
            }
            return [entry]
        }
    }

    private func sortTopLevel(_ entries: [JobListItem]) -> [JobListItem] {
        switch sortOrder {
        case .timeAsc: return entries.sorted { $0.displayMinutes < $1.displayMinutes }
        case .timeDesc: return entries.sorted { $0.displayMinutes > $1.displayMinutes }
        case .priority: return entries.sorted { $0.job.priority.ordinal > $1.job.priority.ordinal }
        case .newest: return entries.sorted { $0.job.createdAt > $1.job.createdAt }
        case .category: return entries.sorted { $0.job.category < $1.job.category }
        }
    }
}
