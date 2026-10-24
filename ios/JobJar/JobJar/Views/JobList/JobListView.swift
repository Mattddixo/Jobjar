import SwiftData
import SwiftUI

struct JobListView: View {
    var onAddJob: () -> Void
    var onOpenJob: (UUID) -> Void

    @Environment(\.modelContext) private var modelContext
    @Environment(UndoCenter.self) private var undoCenter
    @Query private var allJobs: [Job]
    @State private var viewModel = JobListViewModel()
    @State private var itemPendingDelete: JobListItem? = nil
    @State private var itemPendingForceComplete: JobListItem? = nil
    @State private var jobToSchedule: Job? = nil
    @State private var showManageCategories = false

    private var repository: JobRepository { JobRepository(modelContext: modelContext) }

    private var categories: [String] {
        Array(Set(allJobs.map(\.category).filter { !$0.isEmpty })).sorted()
    }

    var body: some View {
        let items = viewModel.items(allJobs: allJobs)
        VStack(spacing: 0) {
            Picker("Show", selection: $viewModel.view) {
                ForEach(JobsView.allCases) { view in
                    Text(view.label).tag(view)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.top, 8)

            filterRow

            if items.isEmpty {
                Text(viewModel.emptyStateText)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 20)
                Spacer()
            } else {
                List {
                    ForEach(items) { item in
                        JobRow(
                            item: item,
                            isExpanded: viewModel.expandedParentIds.contains(item.id),
                            onOpen: { onOpenJob(item.id) },
                            onToggleDone: { requestToggleDone(item) },
                            onToggleInProgress: { repository.toggleInProgress(item.job) },
                            onToggleExpanded: { viewModel.toggleExpanded(item.id) },
                            onSchedule: { jobToSchedule = item.job },
                            onUnschedule: { repository.unscheduleJob(item.job) },
                            onDelete: { itemPendingDelete = item }
                        )
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets(top: 4, leading: item.isSubtask ? 40 : 16, bottom: 4, trailing: 16))
                        .swipeActions(edge: .leading) {
                            Button {
                                requestToggleDone(item)
                            } label: {
                                Label(item.job.isPending() ? "Mark complete" : "Mark not complete", systemImage: "checkmark")
                            }
                            .tint(.accentColor)
                        }
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) {
                                itemPendingDelete = item
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .pageBackground()
        .navigationTitle("Jobs")
        .searchable(text: $viewModel.searchQuery, prompt: "Search jobs")
        .todayDateButton()
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Picker("Sort", selection: $viewModel.sortOrder) {
                        ForEach(JobSortOrder.allCases) { order in
                            Text(order.label).tag(order)
                        }
                    }
                } label: {
                    Image(systemName: "arrow.up.arrow.down")
                }
                .accessibilityLabel("Sort")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button(action: onAddJob) {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("Add job")
            }
        }
        .scheduleSheet(for: $jobToSchedule) { job, date in
            undoCenter.noticeCalendarResult(repository.scheduleJob(job, at: date))
        }
        .sheet(isPresented: $showManageCategories) {
            ManageCategoriesSheet(allJobs: allJobs) { category in
                repository.removeCategory(category, allJobs: allJobs)
                // It won't be in the menu any more, so untick it too.
                viewModel.selectedCategories.remove(category)
            }
        }
        .alert(
            "Delete job?",
            isPresented: Binding(get: { itemPendingDelete != nil }, set: { if !$0 { itemPendingDelete = nil } }),
            presenting: itemPendingDelete
        ) { item in
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                let title = item.job.title
                let snapshot = repository.deleteJob(item.job, allJobs: allJobs)
                undoCenter.offerDeleted(title, snapshot)
            }
        } message: { item in
            Text(
                item.subtaskTotal > 0
                    ? "\(item.job.title) and its \(item.subtaskTotal == 1 ? "subtask" : "\(item.subtaskTotal) subtasks") will be deleted."
                    : "\(item.job.title) will be deleted."
            )
        }
        .alert(
            "Mark as completed?",
            isPresented: Binding(get: { itemPendingForceComplete != nil }, set: { if !$0 { itemPendingForceComplete = nil } }),
            presenting: itemPendingForceComplete
        ) { item in
            Button("Cancel", role: .cancel) {}
            Button("Mark complete") { toggleDone(item.job) }
        } message: { item in
            Text(forceCompleteMessage(openSubtasks: item.subtaskTotal - item.subtaskDone))
        }
    }

