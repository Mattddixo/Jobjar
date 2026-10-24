import SwiftData
import SwiftUI

private let timePresets = [15, 30, 45, 60, 90, 120, 150, 180, 210]

struct DrawView: View {
    var onOpenJob: (UUID) -> Void

    @Environment(\.modelContext) private var modelContext
    @Query(sort: \Job.createdAt, order: .reverse) private var allJobs: [Job]
    @State private var viewModel = DrawViewModel()
    @State private var jobToSchedule: Job? = nil

    private var repository: JobRepository { JobRepository(modelContext: modelContext) }

    private var categories: [String] {
        Array(Set(allJobs.map(\.category).filter { !$0.isEmpty })).sorted()
    }

    var body: some View {
        let availableIds = Set(allJobs.filter { $0.isAvailableToDraw() }.map(\.id))
        // Cards for jobs started, booked or deleted from another screen are skipped straight away
        // (a deleted job can't be read), then dropped from the list.
        let drawnJobs = viewModel.drawnJobs.filter { availableIds.contains($0.id) }
        ScrollView {
            VStack(spacing: 14) {
                JarSummaryCard(available: availableIds.count, inProgress: allJobs.filter(\.isInProgress).count)

                PickerPanel(viewModel: viewModel, categories: categories)

                Button(action: { viewModel.draw(repository: repository, allJobs: allJobs) }) {
                    Label("Draw a job", systemImage: "shuffle")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }
                .buttonStyle(.borderedProminent)

                if !drawnJobs.isEmpty {
                    DrawnJobsBatch(
                        entries: drawnJobs,
                        showBudgetSummary: !viewModel.longJobsOnly && viewModel.batchSize != .one,
                        availableMinutes: viewModel.availableMinutes,
                        remainingMinutes: viewModel.remainingMinutesAfterDraw,
                        onOpen: onOpenJob,
                        onStart: { job in viewModel.startJob(job, repository: repository) },
                        onSchedule: { job in jobToSchedule = job },
                        onRedraw: { viewModel.draw(repository: repository, allJobs: allJobs, excludeCurrent: true) },
                        onCloseJar: { viewModel.clearDraw() }
                    )
                    .transition(.opacity)
                } else if viewModel.noMatchFound {
                    EmptyStateText(
                        viewModel.longJobsOnly
                            ? "Nothing takes \(formatMinutes(longJobMinutes)) or more right now."
                            : "Nothing fits. Try more time or a different category."
                    )
                } else {
                    EmptyStateText("How much time have you got? Pick it above and draw.")
                }
            }
            .padding(16)
            .animation(.easeInOut(duration: 0.2), value: drawnJobs.isEmpty)
        }
        .pageBackground()
        .navigationTitle("Job Jar")
        .todayDateButton()
        .scheduleSheet(for: $jobToSchedule) { job, date in
            viewModel.scheduleJob(job, at: date, repository: repository)
        }
        .onChange(of: categories, initial: true) { _, current in viewModel.pruneCategories(to: current) }
        .onChange(of: availableIds) { viewModel.dropStaleCards(allJobs: allJobs) }
    }
}

private struct EmptyStateText: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
    }
}

/// Time, how many, and category, each as a chip that opens a menu.
private struct PickerPanel: View {
    let viewModel: DrawViewModel
    let categories: [String]

