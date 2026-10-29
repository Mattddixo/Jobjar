import SwiftData
import SwiftUI

private let quickDurations = [5, 15, 30, 45, 60, 90, 120, 180]
private let maxTitleLength = 80

struct AddEditJobView: View {
    let jobId: UUID?
    var parentId: UUID? = nil
    var onDone: () -> Void
    var onOpenSubtask: (UUID) -> Void = { _ in }
    var onAddSubtask: (UUID) -> Void = { _ in }

    @Environment(\.modelContext) private var modelContext
    @Query private var allJobs: [Job]

    @State private var title = ""
    @State private var notes = ""
    @State private var estimatedMinutes = 15
    @State private var category = ""
    @State private var priority: Priority = .normal
    /// As typed. Blank = no cost.
    @State private var costText = ""
    @State private var repeatForm = RepeatForm()
    @State private var dependsOnSubtaskId: UUID? = nil
    @State private var savedJobId: UUID? = nil
    @State private var resolvedParentId: UUID? = nil
    @State private var isLoaded = false

    private var repository: JobRepository { JobRepository(modelContext: modelContext) }
    private var categories: [String] { Array(Set(allJobs.map(\.category).filter { !$0.isEmpty })).sorted() }

    private var isValid: Bool {
        guard !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, estimatedMinutes > 0 else { return false }
        guard parseCostInput(costText) != .invalid else { return false }
        return repeatForm.isValid
    }

    /// Repeat and subtasks are either/or.
    private var hasSubtasks: Bool {
        guard let savedJobId else { return false }
        return allJobs.contains { $0.parentId == savedJobId }
    }

    /// A typo hint, or for a job with subtasks, what they add so the total isn't a surprise.
    @ViewBuilder
    private var costFooter: some View {
        let input = parseCostInput(costText)
        let subtaskCosts = savedJobId.map { id in allJobs.filter { $0.parentId == id }.compactMap(\.costCents) } ?? []
        if input == .invalid {
            Text("Enter an amount, like 12.50").foregroundStyle(.red)
        } else if !subtaskCosts.isEmpty {
            let subtaskTotal = subtaskCosts.reduce(0, +)
            let own: Int = { if case .valid(let cents) = input { return cents } else { return 0 } }()
            Text("Subtasks add \(formatCost(subtaskTotal)) · \(formatCost(own + subtaskTotal)) in total")
        }
    }

    private var navTitle: String {
        if jobId != nil, isLoaded, resolvedParentId != nil { return "Edit subtask" }
        if jobId != nil { return "Edit job" }
        if parentId != nil { return "New subtask" }
        return "New job"
    }

