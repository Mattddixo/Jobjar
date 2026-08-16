import SwiftData
import SwiftUI

struct CategoryStat: Identifiable {
    let category: String
    let completedCount: Int
    let totalMinutes: Int
    var id: String { category }
}

struct StatsView: View {
    @Query private var allJobs: [Job]

    private var stats: StatsSummary { computeStats(allJobs: allJobs) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 12) {
                    StatCard(label: "Active jobs", value: "\(stats.activeCount)")
                    StatCard(label: "Completed", value: "\(stats.completedCount)")
                    StatCard(label: "Time invested", value: formatMinutes(stats.totalMinutesCompleted))
                }

                Text("By category").font(.headline)

                if stats.categoryStats.isEmpty {
                    Text("Complete a few jobs to see your stats here.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else {
                    // Sorted descending by totalMinutes already, so the first entry is the max -
                    // used to scale each bar relative to your biggest time sink.
                    let maxMinutes = max(stats.categoryStats.first?.totalMinutes ?? 1, 1)
                    VStack(spacing: 8) {
                        ForEach(stats.categoryStats) { stat in
                            VStack(alignment: .leading, spacing: 8) {
                                HStack {
                                    Text(stat.category).font(.body)
                                    Spacer()
                                    Text("\(stat.completedCount) done · \(formatMinutes(stat.totalMinutes))")
                                        .font(.subheadline)
                                }
                                ProgressView(value: Double(stat.totalMinutes), total: Double(maxMinutes))
                            }
                            .padding(16)
                            .background(RoundedRectangle(cornerRadius: 16).fill(Color(.secondarySystemBackground)))
                        }
                    }
                }
            }
            .padding(16)
        }
        .navigationTitle("Stats")
    }
}

private struct StatCard: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(value).font(.title2.weight(.bold))
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(.secondarySystemBackground)))
    }
}

private struct StatsSummary {
    let activeCount: Int
    let completedCount: Int
    let totalMinutesCompleted: Int
    let categoryStats: [CategoryStat]
}

/// One unit of credited work: either a single one-off completion, or a repeating job's tally.
private struct Contribution {
    let category: String
    let count: Int
    let minutes: Int
}

private func computeStats(allJobs: [Job]) -> StatsSummary {
    // Every job and subtask alike, same population the Jar meter's "available" count uses -
    // a standalone open subtask is just as much active work as a top-level job.
    let activeCount = allJobs.filter { $0.isPending() }.count

    let parentIdsWithSubtasks = Set(allJobs.compactMap { $0.parentId })
    let repeatingParentIds = Set(allJobs.filter { $0.parentId == nil && $0.recurrenceDays != nil }.map { $0.id })

    // A parent with subtasks is a container, not a unit of work itself - its subtasks already
    // account for that time, so counting the parent too would double up every minute they
    // cover. So: count completed jobs that represent real, granular effort - plain top-level
    // jobs with no subtasks, and subtasks of a *non-repeating* parent. Subtasks of a
    // *repeating* parent are excluded here even when currently done, because cycling resets
    // them back to not-done on every cycle - crediting them individually would make stats
    // swing backward each time that reset happens. Their work is credited once per cycle via
    // the parent's own completionCount instead.
    let oneOffCompleted = allJobs.filter { job in
        guard job.isDone else { return false }
        if let parentId = job.parentId {
            return !repeatingParentIds.contains(parentId)
        }
        return !parentIdsWithSubtasks.contains(job.id)
    }

    // A repeating job never stays isDone = true, so its completions would otherwise be
    // invisible to stats entirely. completionCount is the only record of how many times it's
    // actually been done.
    let repeatingCompletions = allJobs.filter { $0.recurrenceDays != nil && $0.completionCount > 0 }

    let contributions =
        oneOffCompleted.map {
            Contribution(category: $0.category.isEmpty ? "Uncategorized" : $0.category, count: 1, minutes: $0.estimatedMinutes)
        } +
        repeatingCompletions.map {
            Contribution(
                category: $0.category.isEmpty ? "Uncategorized" : $0.category,
                count: $0.completionCount,
                minutes: $0.completionCount * $0.estimatedMinutes
            )
        }

    let grouped = Dictionary(grouping: contributions) { $0.category }
    let categoryStats = grouped
        .map { category, items in
            CategoryStat(
                category: category,
                completedCount: items.reduce(0) { $0 + $1.count },
                totalMinutes: items.reduce(0) { $0 + $1.minutes }
            )
        }
        .sorted { $0.totalMinutes > $1.totalMinutes }

    return StatsSummary(
        activeCount: activeCount,
        completedCount: contributions.reduce(0) { $0 + $1.count },
        totalMinutesCompleted: contributions.reduce(0) { $0 + $1.minutes },
        categoryStats: categoryStats
    )
}
