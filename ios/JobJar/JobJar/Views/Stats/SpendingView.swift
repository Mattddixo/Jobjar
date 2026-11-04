import SwiftData
import SwiftUI

/// Categories show this many jobs until you ask for the rest.
private let itemsShown = 5

/// Stats > Spent: where the money went, all time or a year at a time. Categories biggest first,
/// each with its share and its jobs (top few, then Show all). Tap a job to open it. Same as
/// Android.
struct SpendingView: View {
    var onOpenJob: (UUID) -> Void

    @Query private var allJobs: [Job]
    @Query private var history: [Completion]
    /// Nil = all time.
    @State private var year: Int? = nil
    @State private var expanded: Set<String> = []

    var body: some View {
        let breakdown = spendingBreakdown(jobs: allJobs, completions: history, year: year)
        let jobCount = breakdown.groups.reduce(0) { $0 + $1.items.count }
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if breakdown.years.count > 1 || (breakdown.years.count == 1 && breakdown.undatedCents > 0) {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            periodChip("All time", selected: year == nil) { year = nil }
                            ForEach(breakdown.years, id: \.self) { y in
                                periodChip(String(y), selected: year == y) { year = y }
                            }
                        }
                    }
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text(formatCost(breakdown.totalCents)).font(.largeTitle.weight(.bold))
                    Text(summary(jobCount: jobCount))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    if year == nil, breakdown.undatedCents > 0, !breakdown.years.isEmpty {
                        Text("\(formatCost(breakdown.undatedCents)) of this is from repeat jobs finished before Job Jar kept dates, so it isn't in any year.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                if breakdown.groups.isEmpty {
                    Text("Nothing with a cost has been finished yet.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                ForEach(breakdown.groups) { group in
                    SpendingGroupCard(
                        group: group,
                        totalCents: breakdown.totalCents,
                        expanded: expanded.contains(group.category),
                        onToggle: {
                            if expanded.contains(group.category) {
                                expanded.remove(group.category)
                            } else {
                                expanded.insert(group.category)
                            }
                        },
                        onOpenJob: onOpenJob
                    )
                }
            }
            .padding(16)
        }
        .pageBackground()
        .navigationTitle("Spending")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func summary(jobCount: Int) -> String {
        let jobs = jobCount == 1 ? "1 job" : "\(jobCount) jobs"
        if let year { return "In \(String(year)), on \(jobs)" }
        return "All time, on \(jobs)"
    }

    private func periodChip(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        let fill: Color = selected ? Color.accentColor.opacity(0.18) : Color.secondary.opacity(0.12)
        let ink: Color = selected ? Color.accentColor : Color.primary
        return Button(action: action) {
            Text(label)
                .font(selected ? Font.subheadline.weight(.semibold) : Font.subheadline)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(fill, in: Capsule())
                .foregroundStyle(ink)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private struct SpendingGroupCard: View {
    let group: SpendingGroup
    let totalCents: Int
    let expanded: Bool
    let onToggle: () -> Void
    let onOpenJob: (UUID) -> Void

    private var share: Double { totalCents > 0 ? Double(group.cents) / Double(totalCents) : 0 }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(group.category).font(.headline).lineLimit(1)
                    Spacer()
                    Text(formatCost(group.cents)).font(.headline)
                }
                HStack(spacing: 10) {
                    ProgressView(value: share)
                    Text("\(Int((share * 100).rounded()))%")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)

            ForEach(expanded ? group.items : Array(group.items.prefix(itemsShown))) { item in
                Divider().padding(.leading, 16)
                Button {
                    onOpenJob(item.jobId)
                } label: {
                    SpendingRow(item: item)
                }
                .buttonStyle(.plain)
            }

            if group.items.count > itemsShown {
                Button(expanded ? "Show fewer" : "Show all \(group.items.count)", action: onToggle)
                    .font(.subheadline)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
            }
        }
        .cardStyle()
    }
}

private struct SpendingRow: View {
    let item: SpendingItem

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title).lineLimit(1)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            Text(formatCost(item.cents))
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }

    private var detail: String {
        let date = item.lastDoneAt.map(doneDate)
        let done: String
        switch (item.times, date) {
        case (2..., let date?): done = "Done \(item.times) times, last \(date)"
        case (2..., nil): done = "Done \(item.times) times"
        case (_, let date?): done = "Done \(date)"
        default: done = "Done"
        }
        return item.parentTitle.map { "Part of \($0) · \(done)" } ?? done
    }

    /// "Oct 12" this year, "Oct 12, 2024" before.
    private func doneDate(_ date: Date) -> String {
        let sameYear = Calendar.current.isDate(date, equalTo: .now, toGranularity: .year)
        let monthDay = Date.FormatStyle.dateTime.month(.abbreviated).day()
        return date.formatted(sameYear ? monthDay : monthDay.year())
    }
}
