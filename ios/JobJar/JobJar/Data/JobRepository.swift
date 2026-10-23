import Foundation
import SwiftData

/// The fields from the add/edit form. The repository applies them and enforces the rules.
struct JobEdit {
    var title: String
    var notes: String
    var estimatedMinutes: Int
    var category: String
    var priority: Priority
    var recurrenceDays: Int?
    var repeatWeekdays: Int?
    var dependsOnSubtaskId: UUID?
}

/// Plain copy of a job, for undo and import.
struct JobRecord: Equatable {
    var id: UUID
    var title: String
    var notes: String
    var estimatedMinutes: Int
    var category: String
    var priority: Priority
    var isDone: Bool
    var createdAt: Date
    var completedAt: Date?
    var timesDrawn: Int
    var parentId: UUID?
    var recurrenceDays: Int?
    var repeatWeekdays: Int?
    var nextDueAt: Date?
    var completionCount: Int
    var dependsOnSubtaskId: UUID?

    init(_ job: Job) {
        id = job.id
        title = job.title
        notes = job.notes
        estimatedMinutes = job.estimatedMinutes
        category = job.category
        priority = job.priority
        isDone = job.isDone
        createdAt = job.createdAt
        completedAt = job.completedAt
        timesDrawn = job.timesDrawn
        parentId = job.parentId
        recurrenceDays = job.recurrenceDays
        repeatWeekdays = job.repeatWeekdays
        nextDueAt = job.nextDueAt
        completionCount = job.completionCount
        dependsOnSubtaskId = job.dependsOnSubtaskId
    }

    init(
        id: UUID, title: String, notes: String, estimatedMinutes: Int, category: String, priority: Priority,
        isDone: Bool, createdAt: Date, completedAt: Date?, timesDrawn: Int, parentId: UUID?,
        recurrenceDays: Int?, repeatWeekdays: Int?, nextDueAt: Date?, completionCount: Int, dependsOnSubtaskId: UUID?
    ) {
        self.id = id
        self.title = title
        self.notes = notes
        self.estimatedMinutes = estimatedMinutes
        self.category = category
        self.priority = priority
        self.isDone = isDone
        self.createdAt = createdAt
        self.completedAt = completedAt
        self.timesDrawn = timesDrawn
        self.parentId = parentId
        self.recurrenceDays = recurrenceDays
        self.repeatWeekdays = repeatWeekdays
        self.nextDueAt = nextDueAt
        self.completionCount = completionCount
        self.dependsOnSubtaskId = dependsOnSubtaskId
    }

    func apply(to job: Job) {
        job.title = title
        job.notes = notes
        job.estimatedMinutes = estimatedMinutes
        job.category = category
        job.priority = priority
        job.isDone = isDone
        job.createdAt = createdAt
        job.completedAt = completedAt
        job.timesDrawn = timesDrawn
        job.parentId = parentId
        job.recurrenceDays = recurrenceDays
        job.repeatWeekdays = repeatWeekdays
        job.nextDueAt = nextDueAt
        job.completionCount = completionCount
        job.dependsOnSubtaskId = dependsOnSubtaskId
    }

    func makeJob() -> Job {
        let job = Job(id: id, title: title, estimatedMinutes: estimatedMinutes)
        apply(to: job)
        return job
    }
}

/// The jobs a change touched, as they were before it.
struct UndoSnapshot: Equatable {
    let records: [JobRecord]
}

/// All the job rules live here (same as the Android repository). Views read through @Query
/// and pass their `allJobs` in.
@MainActor
struct JobRepository {
    let modelContext: ModelContext

    private func fetchAll() -> [Job] {
        (try? modelContext.fetch(FetchDescriptor<Job>())) ?? []
    }

    private func save() {
        try? modelContext.save()
    }

