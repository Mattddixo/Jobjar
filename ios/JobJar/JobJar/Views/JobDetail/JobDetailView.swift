import SwiftData
import SwiftUI

struct JobDetailView: View {
    let jobId: UUID
    var onEdit: () -> Void
    var onAddSubtask: () -> Void
    var onOpenJob: (UUID) -> Void
    var onBack: () -> Void

    @Environment(\.modelContext) private var modelContext
    @Environment(UndoCenter.self) private var undoCenter
    @Query private var allJobs: [Job]
    @State private var showDeleteDialog = false
    @State private var showForceCompleteDialog = false
    @State private var jobToSchedule: Job? = nil

    private var repository: JobRepository { JobRepository(modelContext: modelContext) }
    private var currentJob: Job? { allJobs.first { $0.id == jobId } }
    private var subtasks: [Job] { allJobs.filter { $0.parentId == jobId } }
    private var siblings: [Job] {
        guard let parentId = currentJob?.parentId else { return [] }
        return allJobs.filter { $0.parentId == parentId }
    }
    private var parent: Job? {
        guard let parentId = currentJob?.parentId else { return nil }
        return allJobs.first { $0.id == parentId }
    }

    var body: some View {
        Group {
            if let job = currentJob {
                content(for: job)
            } else {
                Text("This job has been deleted.")
            }
        }
        .pageBackground()
        .navigationTitle("Job")
        .navigationBarTitleDisplayMode(.inline)
        .scheduleSheet(for: $jobToSchedule) { job, date in repository.scheduleJob(job, at: date) }
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: onEdit) { Image(systemName: "pencil") }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(role: .destructive, action: { showDeleteDialog = true }) { Image(systemName: "trash") }
            }
        }
        .alert("Delete job?", isPresented: $showDeleteDialog) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                if let job = currentJob {
                    let title = job.title
                    let snapshot = repository.deleteJob(job, allJobs: allJobs)
                    undoCenter.offerDeleted(title, snapshot)
                }
                onBack()
            }
        } message: {
            if let job = currentJob {
                Text(
                    subtasks.isEmpty
                        ? "\(job.title) will be deleted."
                        : "\(job.title) and its \(countLabel(subtasks.count, "subtask", "subtasks")) will be deleted."
                )
            }
        }
        .alert("Mark as done?", isPresented: $showForceCompleteDialog) {
            Button("Cancel", role: .cancel) {}
            Button("Mark done") {
                if let job = currentJob { toggleDone(job) }
            }
        } message: {
            let open = subtasks.filter { !$0.isDone }.count
            Text(forceCompleteMessage(openSubtasks: open))
        }
    }

    @ViewBuilder
    private func content(for job: Job) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(job.title).font(.title.weight(.bold))

                // Chevron so it reads as a link to the parent.
                if let parent {
                    Button { onOpenJob(parent.id) } label: {
                        HStack(spacing: 2) {
                            Text("Part of \(parent.title)")
                            Image(systemName: "chevron.right").font(.caption.weight(.semibold))
                        }
                    }
                    .font(.subheadline)
                }

                FlowLayout(spacing: 8) {
                    if job.isInProgress { InProgressBadge() }
                    let displayMinutes = subtasks.isEmpty ? job.estimatedMinutes : job.remainingMinutes(subtasks: subtasks)
                    TimeBadge(minutes: displayMinutes)
                    if !job.category.isEmpty { CategoryBadge(category: job.category) }
                    if let days = job.recurrenceDays { InfoBadge(text: formatRecurrence(days: days, weekdays: job.repeatWeekdays)) }
                }

                if !subtasks.isEmpty {
                    let remaining = job.remainingMinutes(subtasks: subtasks)
                    Text("\(formatMinutes(remaining)) left of \(formatMinutes(job.estimatedMinutes)) total")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                if job.recurrenceDays != nil {
                    let suffix = job.completionCount == 0 ? ""
                        : job.completionCount == 1 ? " · done once" : " · done \(job.completionCount) times"
                    Text(formatDueStatus(job.nextDueAt) + suffix)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                if let scheduled = job.scheduledDate {
                    Label("Scheduled \(formatScheduledDateTime(scheduled))", systemImage: "calendar")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                if let prerequisiteId = job.dependsOnSubtaskId,
                   let prerequisite = siblings.first(where: { $0.id == prerequisiteId }),
                   !prerequisite.isDone {
                    HStack(spacing: 6) {
                        Image(systemName: "lock.fill").foregroundStyle(.secondary)
                        Text("Waiting on \(prerequisite.title)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                Text("\(job.priority.displayName) priority").font(.body)

                if !job.notes.isEmpty {
                    Text(job.notes).font(.body)
                }

                if job.timesDrawn > 0 {
                    Text(job.timesDrawn == 1 ? "Drawn from the jar once" : "Drawn from the jar \(job.timesDrawn) times")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                // Nothing to start on a done job or a resting repeating one.
                if job.isPending() {
                    HStack(spacing: 10) {
                        Button { repository.toggleInProgress(job) } label: {
                            Label(job.isInProgress ? "Put back in jar" : "Start", systemImage: job.isInProgress ? "arrow.uturn.backward" : "play.fill")
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 6)
                        }
                        // Repeating jobs can't be scheduled, so Start gets the full width.
                        if job.recurrenceDays == nil {
                            Button {
                                if job.scheduledDate != nil {
                                    repository.unscheduleJob(job)
                                } else {
                                    jobToSchedule = job
                                }
                            } label: {
                                Label(
                                    job.scheduledDate != nil ? "Unschedule" : "Schedule",
                                    systemImage: job.scheduledDate != nil ? "calendar.badge.minus" : "calendar.badge.plus"
                                )
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 6)
                            }
                        }
                    }
                    .buttonStyle(.bordered)
                }

                Button(action: {
                    let incompleteSubtaskCount = subtasks.filter { !$0.isDone }.count
                    if job.isPending() && incompleteSubtaskCount > 0 {
                        showForceCompleteDialog = true
                    } else {
                        toggleDone(job)
                    }
                }) {
                    Text(buttonLabel(for: job))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(.borderedProminent)

                if job.parentId == nil && job.recurrenceDays == nil {
                    Divider()
                    SubtasksSection(
                        parentId: job.id,
                        parentEstimatedMinutes: job.estimatedMinutes,
                        onOpenSubtask: onOpenJob,
                        onAddSubtask: onAddSubtask
                    )
                }
            }
            .padding(20)
        }
    }

    /// Only completing offers Undo. Reopening is its own undo.
    private func toggleDone(_ job: Job) {
        let completing = job.isPending()
        let title = job.title
        let snapshot = repository.toggleDone(job, allJobs: allJobs)
        if completing { undoCenter.offerCompleted(title, snapshot) }
    }

    private func buttonLabel(for job: Job) -> String {
        if job.isPending() { return "Mark done" }
        if job.recurrenceDays != nil { return "Do it now" }
        return "Mark not done"
    }
}
