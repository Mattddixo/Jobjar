import SwiftData
import SwiftUI

struct JobDetailView: View {
    let jobId: UUID
    var onEdit: () -> Void
    var onAddSubtask: () -> Void
    var onOpenJob: (UUID) -> Void
    var onBack: () -> Void

    @Environment(\.modelContext) private var modelContext
    @Query private var allJobs: [Job]
    @State private var showDeleteDialog = false
    @State private var showForceCompleteDialog = false

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
                Text("Job not found.")
            }
        }
        .navigationTitle("Job details")
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
                    repository.deleteJob(job, allJobs: allJobs)
                }
                onBack()
            }
        } message: {
            if let job = currentJob {
                Text(
                    subtasks.isEmpty
                        ? "\"\(job.title)\" will be removed permanently."
                        : "\"\(job.title)\" and its \(subtasks.count) subtask(s) will be removed permanently."
                )
            }
        }
        .alert("Mark as done?", isPresented: $showForceCompleteDialog) {
            Button("Cancel", role: .cancel) {}
            Button("Mark done") {
                if let job = currentJob {
                    repository.toggleDone(job, allJobs: allJobs)
                }
            }
        } message: {
            Text("\(subtasks.filter { !$0.isDone }.count) subtask(s) are still open. They'll stay open, but this job will be marked done.")
        }
    }

    @ViewBuilder
    private func content(for job: Job) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(job.title).font(.title.weight(.bold))

                HStack(spacing: 8) {
                    let displayMinutes = subtasks.isEmpty ? job.estimatedMinutes : job.remainingMinutes(subtasks: subtasks)
                    TimeBadge(minutes: displayMinutes)
                    if !job.category.isEmpty { CategoryBadge(category: job.category) }
                    if let days = job.recurrenceDays { InfoBadge(text: formatRecurrenceInterval(days)) }
                }

                if !subtasks.isEmpty {
                    let remaining = job.remainingMinutes(subtasks: subtasks)
                    Text("\(formatMinutes(remaining)) left of \(formatMinutes(job.estimatedMinutes)) total")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                if job.recurrenceDays != nil {
                    let suffix = job.completionCount > 0 ? " · completed \(job.completionCount) time(s)" : ""
                    Text(formatDueStatus(job.nextDueAt) + suffix)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                if let parent {
                    Button("Part of: \(parent.title)") { onOpenJob(parent.id) }
                        .font(.footnote)
                }

                if let prerequisiteId = job.dependsOnSubtaskId,
                   let prerequisite = siblings.first(where: { $0.id == prerequisiteId }),
                   !prerequisite.isDone {
                    HStack(spacing: 6) {
                        Image(systemName: "lock.fill").foregroundStyle(.secondary)
                        Text("Waiting on: \(prerequisite.title)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                Text("Priority: \(job.priority.displayName)").font(.body)

                if !job.notes.isEmpty {
                    Text(job.notes).font(.body)
                }

                if job.timesDrawn > 0 {
                    Text("Drawn from the jar \(job.timesDrawn) time(s)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Button(action: {
                    let incompleteSubtaskCount = subtasks.filter { !$0.isDone }.count
                    if job.isPending() && incompleteSubtaskCount > 0 {
                        showForceCompleteDialog = true
                    } else {
                        repository.toggleDone(job, allJobs: allJobs)
                    }
                }) {
                    Text(buttonLabel(for: job))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(.borderedProminent)

                if job.parentId == nil {
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

    private func buttonLabel(for job: Job) -> String {
        if job.isPending() { return "Mark as done" }
        if job.recurrenceDays != nil { return "Make available now" }
        return "Mark as not done"
    }
}