    var body: some View {
        Form {
            Section {
                TextField("What needs doing?", text: $title)
                    .onChange(of: title) { _, new in
                        if new.count > maxTitleLength { title = String(new.prefix(maxTitleLength)) }
                    }
                HStack {
                    Spacer()
                    Text("\(title.count)/\(maxTitleLength)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
                TextField("Notes", text: $notes, axis: .vertical)
                    .lineLimit(2...4)
            }

            Section("How long will it take?") {
                Text(formatMinutes(estimatedMinutes)).font(.title2.weight(.semibold))
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(quickDurations, id: \.self) { minutes in
                            FilterChip(label: formatMinutes(minutes), selected: estimatedMinutes == minutes) {
                                estimatedMinutes = minutes
                            }
                        }
                        // Selected for anything 4hr+. Tapping it sets 4hr.
                        FilterChip(label: "4+ hrs", selected: estimatedMinutes >= longJobMinutes) {
                            estimatedMinutes = longJobMinutes
                        }
                    }
                }
                Stepper(
                    "\(estimatedMinutes) min",
                    value: $estimatedMinutes,
                    in: 0...1440,
                    step: 5
                )

                // Uses what's typed right now for this subtask, not its saved estimate.
                if isLoaded, let parentId = resolvedParentId, let parent = allJobs.first(where: { $0.id == parentId }) {
                    let others = allJobs.filter { $0.parentId == parentId && $0.id != savedJobId }
                    AllocationSummary(
                        estimatedMinutes: parent.estimatedMinutes,
                        subtasksTotalMinutes: others.reduce(0) { $0 + $1.estimatedMinutes } + estimatedMinutes
                    )
                }
                if resolvedParentId == nil, let savedJobId, hasSubtasks {
                    AllocationSummary(
                        estimatedMinutes: estimatedMinutes,
                        subtasksTotalMinutes: allJobs.filter { $0.parentId == savedJobId }.reduce(0) { $0 + $1.estimatedMinutes }
                    )
                }
            }

            Section("Category") {
                TextField("Chores, errands, garden…", text: $category)
                if !categories.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(categories, id: \.self) { c in
                                FilterChip(label: c, selected: category == c) { category = c }
                            }
                        }
                    }
                }
            }

            Section("Priority") {
                Picker("Priority", selection: $priority) {
                    ForEach(Priority.allCases) { p in
                        Text(p.displayName).tag(p)
                    }
                }
                .pickerStyle(.segmented)
            }

            Section {
                HStack(spacing: 4) {
                    Text(currencySymbol()).foregroundStyle(.secondary)
                    TextField("Optional", text: $costText)
                        .keyboardType(.decimalPad)
                }
            } header: {
                Text("Cost")
            } footer: {
                costFooter
            }

            // Only subtasks can depend on something, and only on a sibling.
            if isLoaded, let subtaskParentId = resolvedParentId {
                Section("Do this after") {
                    Text(
                        "It won't be drawn until that one's completed. You can still tick it off whenever you like."
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                    let siblings = allJobs.filter { $0.parentId == subtaskParentId }
                    let candidates = subtasksAvailableAsDependency(siblings: siblings, excludingSelfId: savedJobId)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            FilterChip(label: "Nothing", selected: dependsOnSubtaskId == nil) {
                                dependsOnSubtaskId = nil
                            }
                            ForEach(candidates) { candidate in
                                FilterChip(label: candidate.title, selected: dependsOnSubtaskId == candidate.id) {
                                    dependsOnSubtaskId = candidate.id
                                }
                            }
                        }
                    }
                }
            }

            // Top-level jobs only. Waits for isLoaded so a subtask doesn't flash these sections.
            if isLoaded, resolvedParentId == nil {
                Section("Repeat") {
                    Toggle(
                        "Repeat",
                        isOn: Binding(
                            get: { repeatForm.repeats },
                            set: { repeatForm.setRepeats($0) }
                        )
                    )
                    .disabled(!repeatForm.repeats && hasSubtasks)
                    if repeatForm.repeats {
                        RepeatEditor(form: $repeatForm)
                    } else {
                        Text(
                            hasSubtasks
                                ? "Jobs with subtasks can't repeat."
                                : "Puts the job back in the jar after you finish it."
                        )
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    }
                }

                Section("Subtasks") {
                    if repeatForm.repeats {
                        Text("Repeating jobs can't have subtasks.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    } else if let savedJobId {
                        SubtasksSection(
                            parentId: savedJobId,
                            parentEstimatedMinutes: estimatedMinutes,
                            onOpenSubtask: onOpenSubtask,
                            onAddSubtask: { ensurePersisted { onAddSubtask($0) } }
                        )
                    } else {
                        Text("This saves the job first.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        Button {
                            ensurePersisted { onAddSubtask($0) }
                        } label: {
                            Label("Add subtask", systemImage: "plus")
                        }
                        .disabled(!isValid)
                    }
                }
            }
        }
        .navigationTitle(navTitle)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button("Save", action: save).disabled(!isValid)
            }
        }
        .task { load() }
    }

    private func load() {
        if let jobId, let job = allJobs.first(where: { $0.id == jobId }) {
            title = job.title
            notes = job.notes
            estimatedMinutes = job.estimatedMinutes
            category = job.category
            priority = job.priority
            costText = costInputText(job.costCents)
            repeatForm = RepeatForm(job: job)
            dependsOnSubtaskId = job.dependsOnSubtaskId
            resolvedParentId = job.parentId
            savedJobId = job.id
        } else {
            // Handy default: same category as the parent.
            resolvedParentId = parentId
            if let parentId, let parent = allJobs.first(where: { $0.id == parentId }), !parent.category.isEmpty {
                category = parent.category
            }
        }
        isLoaded = true
    }

    private func save() {
        persist()
        onDone()
    }

    /// Saves the form (inserting it if it's new) and hands back the id, so "Add subtask" works
    /// without hitting Save first.
    private func ensurePersisted(onReady: (UUID) -> Void) {
        guard isValid else { return }
        if let id = persist() { onReady(id) }
    }

    /// Saves through the repository so its rules apply.
    @discardableResult
    private func persist() -> UUID? {
        var edit = JobEdit(
            title: title,
            notes: notes,
            estimatedMinutes: estimatedMinutes,
            category: category,
            priority: priority,
            recurrenceDays: nil,
            repeatWeekdays: nil,
            dependsOnSubtaskId: dependsOnSubtaskId,
            costCents: { if case .valid(let cents) = parseCostInput(costText) { return cents } else { return nil } }()
        )
        edit.rule = repeatForm.rule
        if let id = savedJobId, let job = allJobs.first(where: { $0.id == id }) {
            repository.updateJob(job, with: edit, allJobs: allJobs, firstDue: repeatForm.firstDue)
            return id
        }
        guard let job = repository.addJob(edit, parentId: resolvedParentId, allJobs: allJobs, firstDue: repeatForm.firstDue) else { return nil }
        savedJobId = job.id
        return job.id
    }
}