    /// All filters live in this one scrolling row. Tap a chip again to clear it.
    private var filterRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                FilterChip(
                    label: "Repeating",
                    selected: viewModel.showRepeatingOnly,
                    leadingSystemImage: "repeat",
                    action: { viewModel.showRepeatingOnly.toggle() }
                )
                FilterChip(
                    label: "In progress",
                    selected: viewModel.showInProgressOnly,
                    leadingSystemImage: "play.fill",
                    action: { viewModel.showInProgressOnly.toggle() }
                )
                if !categories.isEmpty {
                    MenuChip(
                        label: viewModel.selectedCategories.isEmpty ? "Category" : "Category (\(viewModel.selectedCategories.count))",
                        selected: !viewModel.selectedCategories.isEmpty,
                        staysOpen: true
                    ) {
                        ForEach(categories, id: \.self) { category in
                            CheckedMenuItem(
                                title: category,
                                checked: viewModel.selectedCategories.contains(category),
                                action: { viewModel.toggleCategory(category) }
                            )
                        }
                        // Nowhere else lists every category, so editing them lives here.
                        Divider()
                        Button {
                            showManageCategories = true
                        } label: {
                            Label("Edit categories", systemImage: "pencil")
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
    }

    private func requestToggleDone(_ item: JobListItem) {
        let hasOpenSubtasks = item.subtaskTotal > 0 && item.subtaskDone < item.subtaskTotal
        if item.job.isPending() && hasOpenSubtasks {
            itemPendingForceComplete = item
        } else {
            toggleDone(item.job)
        }
    }

    /// Only completing offers Undo. Reopening is its own undo.
    private func toggleDone(_ job: Job) {
        let completing = job.isPending()
        let title = job.title
        let snapshot = repository.toggleDone(job, allJobs: allJobs)
        if completing { undoCenter.offerCompleted(title, snapshot) }
    }
}

func forceCompleteMessage(openSubtasks: Int) -> String {
    openSubtasks == 1
        ? "There's still 1 subtask open. It'll be marked completed too."
        : "There are still \(openSubtasks) subtasks open. They'll be marked completed too."
}

private struct JobRow: View {
    let item: JobListItem
    let isExpanded: Bool
    let onOpen: () -> Void
    let onToggleDone: () -> Void
    let onToggleInProgress: () -> Void
    let onToggleExpanded: () -> Void
    let onSchedule: () -> Void
    let onUnschedule: () -> Void
    let onDelete: () -> Void

    var body: some View {
        let job = item.job
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 4) {
                Text(job.title)
                    .font(item.isSubtask ? .subheadline.weight(.medium) : .body.weight(.medium))
                    .strikethrough(!job.isPending())
                if let parentTitle = item.parentTitle {
                    Text("Part of \(parentTitle)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                FlowLayout {
                    if job.isInProgress { InProgressBadge() }
                    TimeBadge(minutes: item.displayMinutes)
                    if let cost = item.costCents { InfoBadge(text: formatCost(cost)) }
                    if !job.category.isEmpty { CategoryBadge(category: job.category) }
                    if item.subtaskTotal > 0 { InfoBadge(text: "\(item.subtaskDone)/\(item.subtaskTotal) completed") }
                    if let days = job.recurrenceDays { InfoBadge(text: formatRecurrence(days: days, weekdays: job.repeatWeekdays)) }
                    if let scheduled = job.scheduledDate { ScheduledBadge(date: scheduled, missed: job.isMissed()) }
                }
                if let waitingOnTitle = item.waitingOnTitle {
                    Label("Waiting on \(waitingOnTitle)", systemImage: "lock.fill")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                if job.recurrenceDays != nil {
                    Text(formatDueStatus(job.nextDueAt)).font(.caption).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture(perform: onOpen)

            // Expand/collapse for parents. Groups start collapsed (search and filters open them).
            if item.subtaskTotal > 0 {
                Button(action: onToggleExpanded) {
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .accessibilityLabel(isExpanded ? "Hide subtasks" : "Show subtasks")
            }

            Menu {
                Button(action: onOpen) { Label("Open", systemImage: "pencil") }
                Button(action: onToggleDone) {
                    Label(job.isPending() ? "Mark complete" : "Mark not complete", systemImage: job.isPending() ? "circle" : "checkmark.circle.fill")
                }
                if job.isInProgress {
                    Button(action: onToggleInProgress) { Label("Put back in jar", systemImage: "arrow.uturn.backward") }
                } else if job.isPending() {
                    Button(action: onToggleInProgress) { Label("Start", systemImage: "play.fill") }
                }
                // Repeating jobs can't be scheduled.
                if job.recurrenceDays == nil {
                    // A missed booking offers Schedule again.
                    if job.isBooked() {
                        Button(action: onUnschedule) { Label("Unschedule", systemImage: "calendar.badge.minus") }
                    } else {
                        Button(action: onSchedule) { Label("Schedule", systemImage: "calendar.badge.plus") }
                    }
                }
                Button(role: .destructive, action: onDelete) { Label("Delete", systemImage: "trash") }
            } label: {
                Image(systemName: "ellipsis")
                    .frame(width: 32, height: 32)
                    .contentShape(Rectangle())
            }
            .foregroundStyle(.secondary)
            .accessibilityLabel("More options")
        }
        .padding(item.isSubtask ? 10 : 14)
        .background(
            Group {
                // Subtasks: indented, no border, so they sit under their parent.
                if item.isSubtask {
                    RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemFill).opacity(0.5))
                } else {
                    Color.clear.cardStyle(cornerRadius: 14)
                }
            }
        )
    }
}

/// Lists categories in use so you can remove one. Asks before removing.
private struct ManageCategoriesSheet: View {
    let allJobs: [Job]
    let onRemove: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var pendingRemoval: String? = nil

    private var counts: [String: Int] {
        Dictionary(grouping: allJobs.filter { !$0.category.isEmpty }, by: \.category).mapValues(\.count)
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(counts.keys.sorted(), id: \.self) { category in
                    HStack {
                        Text(category)
                        Spacer()
                        Button {
                            pendingRemoval = category
                        } label: {
                            Image(systemName: "trash")
                        }
                        .buttonStyle(.borderless)
                        .foregroundStyle(.red)
                        .accessibilityLabel("Remove \(category)")
                    }
                }
            }
            .overlay {
                if counts.isEmpty {
                    Text("No categories yet.").foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Edit categories")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .alert(
                "Remove category?",
                isPresented: Binding(get: { pendingRemoval != nil }, set: { if !$0 { pendingRemoval = nil } }),
                presenting: pendingRemoval
            ) { category in
                Button("Cancel", role: .cancel) {}
                Button("Remove", role: .destructive) { onRemove(category) }
            } message: { category in
                let count = counts[category] ?? 0
                Text(
                    count == 1
                        ? "\(category) is used by 1 job. It'll be left without a category."
                        : "\(category) is used by \(count) jobs. They'll be left without a category."
                )
            }
        }
        .presentationDetents([.medium, .large])
    }
}
