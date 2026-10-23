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
    @State private var repeatWeekdays: Int? = nil
    @State private var dependsOnSubtaskId: UUID? = nil
    @State private var savedJobId: UUID? = nil
    @State private var resolvedParentId: UUID? = nil
    @State private var isLoaded = false

    private var repository: JobRepository { JobRepository(modelContext: modelContext) }
    private var categories: [String] { Array(Set(allJobs.map(\.category).filter { !$0.isEmpty })).sorted() }

    private var isValid: Bool {
        guard !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, estimatedMinutes > 0 else { return false }
        guard let days = recurrenceDays else { return true }
        if let mask = repeatWeekdays { return mask & everyDayMask != 0 }
        return days > 0
    }

    /// Repeat and subtasks are either/or.
    private var hasSubtasks: Bool {
        guard let savedJobId else { return false }
        return allJobs.contains { $0.parentId == savedJobId }
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

            // Only subtasks can depend on something, and only on a sibling.
            if isLoaded, let subtaskParentId = resolvedParentId {
                Section("Do this after") {
                    Text(
                        "It won't be drawn until that one's done. You can still tick it off whenever you like."
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
                            get: { recurrenceDays != nil },
                            set: { enabled in
                                recurrenceDays = enabled ? (recurrenceDays ?? 7) : nil
                                if !enabled { repeatWeekdays = nil }
                            }
                        )
                    )
                    .disabled(recurrenceDays == nil && hasSubtasks)
                    if let days = recurrenceDays {
                        Text(formatRecurrence(days: days, weekdays: repeatWeekdays)).font(.title3.weight(.semibold))
                        Picker(
                            "Schedule",
                            selection: Binding(
                                get: { repeatWeekdays != nil },
                                set: { onWeekdays in
                                    if onWeekdays {
                                        recurrenceDays = 7
                                        repeatWeekdays = repeatWeekdays
                                            ?? weekdayBit(calendarWeekday: Calendar.current.component(.weekday, from: .now))
                                    } else {
                                        repeatWeekdays = nil
                                    }
                                }
                            )
                        ) {
                            Text("Every few days").tag(false)
                            Text("On set days").tag(true)
                        }
                        .pickerStyle(.segmented)

                        if let mask = repeatWeekdays {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 6) {
                                    ForEach(mondayFirstWeekdays, id: \.self) { weekday in
                                        let bit = weekdayBit(calendarWeekday: weekday)
                                        FilterChip(
                                            label: Calendar.current.shortWeekdaySymbols[weekday - 1],
                                            selected: mask & bit != 0
                                        ) {
                                            repeatWeekdays = mask ^ bit
                                        }
                                    }
                                }
                            }
                            Text("Comes back each chosen day, even if you were late last time.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        } else {
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
                                "Every \(countLabel(days, "day", "days"))",
                                value: Binding(get: { days }, set: { recurrenceDays = $0 }),
                                in: 1...365
                            )
                            Text("Counts from the day you finish it.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
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
                    if recurrenceDays != nil {
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
            recurrenceDays = job.recurrenceDays
            repeatWeekdays = job.repeatWeekdays
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
        let edit = JobEdit(
            title: title,
            notes: notes,
            estimatedMinutes: estimatedMinutes,
            category: category,
            priority: priority,
            recurrenceDays: recurrenceDays,
            repeatWeekdays: recurrenceDays == nil ? nil : repeatWeekdays,
            dependsOnSubtaskId: dependsOnSubtaskId
        )
        if let id = savedJobId, let job = allJobs.first(where: { $0.id == id }) {
            repository.updateJob(job, with: edit, allJobs: allJobs)
            return id
        }
        guard let job = repository.addJob(edit, parentId: resolvedParentId, allJobs: allJobs) else { return nil }
        savedJobId = job.id
        return job.id
    }
}
