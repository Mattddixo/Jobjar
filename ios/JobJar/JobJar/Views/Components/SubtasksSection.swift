import SwiftData
import SwiftUI

/// The "Subtasks" list, progress summary, and add-subtask button for a top-level job. Shared
/// by the job detail screen and the job creation/edit screen so subtask management looks and
/// works identically everywhere it appears.
///
/// `parentId` must refer to a job that is itself NOT a subtask (subtasks are one level deep) -
/// callers are responsible for that check, since this view has no way to verify it.
struct SubtasksSection: View {
    let parentId: UUID
    let parentEstimatedMinutes: Int
    var onOpenSubtask: (UUID) -> Void
    var onAddSubtask: () -> Void

    @Environment(\.modelContext) private var modelContext
    @Query private var allJobs: [Job]

    private var repository: JobRepository { JobRepository(modelContext: modelContext) }
    private var subtasks: [Job] {
        allJobs.filter { $0.parentId == parentId }.sorted { $0.createdAt < $1.createdAt }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Subtasks").font(.headline)
                Spacer()
                if !subtasks.isEmpty {
                    Text("\(subtasks.filter { $0.isDone }.count)/\(subtasks.count) done")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }

            if subtasks.isEmpty {
                Text("Break this job into smaller pieces you can draw on their own.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                let remaining = remainingMinutesOf(estimatedMinutes: parentEstimatedMinutes, subtasks: subtasks)
                Text("\(formatMinutes(remaining)) left of \(formatMinutes(parentEstimatedMinutes)) total")
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                let siblingsById = Dictionary(uniqueKeysWithValues: subtasks.map { ($0.id, $0) })
                VStack(spacing: 8) {
                    ForEach(subtasks) { subtask in
                        SubtaskRow(
                            subtask: subtask,
                            waitingOnTitle: waitingOnTitle(for: subtask, siblingsById: siblingsById),
                            onTap: { onOpenSubtask(subtask.id) },
                            onToggleDone: { repository.toggleDone(subtask, allJobs: allJobs) }
                        )
                    }
                }
            }

            Button(action: onAddSubtask) {
                Label("Add subtask", systemImage: "plus")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
            }
            .buttonStyle(.bordered)
        }
    }

    /// Non-nil (blocked) only when the dependency isn't satisfied yet - a soft block: the
    /// checkbox below stays fully live either way, this only changes the draw pool.
    private func waitingOnTitle(for subtask: Job, siblingsById: [UUID: Job]) -> String? {
        guard let prerequisiteId = subtask.dependsOnSubtaskId, !subtask.isUnblocked(siblingsById: siblingsById) else {
            return nil
        }
        return siblingsById[prerequisiteId]?.title
    }
}

private struct SubtaskRow: View {
    let subtask: Job
    let waitingOnTitle: String?
    let onTap: () -> Void
    let onToggleDone: () -> Void

    private var blocked: Bool { waitingOnTitle != nil }

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 8) {
                Button(action: onToggleDone) {
                    Image(systemName: subtask.isDone ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(subtask.isDone ? Color.accentColor : Color.secondary)
                }
                .buttonStyle(.plain)

                VStack(alignment: .leading, spacing: 4) {
                    Text(subtask.title)
                        .font(.body)
                        .foregroundStyle(blocked ? .secondary : .primary)
                    HStack(spacing: 6) {
                        TimeBadge(minutes: subtask.estimatedMinutes)
                        if let waitingOnTitle {
                            Image(systemName: "lock.fill")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                            Text("Waiting on: \(waitingOnTitle)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .foregroundStyle(.secondary)
            }
            .padding(8)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
        }
        .buttonStyle(.plain)
    }
}
