import SwiftData
import SwiftUI

private let quickDurations = [5, 15, 30, 45, 60, 90, 120, 180]
private let recurrencePresets = [1, 7, 14, 30]

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
    @State private var recurrenceDays: Int? = nil
    @State private var dependsOnSubtaskId: UUID? = nil
    @State private var savedJobId: UUID? = nil
    @State private var resolvedParentId: UUID? = nil
    @State private var isLoaded = false

    private var repository: JobRepository { JobRepository(modelContext: modelContext) }
    private var categories: [String] { Array(Set(allJobs.map(\.category).filter { !$0.isEmpty })).sorted() }

    private var isValid: Bool {
        !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            estimatedMinutes > 0 &&
            (recurrenceDays == nil || recurrenceDays! > 0)
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
                TextField("Title", text: $title)
                TextField("Notes (optional)", text: $notes, axis: .vertical)
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
                        // Long jobs don't have one "right" length, so this is a range, not an
                        // exact value: selected whenever the estimate is 4hr or more, and
                        // tapping it seeds 4hr as a starting point you can then fine-tune.
                        FilterChip(label: "4+ hrs", selected: estimatedMinutes >= longJobMinutes) {
                            estimatedMinutes = longJobMinutes
                        }
                    }
                }
                Stepper(
                    "Custom: \(estimatedMinutes) min",
                    value: $estimatedMinutes,
                    in: 0...1440,
                    step: 5
                )
            }

            Section("Category") {
                TextField("e.g. Chores, Work, Errands", text: $category)
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

            // The opposite gate from below: only a subtask can depend on another subtask, and
            // only among its own siblings (same parent).
            if isLoaded, let subtaskParentId = resolvedParentId {
                Section("Depends on") {
                    Text(
                        "Optional: this subtask stays out of the jar's random draw until the one it depends " +
                            "on is done. You can still check it off by hand any time."
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                    let siblings = allJobs.filter { $0.parentId == subtaskParentId }
                    let candidates = subtasksAvailableAsDependency(siblings: siblings, excludingSelfId: savedJobId)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            FilterChip(label: "None", selected: dependsOnSubtaskId == nil) {
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

            // Only a job that isn't itself a subtask can repeat or have subtasks (one level
            // deep). Gated on isLoaded so an existing subtask being edited never flashes
            // either section before we know its real parentId.
            if isLoaded, resolvedParentId == nil {
                Section("Repeat") {
                    Toggle(
                        "Repeat",
                        isOn: Binding(
                            get: { recurrenceDays != nil },
                            set: { enabled in recurrenceDays = enabled ? (recurrenceDays ?? 7) : nil }
                        )
                    )
                    if let days = recurrenceDays {
                        Text(formatRecurrenceInterval(days)).font(.title3.weight(.semibold))
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(recurrencePresets, id: \.self) { preset in
                                    FilterChip(label: formatRecurrenceInterval(preset), selected: days == preset) {
                                        recurrenceDays = preset
                                    }
                                }
                            }
                        }
                        Stepper(
                            "Custom: every \(days) day(s)",
                            value: Binding(get: { days }, set: { recurrenceDays = $0 }),
                            in: 1...365
                        )
                    } else {
                        Text("Completing a repeating job reopens it automatically after the interval, instead of leaving it done for good.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                Section("Subtasks") {
                    if let savedJobId {
                        SubtasksSection(
                            parentId: savedJobId,
                            parentEstimatedMinutes: estimatedMinutes,
                            onOpenSubtask: onOpenSubtask,
                            onAddSubtask: { onAddSubtask(savedJobId) }
                        )
                    } else {
                        Text("Adding a subtask saves this job first, using what you've filled in so far.")
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
            recurrenceDays = job.recurrenceDays
            dependsOnSubtaskId = job.dependsOnSubtaskId
            resolvedParentId = job.parentId
            savedJobId = job.id
        } else {
            // Prefill the new subtask's category from its parent, purely as a convenience.
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

    /// Ensures this job already exists in SwiftData - inserting it now, using whatever's
    /// currently in the form, if it's still an unsaved draft - then invokes `onReady` with its
    /// id. This is what lets "Add subtask" work before you've explicitly hit Save: the job
    /// behind the form quietly becomes real the moment you need to attach something to it.
    private func ensurePersisted(onReady: (UUID) -> Void) {
        if let savedJobId {
            onReady(savedJobId)
            return
        }
        guard isValid else { return }
        onReady(persist())
    }

    @discardableResult
    private func persist() -> UUID {
        if let id = savedJobId, let job = allJobs.first(where: { $0.id == id }) {
            job.title = title.trimmingCharacters(in: .whitespacesAndNewlines)
            job.notes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
            job.estimatedMinutes = estimatedMinutes
            job.category = category.trimmingCharacters(in: .whitespacesAndNewlines)
            job.priority = priority
            // Turning repeat off clears the stale schedule; turning it on (or leaving it on)
            // keeps whatever nextDueAt already existed.
            if recurrenceDays == nil { job.nextDueAt = nil }
            job.recurrenceDays = recurrenceDays
            job.dependsOnSubtaskId = dependsOnSubtaskId
            repository.updateJob(job, allJobs: allJobs)
            return id
        }

        let job = Job(
            title: title.trimmingCharacters(in: .whitespacesAndNewlines),
            notes: notes.trimmingCharacters(in: .whitespacesAndNewlines),
            estimatedMinutes: estimatedMinutes,
            category: category.trimmingCharacters(in: .whitespacesAndNewlines),
            priority: priority,
            parentId: resolvedParentId,
            recurrenceDays: recurrenceDays,
            dependsOnSubtaskId: dependsOnSubtaskId
        )
        repository.addJob(job, allJobs: allJobs)
        savedJobId = job.id
        return job.id
    }
}
