import SwiftData
import SwiftUI

/// Rows shown when the card is opened; the rest are on the See all page.
private let cardRows = 5

/// The slim line under the jar: "1 overdue · 1 today · 3 this week". Tap to open the first few
/// underneath. Starts closed every time, and isn't shown at all when nothing's due in the range.
struct ComingUpCard: View {
    let jobs: [Job]
    let rangeDays: Int
    let onOpenJob: (UUID) -> Void
    let onComplete: (Job) -> Void
    let onSeeAll: () -> Void

    @State private var expanded = false

    var body: some View {
        let items = comingUp(jobs, rangeDays: rangeDays).filter { $0.group != .later }
        if !items.isEmpty {
            let counts = comingUpCounts(items)
            VStack(spacing: 0) {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) { expanded.toggle() }
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "calendar.badge.clock")
                            .font(.subheadline)
                            .foregroundStyle(Color.accentColor)
                        Text(items.count == 1 ? "\(items[0].job.title) · \(formatDueStatus(items[0].job))" : summary(counts))
                            .font(.subheadline)
                            .lineLimit(1)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        if counts.overdue > 0 {
                            Circle()
                                .fill(.red)
                                .frame(width: 8, height: 8)
                                .accessibilityLabel("Something is overdue")
                        }
                        Image(systemName: expanded ? "chevron.up" : "chevron.down")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint(expanded ? "Hides coming up" : "Shows coming up")

                if expanded {
                    Divider()
                    VStack(spacing: 0) {
                        ForEach(items.prefix(cardRows)) { item in
                            ComingUpRow(
                                item: item,
                                showRule: false,
                                onOpen: { onOpenJob(item.job.id) },
                                onComplete: { onComplete(item.job) }
                            )
                        }
                        HStack {
                            Spacer()
                            Button(action: onSeeAll) {
                                Label("See all", systemImage: "chevron.right")
                                    .labelStyle(TrailingIconLabelStyle())
                                    .font(.subheadline)
                            }
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                    }
                }
            }
            .frame(maxWidth: .infinity)
            .cardStyle()
        }
    }

    /// "1 overdue · 1 today · 3 this week", leaving out whatever's zero.
    private func summary(_ counts: ComingUpCounts) -> String {
        var parts: [String] = []
        if counts.overdue > 0 { parts.append("\(counts.overdue) overdue") }
        if counts.today > 0 { parts.append("\(counts.today) today") }
        if counts.soon > 0 {
            parts.append(rangeDays == 7 ? "\(counts.soon) this week" : "\(counts.soon) in the next \(rangeDays) days")
        }
        return parts.joined(separator: " · ")
    }
}

/// Text, then the icon.
private struct TrailingIconLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 2) {
            configuration.title
            configuration.icon.font(.caption.weight(.semibold))
        }
    }
}

/// Tick, name (tap for the job), and when it's due.
private struct ComingUpRow: View {
    let item: ComingUpItem
    let showRule: Bool
    let onOpen: () -> Void
    let onComplete: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Button(action: onComplete) {
                Image(systemName: "circle")
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Mark complete")

            Button(action: onOpen) {
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.job.title).lineLimit(1)
                        if showRule {
                            Text(formatRecurrence(item.job))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Text(formatDueStatus(item.job))
                        .font(.caption)
                        .foregroundStyle(item.group == .overdue ? AnyShapeStyle(Color.red) : AnyShapeStyle(.secondary))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }
}

/// Every repeating job by when it's due: Overdue, Today, the next N days, then Later (folded
/// away). The range chips here also set the card's range.
struct ComingUpView: View {
    var onOpenJob: (UUID) -> Void

    @Environment(\.modelContext) private var modelContext
    @Environment(UndoCenter.self) private var undoCenter
    @Query(sort: \Job.createdAt) private var allJobs: [Job]
    @AppStorage(comingUpRangeKey) private var rangeDays = defaultComingUpRange
    @State private var laterOpen = false

    private var repository: JobRepository { JobRepository(modelContext: modelContext) }

    var body: some View {
        let range = comingUpRanges.contains(rangeDays) ? rangeDays : defaultComingUpRange
        let items = comingUp(allJobs, rangeDays: range)
        let byGroup = Dictionary(grouping: items, by: \.group)
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 8) {
                    ForEach(comingUpRanges, id: \.self) { days in
                        FilterChip(label: "\(days) days", selected: range == days) { rangeDays = days }
                    }
                }

                if items.isEmpty {
                    Text("No repeating jobs yet. Turn on Repeat when you add or edit a job.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else {
                    if byGroup.keys.allSatisfy({ $0 == .later }) {
                        Text("Nothing due in that time.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    ForEach(ComingUpGroup.allCases, id: \.self) { group in
                        if let groupItems = byGroup[group], !groupItems.isEmpty {
                            groupCard(group, items: groupItems, range: range)
                        }
                    }
                }
            }
            .padding(16)
        }
        .pageBackground()
        .navigationTitle("Coming up")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func title(_ group: ComingUpGroup, range: Int) -> String {
        switch group {
        case .overdue: return "Overdue"
        case .today: return "Today"
        case .soon: return "Next \(range) days"
        case .later: return "Later"
        }
    }

    private func groupCard(_ group: ComingUpGroup, items: [ComingUpItem], range: Int) -> some View {
        let foldable = group == .later
        return VStack(spacing: 0) {
            Button {
                if foldable { withAnimation(.easeInOut(duration: 0.2)) { laterOpen.toggle() } }
            } label: {
                HStack {
                    Text("\(title(group, range: range)) · \(items.count)")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(group == .overdue ? AnyShapeStyle(Color.red) : AnyShapeStyle(.primary))
                    Spacer()
                    if foldable {
                        Image(systemName: laterOpen ? "chevron.up" : "chevron.down")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!foldable)

            if !foldable || laterOpen {
                ForEach(items) { item in
                    ComingUpRow(
                        item: item,
                        showRule: true,
                        onOpen: { onOpenJob(item.job.id) },
                        onComplete: { complete(item.job) }
                    )
                }
            }
        }
        .frame(maxWidth: .infinity)
        .cardStyle()
    }

    private func complete(_ job: Job) {
        let title = job.title
        if let snapshot = repository.completeCycle(job) { undoCenter.offerCompleted(title, snapshot) }
    }
}