    private var categoryLabel: String {
        switch viewModel.selectedCategories.count {
        case 0: return "Any"
        case 1: return viewModel.selectedCategories.first!
        default: return "\(viewModel.selectedCategories.count) categories"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                SectionLabel("TIME")
                Spacer()
                MenuChip(
                    label: viewModel.longJobsOnly ? "4+ hrs" : formatMinutes(viewModel.availableMinutes),
                    selected: true
                ) {
                    ForEach(timePresets, id: \.self) { minutes in
                        Button(formatMinutes(minutes)) { viewModel.setAvailableMinutes(minutes) }
                    }
                    // Not a duration: switches to "4hr or longer" and the slider stops applying.
                    Button("4+ hrs") { viewModel.setLongJobsOnly() }
                }
            }
            Slider(
                value: Binding(
                    get: { Double(viewModel.availableMinutes) },
                    set: { viewModel.setAvailableMinutes(Int($0)) }
                ),
                in: 5...240
            )
            .disabled(viewModel.longJobsOnly)

            // 4+ hrs always draws one job, so "how many" only shows for normal draws.
            if !viewModel.longJobsOnly {
                HStack {
                    SectionLabel("HOW MANY")
                    Spacer()
                    MenuChip(label: viewModel.batchSize.label, selected: viewModel.batchSize != .one) {
                        ForEach(DrawBatchSize.allCases) { size in
                            Button(size.label) { viewModel.setBatchSize(size) }
                        }
                    }
                }
            }

            if !categories.isEmpty {
                Divider()
                HStack {
                    SectionLabel("CATEGORY")
                    Spacer()
                    MenuChip(label: categoryLabel, selected: !viewModel.selectedCategories.isEmpty, staysOpen: true) {
                        ForEach(categories, id: \.self) { category in
                            CheckedMenuItem(
                                title: category,
                                checked: viewModel.selectedCategories.contains(category),
                                action: { viewModel.toggleCategory(category) }
                            )
                        }
                    }
                }
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardStyle()
    }
}

private struct SectionLabel: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .tracking(0.8)
            .foregroundStyle(.secondary)
    }
}

/// The drawn jobs, with a "time spare" line when you asked for more than one, then Draw again /
/// Never mind. Drawing doesn't take anything out of the jar, so Never mind just clears the list.
private struct DrawnJobsBatch: View {
    let entries: [DrawnJobEntry]
    let showBudgetSummary: Bool
    let availableMinutes: Int
    let remainingMinutes: Int
    let onOpen: (UUID) -> Void
    let onStart: (Job) -> Void
    let onSchedule: (Job) -> Void
    let onRedraw: () -> Void
    let onCloseJar: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if showBudgetSummary {
                Text("\(countLabel(entries.count, "job", "jobs")) · \(formatMinutes(remainingMinutes)) spare of \(formatMinutes(availableMinutes))")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ForEach(entries) { entry in
                BatchJobCard(
                    entry: entry,
                    onOpen: { onOpen(entry.id) },
                    onStart: { onStart(entry.job) },
                    onSchedule: { onSchedule(entry.job) }
                )
            }
            HStack(spacing: 10) {
                Button(action: onRedraw) {
                    Text("Draw again").frame(maxWidth: .infinity)
                }
                Button(action: onCloseJar) {
                    Text("Never mind").frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.bordered)
            .font(.subheadline)
        }
    }
}

/// A drawn job, on the honey card. Tap for details. Actions are Start and Schedule; finishing
/// happens later from the job itself. Notes are left off to keep cards a predictable size.
private struct BatchJobCard: View {
    let entry: DrawnJobEntry
    let onOpen: () -> Void
    let onStart: () -> Void
    let onSchedule: () -> Void

    var body: some View {
        let job = entry.job
        let context = entry.context
        VStack(alignment: .leading, spacing: 6) {
            Button(action: onOpen) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(job.title)
                        .font(.headline)
                        .lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    HStack(spacing: 6) {
                        TimeBadge(minutes: context.remainingMinutes ?? job.estimatedMinutes, compact: true)
                        if !job.category.isEmpty { CategoryBadge(category: job.category, compact: true) }
                        if let days = job.recurrenceDays {
                            InfoBadge(text: formatRecurrence(days: days, weekdays: job.repeatWeekdays), compact: true)
                        }
                    }
                    if let parentTitle = context.parentTitle {
                        Text("Part of \(parentTitle)").font(.caption).lineLimit(1)
                    }
                    if context.subtaskTotal > 0 {
                        Text("\(context.subtaskDone) of \(context.subtaskTotal) subtasks completed · \(formatMinutes(context.remainingMinutes ?? job.estimatedMinutes)) left")
                            .font(.caption)
                            .lineLimit(1)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            HStack(spacing: 8) {
                Button(action: onStart) {
                    Text("Start").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Palette.honey)
                .foregroundStyle(Palette.onHoney)
                // No scheduling for repeating jobs.
                if job.recurrenceDays == nil {
                    Button(action: onSchedule) {
                        Text("Schedule").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .tint(Palette.honey)
                }
            }
            .font(.subheadline.weight(.medium))
            .padding(.top, 4)
        }
        .foregroundStyle(Palette.onDrawnCard)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color("DrawnCard")))
    }
}
