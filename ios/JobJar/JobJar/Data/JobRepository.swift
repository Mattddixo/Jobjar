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
    var notes: String = ""
    var estimatedMinutes: Int
    var category: String = ""
    var priority: Priority = .normal
    var isDone: Bool = false
    var isInProgress: Bool = false
    var createdAt: Date = .now
    var completedAt: Date?
    var timesDrawn: Int = 0
    var parentId: UUID?
    var recurrenceDays: Int?
    var repeatWeekdays: Int?
    var nextDueAt: Date?
    var completionCount: Int = 0
    var dependsOnSubtaskId: UUID?
    var scheduledDate: Date?
    var calendarEventId: String?

    init(
        id: UUID, title: String, notes: String = "", estimatedMinutes: Int, category: String = "",
        priority: Priority = .normal, isDone: Bool = false, isInProgress: Bool = false, createdAt: Date = .now,
        completedAt: Date? = nil, timesDrawn: Int = 0, parentId: UUID? = nil, recurrenceDays: Int? = nil,
        repeatWeekdays: Int? = nil, nextDueAt: Date? = nil, completionCount: Int = 0,
        dependsOnSubtaskId: UUID? = nil, scheduledDate: Date? = nil, calendarEventId: String? = nil
    ) {
        self.id = id
        self.title = title
        self.notes = notes
        self.estimatedMinutes = estimatedMinutes
        self.category = category
        self.priority = priority
        self.isDone = isDone
        self.isInProgress = isInProgress
        self.createdAt = createdAt
        self.completedAt = completedAt
        self.timesDrawn = timesDrawn
        self.parentId = parentId
        self.recurrenceDays = recurrenceDays
        self.repeatWeekdays = repeatWeekdays
        self.nextDueAt = nextDueAt
        self.completionCount = completionCount
        self.dependsOnSubtaskId = dependsOnSubtaskId
        self.scheduledDate = scheduledDate
        self.calendarEventId = calendarEventId
    }

    init(_ job: Job) {
        self.init(
            id: job.id, title: job.title, notes: job.notes, estimatedMinutes: job.estimatedMinutes,
            category: job.category, priority: job.priority, isDone: job.isDone, isInProgress: job.isInProgress,
            createdAt: job.createdAt, completedAt: job.completedAt, timesDrawn: job.timesDrawn,
            parentId: job.parentId, recurrenceDays: job.recurrenceDays, repeatWeekdays: job.repeatWeekdays,
            nextDueAt: job.nextDueAt, completionCount: job.completionCount,
            dependsOnSubtaskId: job.dependsOnSubtaskId, scheduledDate: job.scheduledDate,
            calendarEventId: job.calendarEventId
        )
    }

    func apply(to job: Job) {
        job.title = title
        job.notes = notes
        job.estimatedMinutes = estimatedMinutes
        job.category = category
        job.priority = priority
        job.isDone = isDone
        job.isInProgress = isInProgress
        job.createdAt = createdAt
        job.completedAt = completedAt
        job.timesDrawn = timesDrawn
        job.parentId = parentId
        job.recurrenceDays = recurrenceDays
        job.repeatWeekdays = repeatWeekdays
        job.nextDueAt = nextDueAt
        job.completionCount = completionCount
        job.dependsOnSubtaskId = dependsOnSubtaskId
        job.scheduledDate = scheduledDate
        job.calendarEventId = calendarEventId
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

/// A drawn job and how many minutes it counts against the time budget.
struct DrawPick {
    let job: Job
    let minutesUsed: Int
}

/// All the job rules live here (same as the Android repository). Views read through @Query
/// and pass their `allJobs` in. Calendar calls go through `calendar` so tests can fake it.
@MainActor
struct JobRepository {
    let modelContext: ModelContext
    var calendar: CalendarGateway = DeviceCalendar.shared
    var now: () -> Date = { .now }

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

    private func end(of job: Job, startingAt start: Date) -> Date {
        start.addingTimeInterval(Double(job.estimatedMinutes) * 60)
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
            createdAt: now(),
            parentId: parentId,
            recurrenceDays: repeats ? edit.recurrenceDays : nil,
            repeatWeekdays: weekdays,
            nextDueAt: repeats ? initialNextDue(repeatWeekdays: weekdays, now: now()) : nil,
            dependsOnSubtaskId: parentId != nil ? edit.dependsOnSubtaskId : nil
        )
        modelContext.insert(job)
        if let parentId {
            growParentToFitSubtasks(parentId: parentId, allJobs: allJobs + [job])
        }
        save()
        return job
    }

    /// Saves the form onto `job`.
    ///
    /// - Jobs with subtasks can't repeat.
    /// - Turning repeat on clears done and any booking.
    /// - Switching repeat type restarts the cycle. Changing the number of days doesn't.
    /// - If it's booked, the calendar event gets the new title, length and notes.
    func updateJob(_ job: Job, with edit: JobEdit, allJobs: [Job]) {
        let before = JobRecord(job)
        let hasSubtasks = allJobs.contains { $0.parentId == job.id }
        let repeats = edit.recurrenceDays != nil && job.parentId == nil && !hasSubtasks
        let newDays = repeats ? edit.recurrenceDays : nil
        let newWeekdays = repeats ? validWeekdays(edit.repeatWeekdays) : nil

        let nextDue: Date?
        if newDays == nil {
            nextDue = nil
        } else if newWeekdays != job.repeatWeekdays {
            nextDue = initialNextDue(repeatWeekdays: newWeekdays, now: now())
        } else if job.recurrenceDays == nil {
            nextDue = nil
        } else {
            nextDue = job.nextDueAt
        }

        if newDays != nil {
            job.isDone = false
            clearSchedule(job)
        }
        job.title = edit.title.trimmingCharacters(in: .whitespacesAndNewlines)
        job.notes = edit.notes.trimmingCharacters(in: .whitespacesAndNewlines)
        job.estimatedMinutes = edit.estimatedMinutes
        job.category = canonicalCategory(edit.category, excluding: job.id, allJobs: allJobs)
        job.priority = edit.priority
        job.recurrenceDays = newDays
        job.repeatWeekdays = newWeekdays
        job.nextDueAt = nextDue
        job.dependsOnSubtaskId = job.parentId != nil && edit.dependsOnSubtaskId != job.id ? edit.dependsOnSubtaskId : nil
        if let parentId = job.parentId {
            growParentToFitSubtasks(parentId: parentId, allJobs: allJobs)
        }

        if let eventId = job.calendarEventId, let start = job.scheduledDate,
           job.title != before.title || job.estimatedMinutes != before.estimatedMinutes || job.notes != before.notes {
            _ = calendar.updateEvent(eventId, owner: job, title: job.title, notes: job.notes, start: start, end: end(of: job, startingAt: start))
        }
        save()
    }

    /// Deletes the job (and its subtasks if it's a parent), plus their calendar events. Siblings
    /// that depended on a deleted subtask lose the dependency. Returns an undo snapshot.
    @discardableResult
    func deleteJob(_ job: Job, allJobs: [Job]) -> UndoSnapshot {
        let removed = job.parentId == nil ? [job] + allJobs.filter { $0.parentId == job.id } : [job]
        let dependents = job.parentId == nil ? [] : allJobs.filter { $0.parentId == job.parentId && $0.dependsOnSubtaskId == job.id }
        let snapshot = UndoSnapshot(records: (removed + dependents).map(JobRecord.init))
        for dependent in dependents {
            dependent.dependsOnSubtaskId = nil
        }
        for row in removed {
            if let eventId = row.calendarEventId {
                _ = calendar.deleteEvent(eventId, owner: row)
            }
            modelContext.delete(row)
        }
        save()
        return snapshot
    }

    /// Puts the jobs back, re-creating deleted ones and any calendar events the change removed.
    func undo(_ snapshot: UndoSnapshot) {
        let current = Dictionary(fetchAll().map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var restored: [Job] = []
        for record in snapshot.records {
            if let existing = current[record.id] {
                record.apply(to: existing)
                restored.append(existing)
            } else {
                let job = record.makeJob()
                modelContext.insert(job)
                restored.append(job)
            }
        }
        for job in restored {
            guard let start = job.scheduledDate else { continue }
            if let eventId = job.calendarEventId, calendar.ownsEvent(eventId, owner: job) { continue }
            job.calendarEventId = calendar.insertEvent(for: job, start: start, end: end(of: job, startingAt: start))
        }
        save()
    }

    /// Done / not done.
    ///
    /// - Repeating: completes the cycle, or if it's resting, makes it due now.
    /// - Otherwise flips isDone. Completing clears any booking, and a parent takes its open
    ///   subtasks with it, stamped with its own completedAt. Reopening the parent reopens just
    ///   those; ones you finished yourself stay done.
    ///
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
        let now = now()

        if let days = job.recurrenceDays {
            if job.isPending(now: now) {
                job.completedAt = now
                job.nextDueAt = nextDueAfterCompletion(recurrenceDays: days, repeatWeekdays: job.repeatWeekdays, completedAt: now)
                job.completionCount += 1
                job.isInProgress = false
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
            markDone(job, at: now)
            if let parentId = job.parentId {
                autoCompleteParentIfFinished(parentId: parentId, allJobs: allJobs, now: now)
            } else {
                for subtask in allJobs where subtask.parentId == job.id && !subtask.isDone {
                    markDone(subtask, at: now)
                }
            }
        }
        save()
        return snapshot
    }

    private func markDone(_ job: Job, at time: Date) {
        clearSchedule(job)
        job.isDone = true
        job.isInProgress = false
        job.completedAt = time
    }

    private func autoCompleteParentIfFinished(parentId: UUID, allJobs: [Job], now: Date) {
        let siblings = allJobs.filter { $0.parentId == parentId }
        guard !siblings.isEmpty, siblings.allSatisfy({ $0.isDone }) else { return }
        guard let parent = allJobs.first(where: { $0.id == parentId }),
              !parent.isDone, parent.recurrenceDays == nil else { return }
        // A hair after the last subtask, so the parent never shares a timestamp with one you ticked
        // yourself.
        let latest = siblings.compactMap(\.completedAt).max() ?? now
        markDone(parent, at: max(now, latest.addingTimeInterval(0.001)))
    }

    /// Reopening a subtask reopens its parent.
    private func reopenParentIfDone(parentId: UUID, allJobs: [Job]) {
        guard let parent = allJobs.first(where: { $0.id == parentId }), parent.isDone else { return }
        parent.isDone = false
        parent.completedAt = nil
    }

    /// Start / put back in the jar. Starting a booked job unbooks it.
    func toggleInProgress(_ job: Job) {
        if job.isInProgress {
            job.isInProgress = false
        } else {
            clearSchedule(job)
            job.isInProgress = true
        }
        save()
    }

    /// Books a job and writes a calendar event for it (moving the existing one if there is
    /// one). Booked jobs stay out of the jar and aren't in progress. Repeating jobs can't be
    /// booked. Returns whether a calendar event was written; the booking is saved either way.
    @discardableResult
    func scheduleJob(_ job: Job, at start: Date) -> Bool {
        guard job.recurrenceDays == nil else { return false }
        let finish = end(of: job, startingAt: start)
        var eventId: String?
        if let existing = job.calendarEventId,
           calendar.updateEvent(existing, owner: job, title: job.title, notes: job.notes, start: start, end: finish) {
            eventId = existing
        } else {
            eventId = calendar.insertEvent(for: job, start: start, end: finish)
        }
        job.scheduledDate = start
        job.calendarEventId = eventId
        job.isInProgress = false
        save()
        return eventId != nil
    }

    /// Unbooks a job and deletes its calendar event.
    func unscheduleJob(_ job: Job) {
        clearSchedule(job)
        save()
    }

    private func clearSchedule(_ job: Job) {
        if let eventId = job.calendarEventId {
            _ = calendar.deleteEvent(eventId, owner: job)
        }
        job.scheduledDate = nil
        job.calendarEventId = nil
    }

    /// "Removing" a category just blanks it on every job that has it.
    func removeCategory(_ category: String, allJobs: [Job]) {
        for job in allJobs where job.category == category {
            job.category = ""
        }
        save()
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

    /// Import: replaces everything. The old jobs' calendar events go with them, and upcoming
    /// bookings in the file get new events on this phone (event ids aren't kept in backups).
    func replaceAll(with records: [JobRecord]) {
        for job in fetchAll() {
            if let eventId = job.calendarEventId {
                _ = calendar.deleteEvent(eventId, owner: job)
            }
            modelContext.delete(job)
        }
        let now = now()
        for record in records {
            let job = record.makeJob()
            modelContext.insert(job)
            if let start = job.scheduledDate, start > now {
                job.calendarEventId = calendar.insertEvent(for: job, start: start, end: end(of: job, startingAt: start))
            }
        }
        save()
    }

    /// Picks a random job that fits `maxMinutes`, or with `longOnly` one of `longJobMinutes` or
    /// more. Skips anything done, not due, started or booked, plus subtasks whose prerequisite
    /// isn't done or whose parent has been started. Parents count by their remaining minutes.
    func drawJob(allJobs: [Job], maxMinutes: Int, categories: Set<String>, excludeIds: Set<UUID>, longOnly: Bool) -> DrawPick? {
        let now = now()
        let byId = Dictionary(allJobs.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let subtasksByParent = Dictionary(grouping: allJobs.filter { $0.parentId != nil }) { $0.parentId! }
        let subtasksById = Dictionary(subtasksByParent.values.flatMap { $0 }.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })

        let candidates = allJobs.filter { job in
            guard job.isAvailableToDraw(now: now), !excludeIds.contains(job.id) else { return false }
            if !categories.isEmpty && !categories.contains(job.category) { return false }
            if let parentId = job.parentId {
                guard job.isUnblocked(siblingsById: subtasksById), job.isParentAvailable(byId[parentId]) else { return false }
            }
            let needed = minutesNeeded(job: job, subtasksByParent: subtasksByParent)
            return longOnly ? needed >= longJobMinutes : needed <= maxMinutes
        }

        guard let picked = candidates.randomElement() else { return nil }
        picked.timesDrawn += 1
        save()
        return DrawPick(job: picked, minutesUsed: minutesNeeded(job: picked, subtasksByParent: subtasksByParent))
    }

    private func minutesNeeded(job: Job, subtasksByParent: [UUID: [Job]]) -> Int {
        if job.parentId == nil {
            return job.remainingMinutes(subtasks: subtasksByParent[job.id] ?? [])
        }
        return job.estimatedMinutes
    }
}
