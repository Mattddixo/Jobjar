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
                    StatCard(label: "To do", value: "\(stats.activeCount)")
                    StatCard(label: "Completed", value: "\(stats.completedCount)")
                    StatCard(label: "Time spent", value: formatMinutes(stats.totalMinutesCompleted))
                }

                Text("By category").font(.headline)

                if stats.categoryStats.isEmpty {
                    Text("Finish a few jobs and your stats will show up here.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else {
                    // Already sorted biggest first, so the first one sets the bar scale.
                    let maxMinutes = max(stats.categoryStats.first?.totalMinutes ?? 1, 1)
                    VStack(spacing: 8) {
                        ForEach(stats.categoryStats) { stat in
                            VStack(alignment: .leading, spacing: 8) {
                                HStack {
                                    Text(stat.category).font(.body)
                                    Spacer()
                                    Text("\(stat.completedCount) completed · \(formatMinutes(stat.totalMinutes))")
                                        .font(.subheadline)
                                }
                                ProgressView(value: Double(stat.totalMinutes), total: Double(maxMinutes))
                            }
                            .padding(16)
                            .cardStyle()
                        }
                    }
                }
            }
            .padding(16)
        }
        .pageBackground()
        .navigationTitle("Stats")
        .todayDateButton()
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
        .cardStyle()
    }
}

private struct StatsSummary {
    let activeCount: Int
    let completedCount: Int
    let totalMinutesCompleted: Int
    let categoryStats: [CategoryStat]
}

/// A finished job, or a repeating job's total.
private struct Contribution {
    let category: String
    let count: Int
    let minutes: Int
}

private func computeStats(allJobs: [Job]) -> StatsSummary {
    // Every job and subtask, same as the Jar counts.
    let activeCount = allJobs.filter { $0.isPending() }.count

    let parentIdsWithSubtasks = Set(allJobs.compactMap { $0.parentId })
    let repeatingParentIds = Set(allJobs.filter { $0.parentId == nil && $0.recurrenceDays != nil }.map { $0.id })

    // Parents with subtasks aren't counted themselves, their subtasks already cover that time.
    let oneOffCompleted = allJobs.filter { job in
        guard job.isDone else { return false }
        if let parentId = job.parentId {
            return !repeatingParentIds.contains(parentId)
        }
        return !parentIdsWithSubtasks.contains(job.id)
    }

    // Repeating jobs never stay done, so count them from completionCount.
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
