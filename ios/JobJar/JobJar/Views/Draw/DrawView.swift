import SwiftData
import SwiftUI

private let timePresets = [15, 30, 45, 60, 90, 120]

struct DrawView: View {
    var onOpenJob: (UUID) -> Void

    @Environment(\.modelContext) private var modelContext
    @Query(sort: \Job.createdAt, order: .reverse) private var allJobs: [Job]
    @State private var viewModel = DrawViewModel()
    @State private var showForceCompleteDialog = false

    private var repository: JobRepository { JobRepository(modelContext: modelContext) }

    private var categories: [String] {
        Array(Set(allJobs.map(\.category).filter { !$0.isEmpty })).sorted()
    }

    private var availableCount: Int { allJobs.filter { $0.isPending() }.count }
    private var completedCount: Int { allJobs.filter { !$0.isPending() }.count }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                JarSummaryCard(available: availableCount, completed: completedCount)

                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("Time available").font(.subheadline.weight(.semibold))
                        Spacer()
                        Text(viewModel.longJobsOnly ? "4+ hrs" : formatMinutes(viewModel.availableMinutes))
                            .font(.title3.weight(.semibold))
                    }
                    Slider(
                        value: Binding(
                            get: { Double(viewModel.availableMinutes) },
                            set: { viewModel.setAvailableMinutes(Int($0)) }
                        ),
                        in: 5...240
                    )
                    .disabled(viewModel.longJobsOnly)

                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(timePresets, id: \.self) { minutes in
                                FilterChip(
                                    label: formatMinutes(minutes),
                                    selected: !viewModel.longJobsOnly && viewModel.availableMinutes == minutes,
                                    action: { viewModel.setAvailableMinutes(minutes) }
                                )
                            }
                            // Not a ceiling like the other chips - an explicit "pull from the
                            // big projects" request, since the slider above can't reach past
                            // 4 hours.
                            FilterChip(
                                label: "4+ hrs",
                                selected: viewModel.longJobsOnly,
                                action: { viewModel.setLongJobsOnly() }
                            )
                        }
                    }

                    if !categories.isEmpty {
                        Divider()
                        Text("Category").font(.subheadline.weight(.semibold))
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 6) {
                                FilterChip(
                                    label: "Any",
                                    selected: viewModel.selectedCategory == nil,
                                    action: { viewModel.setCategory(nil) }
                                )
                                ForEach(categories, id: \.self) { category in
                                    FilterChip(
                                        label: category,
                                        selected: viewModel.selectedCategory == category,
                                        action: {
                                            viewModel.setCategory(viewModel.selectedCategory == category ? nil : category)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 16).fill(Color(.secondarySystemBackground)))

                Button(action: { viewModel.draw(repository: repository, allJobs: allJobs) }) {
                    Label("Draw a job", systemImage: "shuffle")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                }
                .buttonStyle(.borderedProminent)
                .disabled(viewModel.isDrawing)

                if let drawnJob = viewModel.drawnJob {
                    DrawnJobCard(
                        job: drawnJob,
                        context: viewModel.drawnContext,
                        isBusy: viewModel.isDrawing,
                        onOpen: { onOpenJob(drawnJob.id) },
                        onDone: {
                            let context = viewModel.drawnContext
                            let hasOpenSubtasks = (context?.subtaskTotal ?? 0) > (context?.subtaskDone ?? 0)
                            if hasOpenSubtasks {
                                showForceCompleteDialog = true
                            } else {
                                viewModel.completeDrawnJob(repository: repository, allJobs: allJobs)
                            }
                        },
                        onSkip: { viewModel.draw(repository: repository, allJobs: allJobs, excludeCurrent: true) }
                    )
                    .transition(.opacity)
                } else if viewModel.noMatchFound {
                    Text(
                        viewModel.longJobsOnly
                            ? "Nothing needs \(formatMinutes(longJobMinutes))+ yet. Try a shorter time, or add a bigger job."
                            : "No jobs fit that time and category. Try a longer time or add more jobs."
                    )
                    .font(.body)
                    .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    Text("Set your time and tap \"Draw a job\" to pick something from the jar.")
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(16)
            .animation(.easeInOut(duration: 0.2), value: viewModel.drawnJob?.id)
        }
        .navigationTitle("The Job Jar")
        .alert("Mark as done?", isPresented: $showForceCompleteDialog) {
            Button("Cancel", role: .cancel) {}
            Button("Mark done") {
                viewModel.completeDrawnJob(repository: repository, allJobs: allJobs)
            }
        } message: {
            let incomplete = (viewModel.drawnContext?.subtaskTotal ?? 0) - (viewModel.drawnContext?.subtaskDone ?? 0)
            Text("\(incomplete) subtask(s) are still open. They'll stay open, but this job will be marked done.")
        }
    }
}

private struct DrawnJobCard: View {
    let job: Job
    let context: DrawnJobContext?
    let isBusy: Bool
    let onOpen: () -> Void
    let onDone: () -> Void
    let onSkip: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(job.title).font(.title3.weight(.semibold))

            HStack(spacing: 8) {
                TimeBadge(minutes: context?.remainingMinutes ?? job.estimatedMinutes)
                if !job.category.isEmpty { CategoryBadge(category: job.category) }
                if let days = job.recurrenceDays { InfoBadge(text: formatRecurrenceInterval(days)) }
            }

            if let parentTitle = context?.parentTitle {
                Text("Part of: \(parentTitle)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            if let context, context.subtaskTotal > 0 {
                Text(
                    "\(context.subtaskDone)/\(context.subtaskTotal) subtasks done · " +
                        "\(formatMinutes(context.remainingMinutes ?? job.estimatedMinutes)) left of " +
                        "\(formatMinutes(job.estimatedMinutes)) total"
                )
                .font(.footnote)
                .foregroundStyle(.secondary)
            }

            if !job.notes.isEmpty {
                Text(job.notes).font(.body)
            }

            HStack(spacing: 8) {
                Button("Skip", action: onSkip)
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
                    .disabled(isBusy)
                Button("Mark done", action: onDone)
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
                    .disabled(isBusy)
            }

            Button("View details", action: onOpen)
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(.secondarySystemBackground)))
    }
}
