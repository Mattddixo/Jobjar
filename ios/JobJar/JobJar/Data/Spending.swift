import Foundation

/// One job's spending in the chosen period.
struct SpendingItem: Identifiable, Equatable {
    let jobId: UUID
    let title: String
    /// Set for a subtask, so it's clear where it belongs.
    let parentTitle: String?
    let category: String
    let cents: Int
    /// How many times its cost was spent: 1 for a one-off, every finish for a repeating job.
    let times: Int
    /// The latest finish in the period, if known.
    let lastDoneAt: Date?

    var id: UUID { jobId }
}

/// A category's spending, its jobs biggest first.
struct SpendingGroup: Identifiable, Equatable {
    let category: String
    let cents: Int
    let items: [SpendingItem]

    var id: String { category }
}

struct SpendingBreakdown: Equatable {
    let totalCents: Int
    /// Biggest first.
    let groups: [SpendingGroup]
    /// Years with dated spending, newest first.
    let years: [Int]
    /// Spending with no date (repeating finishes from before history was kept). All time only.
    let undatedCents: Int
}

/// One time a job's cost was spent, with its date when known.
private struct Spend {
    let job: Job
    let at: Date?
}

/// Where the money went, all time or in one `year`. Counts the same as the Spent total on Stats:
/// a finished job or subtask's cost once, a repeating job's cost every time it's finished, using
/// each job's current cost. Dates come from when a one-off was finished and from the history for
/// repeating jobs; finishes from before the history started count in All time only. Same as
/// Android.
func spendingBreakdown(jobs: [Job], completions: [Completion], year: Int? = nil, calendar: Calendar = .current) -> SpendingBreakdown {
    let byId = Dictionary(jobs.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    let repeatingParentIds = Set(jobs.filter { $0.parentId == nil && $0.recurrenceDays != nil }.map(\.id))
    var finishesByJob: [UUID: [Date]] = [:]
    for completion in completions {
        guard let jobId = completion.jobId else { continue }
        finishesByJob[jobId, default: []].append(completion.completedAt)
    }

    var spends: [Spend] = []
    for job in jobs {
        let cost = job.costCents ?? 0
        guard cost > 0 else { continue }
        if job.recurrenceDays != nil {
            let dated = (finishesByJob[job.id] ?? []).sorted(by: >).prefix(job.completionCount)
            spends += dated.map { Spend(job: job, at: $0) }
            spends += Array(repeating: Spend(job: job, at: nil), count: max(job.completionCount - dated.count, 0))
        } else if job.isDone, !(job.parentId.map { repeatingParentIds.contains($0) } ?? false) {
            // Subtasks of repeating parents only exist in old data and never counted.
            spends.append(Spend(job: job, at: job.completedAt))
        }
    }

    let inPeriod: [Spend]
    if let year {
        inPeriod = spends.filter { spend in spend.at.map { calendar.component(.year, from: $0) == year } ?? false }
    } else {
        inPeriod = spends
    }

    let byJob: [UUID: [Spend]] = Dictionary(grouping: inPeriod, by: { $0.job.id })
    let items: [SpendingItem] = byJob.values.compactMap { (list: [Spend]) -> SpendingItem? in
        guard let job = list.first?.job else { return nil }
        return SpendingItem(
            jobId: job.id,
            title: job.title,
            parentTitle: job.parentId.flatMap { byId[$0]?.title },
            category: job.category.isEmpty ? "Uncategorized" : job.category,
            cents: (job.costCents ?? 0) * list.count,
            times: list.count,
            lastDoneAt: list.compactMap { $0.at }.max()
        )
    }

    let byCategory: [String: [SpendingItem]] = Dictionary(grouping: items, by: { $0.category })
    var groups: [SpendingGroup] = []
    for (category, members) in byCategory {
        let sortedItems: [SpendingItem] = members.sorted(by: biggerItemFirst)
        let cents: Int = members.reduce(0) { $0 + $1.cents }
        groups.append(SpendingGroup(category: category, cents: cents, items: sortedItems))
    }
    groups.sort(by: biggerGroupFirst)

    let years: [Int] = Array(Set(spends.compactMap { spend -> Int? in
        guard let at = spend.at else { return nil }
        return calendar.component(.year, from: at)
    })).sorted(by: >)
    let undatedCents: Int = spends.filter { $0.at == nil }.reduce(0) { $0 + ($1.job.costCents ?? 0) }
    let totalCents: Int = groups.reduce(0) { $0 + $1.cents }

    return SpendingBreakdown(totalCents: totalCents, groups: groups, years: years, undatedCents: undatedCents)
}

/// Bigger amounts first, then by name.
private func biggerItemFirst(_ a: SpendingItem, _ b: SpendingItem) -> Bool {
    if a.cents != b.cents { return a.cents > b.cents }
    return a.title.localizedCaseInsensitiveCompare(b.title) == .orderedAscending
}

private func biggerGroupFirst(_ a: SpendingGroup, _ b: SpendingGroup) -> Bool {
    if a.cents != b.cents { return a.cents > b.cents }
    return a.category.localizedCaseInsensitiveCompare(b.category) == .orderedAscending
}