    /// Match the spelling other jobs already use ("home" -> "Home"). Ignores the job itself.
    private func canonicalCategory(_ raw: String, excluding jobId: UUID?, allJobs: [Job]) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        let existing = allJobs.filter { $0.id != jobId }.map(\.category)
        return existing.first { $0.caseInsensitiveCompare(trimmed) == .orderedSame } ?? trimmed
    }

    private func validWeekdays(_ mask: Int?) -> Int? {
        guard let mask, mask & everyDayMask != 0 else { return nil }
        return mask
    }

    /// Parent estimate grows to cover its subtasks. Never shrinks.
    private func growParentToFitSubtasks(parentId: UUID, allJobs: [Job]) {
        guard let parent = allJobs.first(where: { $0.id == parentId }) else { return }
        let subtaskTotal = allJobs.filter { $0.parentId == parentId }.reduce(0) { $0 + $1.estimatedMinutes }
        if subtaskTotal > parent.estimatedMinutes {
            parent.estimatedMinutes = subtaskTotal
        }
    }

    /// Nil if the parent can't take subtasks (missing, a subtask itself, or repeating).
    @discardableResult
    func addJob(_ edit: JobEdit, parentId: UUID?, allJobs: [Job]) -> Job? {
        if let parentId {
            guard let parent = allJobs.first(where: { $0.id == parentId }),
                  parent.parentId == nil,
                  parent.recurrenceDays == nil else { return nil }
        }
        let repeats = parentId == nil && edit.recurrenceDays != nil
        let weekdays = repeats ? validWeekdays(edit.repeatWeekdays) : nil
        let job = Job(
            title: edit.title.trimmingCharacters(in: .whitespacesAndNewlines),
            notes: edit.notes.trimmingCharacters(in: .whitespacesAndNewlines),
            estimatedMinutes: edit.estimatedMinutes,
            category: canonicalCategory(edit.category, excluding: nil, allJobs: allJobs),
            priority: edit.priority,
            parentId: parentId,
            recurrenceDays: repeats ? edit.recurrenceDays : nil,
            repeatWeekdays: weekdays,
            nextDueAt: repeats ? initialNextDue(repeatWeekdays: weekdays) : nil,
            dependsOnSubtaskId: parentId != nil ? edit.dependsOnSubtaskId : nil
        )
        modelContext.insert(job)
        if let parentId {
            growParentToFitSubtasks(parentId: parentId, allJobs: allJobs + [job])
        }
        save()
        return job
    }

    /// Saves the form onto `job`. Jobs with subtasks can't repeat, turning repeat on clears done,
    /// and switching repeat type restarts the cycle.
    func updateJob(_ job: Job, with edit: JobEdit, allJobs: [Job]) {
        let hasSubtasks = allJobs.contains { $0.parentId == job.id }
        let repeats = edit.recurrenceDays != nil && job.parentId == nil && !hasSubtasks
        let newDays = repeats ? edit.recurrenceDays : nil
        let newWeekdays = repeats ? validWeekdays(edit.repeatWeekdays) : nil

        let nextDue: Date?
        if newDays == nil {
            nextDue = nil
        } else if newWeekdays != job.repeatWeekdays {
            nextDue = initialNextDue(repeatWeekdays: newWeekdays)
        } else if job.recurrenceDays == nil {
            nextDue = nil
        } else {
            nextDue = job.nextDueAt
        }

        job.title = edit.title.trimmingCharacters(in: .whitespacesAndNewlines)
        job.notes = edit.notes.trimmingCharacters(in: .whitespacesAndNewlines)
        job.estimatedMinutes = edit.estimatedMinutes
        job.category = canonicalCategory(edit.category, excluding: job.id, allJobs: allJobs)
        job.priority = edit.priority
        if newDays != nil { job.isDone = false }
        job.recurrenceDays = newDays
        job.repeatWeekdays = newWeekdays
        job.nextDueAt = nextDue
        job.dependsOnSubtaskId = job.parentId != nil && edit.dependsOnSubtaskId != job.id ? edit.dependsOnSubtaskId : nil
        if let parentId = job.parentId {
            growParentToFitSubtasks(parentId: parentId, allJobs: allJobs)
        }
        save()
    }

    /// Deletes the job (and its subtasks if it's a parent). Siblings that depended on a deleted
    /// subtask lose the dependency. Returns an undo snapshot.
    @discardableResult
    func deleteJob(_ job: Job, allJobs: [Job]) -> UndoSnapshot {
        let removed = job.parentId == nil ? [job] + allJobs.filter { $0.parentId == job.id } : [job]
        let dependents = job.parentId == nil ? [] : allJobs.filter { $0.parentId == job.parentId && $0.dependsOnSubtaskId == job.id }
        let snapshot = UndoSnapshot(records: (removed + dependents).map(JobRecord.init))
        for dependent in dependents {
            dependent.dependsOnSubtaskId = nil
        }
        for row in removed {
            modelContext.delete(row)
        }
        save()
        return snapshot
    }

    /// Puts the jobs back, re-creating deleted ones.
    func undo(_ snapshot: UndoSnapshot) {
        let current = Dictionary(fetchAll().map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        for record in snapshot.records {
            if let existing = current[record.id] {
                record.apply(to: existing)
            } else {
                modelContext.insert(record.makeJob())
            }
        }
        save()
    }

    /// Done / not done. Repeating jobs cycle instead (or wake up if resting). A parent takes its
    /// open subtasks with it, stamped with its own completedAt; reopening it reopens just those.
    /// Returns an undo snapshot.
    @discardableResult
    func toggleDone(_ job: Job, allJobs: [Job]) -> UndoSnapshot {
        let family: [Job]
        if let parentId = job.parentId {
            family = allJobs.filter { $0.id == parentId || $0.parentId == parentId }
        } else {
            family = [job] + allJobs.filter { $0.parentId == job.id }
        }
        let snapshot = UndoSnapshot(records: family.map(JobRecord.init))
        let now = Date.now

        if let days = job.recurrenceDays {
            if job.isPending(now: now) {
                job.completedAt = now
                job.nextDueAt = nextDueAfterCompletion(recurrenceDays: days, repeatWeekdays: job.repeatWeekdays, completedAt: now)
                job.completionCount += 1
            } else {
                job.nextDueAt = nil
            }
        } else if job.isDone {
            let completedAt = job.completedAt
            job.isDone = false
            job.completedAt = nil
            if let parentId = job.parentId {
                reopenParentIfDone(parentId: parentId, allJobs: allJobs)
            } else if let completedAt {
                for subtask in allJobs where subtask.parentId == job.id && subtask.isDone && subtask.completedAt == completedAt {
                    subtask.isDone = false
                    subtask.completedAt = nil
                }
            }
        } else {
            job.isDone = true
            job.completedAt = now
            if let parentId = job.parentId {
                autoCompleteParentIfFinished(parentId: parentId, allJobs: allJobs, now: now)
            } else {
                for subtask in allJobs where subtask.parentId == job.id && !subtask.isDone {
                    subtask.isDone = true
                    subtask.completedAt = now
                }
            }
        }
        save()
        return snapshot
    }

    private func autoCompleteParentIfFinished(parentId: UUID, allJobs: [Job], now: Date) {
        let siblings = allJobs.filter { $0.parentId == parentId }
        guard !siblings.isEmpty, siblings.allSatisfy({ $0.isDone }) else { return }
        guard let parent = allJobs.first(where: { $0.id == parentId }),
              !parent.isDone, parent.recurrenceDays == nil else { return }
        // A hair after the last subtask, so the parent never shares a timestamp with one you ticked
        // yourself.
        let latest = siblings.compactMap(\.completedAt).max() ?? now
        parent.isDone = true
        parent.completedAt = max(now, latest.addingTimeInterval(0.001))
    }

    /// Reopening a subtask reopens its parent.
    private func reopenParentIfDone(parentId: UUID, allJobs: [Job]) {
        guard let parent = allJobs.first(where: { $0.id == parentId }), parent.isDone else { return }
        parent.isDone = false
        parent.completedAt = nil
    }

    /// Runs on launch: detaches subtasks from repeating jobs (older data) and merges categories
    /// that only differ by case.
    func applyLaunchRules() {
        let all = fetchAll()
        var changed = false
        let repeatingIds = Set(all.filter { $0.parentId == nil && $0.recurrenceDays != nil }.map(\.id))
        for job in all where job.parentId.map({ repeatingIds.contains($0) }) == true {
            job.parentId = nil
            job.dependsOnSubtaskId = nil
            changed = true
        }
        let groups = Dictionary(grouping: all.filter { !$0.category.isEmpty }) { $0.category.lowercased() }
        for group in groups.values {
            let counts = Dictionary(grouping: group, by: \.category).mapValues(\.count)
            guard counts.count > 1, let winner = counts.max(by: { $0.value < $1.value })?.key else { continue }
            for job in group where job.category != winner {
                job.category = winner
                changed = true
            }
        }
        if changed { save() }
    }

    /// Import: replaces everything.
    func replaceAll(with records: [JobRecord]) {
        for job in fetchAll() {
            modelContext.delete(job)
        }
        for record in records {
            modelContext.insert(record.makeJob())
        }
        save()
    }

    /// Random job that fits `maxMinutes`, or with `longOnly` one of `longJobMinutes` or more.
    /// Skips anything not due, and subtasks waiting on a sibling.
    func drawJob(allJobs: [Job], maxMinutes: Int, category: String?, excludeIds: Set<UUID>, longOnly: Bool) -> Job? {
        let subtasksByParent = Dictionary(grouping: allJobs.filter { $0.parentId != nil }) { $0.parentId! }
        let subtasksById = Dictionary(uniqueKeysWithValues: subtasksByParent.values.flatMap { $0 }.map { ($0.id, $0) })

        let candidates = allJobs.filter { job in
            guard job.isPending() else { return false }
            guard !excludeIds.contains(job.id) else { return false }
            if let category, job.category != category { return false }
            if job.parentId != nil && !job.isUnblocked(siblingsById: subtasksById) { return false }
            let needed = minutesNeeded(job: job, subtasksByParent: subtasksByParent)
            return longOnly ? needed >= longJobMinutes : needed <= maxMinutes
        }

        guard let picked = candidates.randomElement() else { return nil }
        picked.timesDrawn += 1
        save()
        return picked
    }

    private func minutesNeeded(job: Job, subtasksByParent: [UUID: [Job]]) -> Int {
        if job.parentId == nil {
            return job.remainingMinutes(subtasks: subtasksByParent[job.id] ?? [])
        }
        return job.estimatedMinutes
    }
}
