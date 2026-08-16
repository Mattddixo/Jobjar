import SwiftData
import SwiftUI

struct JobListView: View {
    var onAddJob: () -> Void
    var onOpenJob: (UUID) -> Void

    @Environment(\.modelContext) private var modelContext
    @Query private var allJobs: [Job]
    @State private var viewModel = JobListViewModel()
    @State private var itemPendingDelete: JobListItem? = nil
    @State private var itemPendingForceComplete: JobListItem? = nil

    private var repository: JobRepository { JobRepository(modelContext: modelContext) }

    private var categories: [String] {
        Array(Set(allJobs.map(\.category).filter { !$0.isEmpty })).sorted()
    }

    private var items: [JobListItem] { viewModel.items(allJobs: allJobs) }

    private var emptyStateText: String {
        if viewModel.showRepeatingOnly { return "No repeating jobs yet." }
        if viewModel.showCompleted { return "No completed jobs yet." }
        return "No jobs yet. Tap + to add one."
    }

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $viewModel.showCompleted) {
                Text("Active").tag(false)
                Text("Completed").tag(true)
            }
            .pickerStyle(.segmented)
            .disabled(viewModel.showRepeatingOnly)
            .padding(.horizontal, 16)
            .padding(.top, 16)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    FilterChip(
                        label: "Repeating",
                        selected: viewModel.showRepeatingOnly,
                        leadingSystemImage: "repeat",
                        action: { viewModel.showRepeatingOnly.toggle() }
                    )
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }

            if !categories.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        FilterChip(label: "All", selected: viewModel.selectedCategory == nil, action: { viewModel.selectedCategory = nil })
                        ForEach(categories, id: \.self) { category in
                            FilterChip(
                                label: category,
                                selected: viewModel.selectedCategory == category,
                                action: { viewModel.selectedCategory = viewModel.selectedCategory == category ? nil : category }
                            )
                        }
                    }
                    .padding(.horizontal, 16)
                }
                .padding(.bottom, 8)
            }

            if items.isEmpty {
                Spacer()
                Text(emptyStateText)
                    .font(.body)
                    .padding(32)
                Spacer()
            } else {
                List {
                    ForEach(items) { item in
                        JobRow(
                            item: item,
                            onTap: { onOpenJob(item.job.id) },
                            onToggleDone: {
                                let hasOpenSubtasks = item.subtaskTotal > 0 && item.subtaskDone < item.subtaskTotal
                                if item.job.isPending() && hasOpenSubtasks {
                                    itemPendingForceComplete = item
                                } else {
                                    repository.toggleDone(item.job, allJobs: allJobs)
                                }
                            }
                        )
                        .swipeActions {
                            Button(role: .destructive) {
                                itemPendingDelete = item
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                        }
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle("Jobs")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    ForEach(JobSortOrder.allCases) { order in
                        Button(order.label) { viewModel.sortOrder = order }
                    }
                } label: {
                    Image(systemName: "arrow.up.arrow.down")
                }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: onAddJob) {
                    Image(systemName: "plus")
                }
            }
        }
        .alert(
            "Delete job?",
            isPresented: Binding(get: { itemPendingDelete != nil }, set: { if !$0 { itemPendingDelete = nil } })
        ) {
            Button("Cancel", role: .cancel) { itemPendingDelete = nil }
            Button("Delete", role: .destructive) {
                if let item = itemPendingDelete {
                    repository.deleteJob(item.job, allJobs: allJobs)
                }
                itemPendingDelete = nil
            }
        } message: {
            if let item = itemPendingDelete {
                Text(
                    item.subtaskTotal > 0
                        ? "\"\(item.job.title)\" and its \(item.subtaskTotal) subtask(s) will be removed permanently."
                        : "\"\(item.job.title)\" will be removed permanently."
                )
            }
        }
        .alert(
            "Mark as done?",
            isPresented: Binding(get: { itemPendingForceComplete != nil }, set: { if !$0 { itemPendingForceComplete = nil } })
        ) {
            Button("Cancel", role: .cancel) { itemPendingForceComplete = nil }
            Button("Mark done") {
                if let item = itemPendingForceComplete {
                    repository.toggleDone(item.job, allJobs: allJobs)
                }
                itemPendingForceComplete = nil
            }
        } message: {
            if let item = itemPendingForceComplete {
                let incomplete = item.subtaskTotal - item.subtaskDone
                Text("\(incomplete) subtask(s) are still open. They'll stay open, but this job will be marked done.")
            }
        }
    }
}

private struct JobRow: View {
    let item: JobListItem
    let onTap: () -> Void
    let onToggleDone: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                Button(action: onToggleDone) {
                    Image(systemName: !item.job.isPending() ? "checkmark.circle.fill" : "circle")
                        .font(.title2)
                        .foregroundStyle(!item.job.isPending() ? Color.accentColor : Color.secondary)
                }
                .buttonStyle(.plain)

                VStack(alignment: .leading, spacing: 4) {
                    Text(item.job.title)
                        .font(.body.weight(.medium))
                        .strikethrough(!item.job.isPending())
                        .foregroundStyle(.primary)
                    HStack(spacing: 6) {
                        TimeBadge(minutes: item.displayMinutes)
                        if !item.job.category.isEmpty { CategoryBadge(category: item.job.category) }
                        if item.subtaskTotal > 0 { InfoBadge(text: "\(item.subtaskDone)/\(item.subtaskTotal) done") }
                        if let recurrenceLabel = item.recurrenceLabel { InfoBadge(text: recurrenceLabel) }
                    }
                    if let dueStatus = item.dueStatus {
                        Text(dueStatus).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
