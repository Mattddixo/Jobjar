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
    var costCents: Int? = nil
    var repeatUnit: RepeatUnit = .day
    var repeatAnchor: RepeatAnchor = .completion
    var repeatMonthDay: Int? = nil
    var repeatMonth: Int? = nil
    var leadDays: Int = 0

    var repeatFields: RepeatFields {
        get {
            RepeatFields(
                recurrenceDays: recurrenceDays, repeatWeekdays: repeatWeekdays, repeatUnit: repeatUnit,
                repeatAnchor: repeatAnchor, repeatMonthDay: repeatMonthDay, repeatMonth: repeatMonth, leadDays: leadDays
            )
        }
        set {
            recurrenceDays = newValue.recurrenceDays
            repeatWeekdays = newValue.repeatWeekdays
            repeatUnit = newValue.repeatUnit
            repeatAnchor = newValue.repeatAnchor
            repeatMonthDay = newValue.repeatMonthDay
            repeatMonth = newValue.repeatMonth
            leadDays = newValue.leadDays
        }
    }

    /// Nil = doesn't repeat.
    var rule: RepeatRule? {
        get { repeatFields.rule }
        set { repeatFields = RepeatFields(rule: newValue) }
    }
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
    var costCents: Int?
    var checklist: [ChecklistItem] = []
    var repeatUnit: RepeatUnit = .day
    var repeatAnchor: RepeatAnchor = .completion
    var repeatMonthDay: Int?
    var repeatMonth: Int?
    var leadDays: Int = 0

    init(
        id: UUID, title: String, notes: String = "", estimatedMinutes: Int, category: String = "",
        priority: Priority = .normal, isDone: Bool = false, isInProgress: Bool = false, createdAt: Date = .now,
        completedAt: Date? = nil, timesDrawn: Int = 0, parentId: UUID? = nil, recurrenceDays: Int? = nil,
        repeatWeekdays: Int? = nil, nextDueAt: Date? = nil, completionCount: Int = 0,
        dependsOnSubtaskId: UUID? = nil, scheduledDate: Date? = nil, calendarEventId: String? = nil,
        costCents: Int? = nil, checklist: [ChecklistItem] = [], repeatUnit: RepeatUnit = .day,
        repeatAnchor: RepeatAnchor = .completion, repeatMonthDay: Int? = nil, repeatMonth: Int? = nil, leadDays: Int = 0
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
        self.costCents = costCents
        self.checklist = checklist
        self.repeatUnit = repeatUnit
        self.repeatAnchor = repeatAnchor
        self.repeatMonthDay = repeatMonthDay
        self.repeatMonth = repeatMonth
        self.leadDays = leadDays
    }

    init(_ job: Job) {
        self.init(
            id: job.id, title: job.title, notes: job.notes, estimatedMinutes: job.estimatedMinutes,
            category: job.category, priority: job.priority, isDone: job.isDone, isInProgress: job.isInProgress,
            createdAt: job.createdAt, completedAt: job.completedAt, timesDrawn: job.timesDrawn,
            parentId: job.parentId, recurrenceDays: job.recurrenceDays, repeatWeekdays: job.repeatWeekdays,
            nextDueAt: job.nextDueAt, completionCount: job.completionCount,
            dependsOnSubtaskId: job.dependsOnSubtaskId, scheduledDate: job.scheduledDate,
            calendarEventId: job.calendarEventId, costCents: job.costCents, checklist: job.checklist,
            repeatUnit: job.repeatUnit, repeatAnchor: job.repeatAnchor, repeatMonthDay: job.repeatMonthDay,
            repeatMonth: job.repeatMonth, leadDays: job.leadDays
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
        job.costCents = costCents
        job.checklist = checklist
        job.repeatUnit = repeatUnit
        job.repeatAnchor = repeatAnchor
        job.repeatMonthDay = repeatMonthDay
        job.repeatMonth = repeatMonth
        job.leadDays = leadDays
    }

    var repeatFields: RepeatFields {
        RepeatFields(
            recurrenceDays: recurrenceDays, repeatWeekdays: repeatWeekdays, repeatUnit: repeatUnit,
            repeatAnchor: repeatAnchor, repeatMonthDay: repeatMonthDay, repeatMonth: repeatMonth, leadDays: leadDays
        )
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
    /// A random number in 0..<1. Tests swap in a seeded one.
    var randomUnit: () -> Double = { Double.random(in: 0..<1) }

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
        let rule = parentId == nil ? edit.rule : nil
        let job = Job(
            title: edit.title.trimmingCharacters(in: .whitespacesAndNewlines),
            notes: edit.notes.trimmingCharacters(in: .whitespacesAndNewlines),
            estimatedMinutes: edit.estimatedMinutes,
            category: canonicalCategory(edit.category, excluding: nil, allJobs: allJobs),
            priority: edit.priority,
            createdAt: now(),
            parentId: parentId,
            nextDueAt: rule?.initialDue(now: now()),
            dependsOnSubtaskId: parentId != nil ? edit.dependsOnSubtaskId : nil,
            costCents: edit.costCents.map { max($0, 0) }
        )
        job.repeatFields = RepeatFields(rule: rule)
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
    /// - Changing the kind of repeat (unit, weekdays, the date, what it counts from) restarts the
    ///   cycle. Changing just the number or the lead window doesn't.
    /// - If it's booked, the calendar event gets the new title, length and notes.
    func updateJob(_ job: Job, with edit: JobEdit, allJobs: [Job]) {
        let before = JobRecord(job)
        let hasSubtasks = allJobs.contains { $0.parentId == job.id }
        let rule = job.parentId == nil && !hasSubtasks ? edit.rule : nil
        let oldRule = job.repeatRule

        let nextDue: Date?
        if let rule {
            if let oldRule, rule.sameShape(as: oldRule) {
                nextDue = job.nextDueAt
            } else {
                nextDue = rule.initialDue(now: now())
            }
        } else {
            nextDue = nil
        }

        if rule != nil {
            job.isDone = false
            clearSchedule(job)
        }
        job.title = edit.title.trimmingCharacters(in: .whitespacesAndNewlines)
        job.notes = edit.notes.trimmingCharacters(in: .whitespacesAndNewlines)
        job.estimatedMinutes = edit.estimatedMinutes
        job.category = canonicalCategory(edit.category, excluding: job.id, allJobs: allJobs)
        job.priority = edit.priority
        job.costCents = edit.costCents.map { max($0, 0) }
        job.repeatFields = RepeatFields(rule: rule)
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
            removeUpcomingEvent(row)
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
        let now = now()
        for job in restored {
            // Past slots were never deleted (see removeUpcomingEvent), so there's nothing to re-add.
            guard let start = job.scheduledDate, !job.bookingIsOver(now: now) else { continue }
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

        if job.recurrenceDays != nil {
            if job.isPending(now: now) {
                cycle(job, now: now)
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

    /// Finishes a repeating job's current cycle even if it isn't due yet (ticking it on Coming
    /// up). Nil if it doesn't repeat.
    @discardableResult
    func completeCycle(_ job: Job) -> UndoSnapshot? {
        guard job.recurrenceDays != nil else { return nil }
        let snapshot = UndoSnapshot(records: [JobRecord(job)])
        cycle(job, now: now())
        save()
        return snapshot
    }

    /// Next cycle: bump the count, set the next due date, clear in-progress and the checklist ticks.
    private func cycle(_ job: Job, now: Date) {
        guard let rule = job.repeatRule else { return }
        job.nextDueAt = rule.nextDue(previousDue: job.nextDueAt, completedAt: now)
        job.completedAt = now
        job.completionCount += 1
        job.isInProgress = false
        if job.checklist.contains(where: \.checked) {
            job.checklist = job.checklist.map { ChecklistItem(text: $0.text, checked: false) }
        }
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

    /// Books a job and writes a calendar event for it. Booked jobs stay out of the jar and aren't
    /// in progress. Repeating jobs can't be booked. Returns whether a calendar event was written;
    /// the booking is saved either way.
    ///
    /// Rescheduling writes a fresh event and removes the old one, same as Android (where moving an
    /// event deleted in the calendar app but not yet synced would leave the booking without one).
    /// A missed booking's past event stays as a record (see removeUpcomingEvent).
    @discardableResult
    func scheduleJob(_ job: Job, at start: Date) -> Bool {
        guard job.recurrenceDays == nil else { return false }
        let eventId = calendar.insertEvent(for: job, start: start, end: end(of: job, startingAt: start))
        if let previous = job.calendarEventId, previous != eventId {
            removeUpcomingEvent(job)
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
        removeUpcomingEvent(job)
        job.scheduledDate = nil
        job.calendarEventId = nil
    }

    /// Deletes the job's calendar event, unless its slot is already over. A past event is a record
    /// of when you'd planned it, so unbooking only unlinks it.
    private func removeUpcomingEvent(_ job: Job) {
        guard let eventId = job.calendarEventId, !job.bookingIsOver(now: now()) else { return }
        _ = calendar.deleteEvent(eventId, owner: job)
    }

    /// Picks up changes made in the Calendar app, for every booked job with an event: a moved event
    /// moves the booking, a deleted one unbooks the job, and an event whose id changed gets
    /// relinked. Anything the calendar can't vouch for is left alone. Run when the app becomes
    /// active.
    func syncCalendar() {
        var changed = false
        for job in fetchAll() {
            guard job.scheduledDate != nil, let eventId = job.calendarEventId else { continue }
            switch calendar.findEvent(eventId, owner: job) {
            case .unknown:
                continue
            case .missing:
                job.scheduledDate = nil
                job.calendarEventId = nil
                changed = true
            case .found(let foundId, let start):
                if foundId != eventId || start != job.scheduledDate {
                    job.calendarEventId = foundId
                    job.scheduledDate = start
                    changed = true
                }
            }
        }
        if changed { save() }
    }

    /// Applies `change` to the job's checklist and saves it (trimmed, blank lines dropped).
    func updateChecklist(_ job: Job, _ change: ([ChecklistItem]) -> [ChecklistItem]) {
        job.checklist = change(job.checklist)
        save()
    }

    /// Removes the whole checklist. Returns an undo snapshot, or nil if there wasn't one.
    @discardableResult
    func deleteChecklist(_ job: Job) -> UndoSnapshot? {
        guard !job.checklist.isEmpty else { return nil }
        let snapshot = UndoSnapshot(records: [JobRecord(job)])
        job.checklist = []
        save()
        return snapshot
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
            removeUpcomingEvent(job)
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
    ///
    /// Priority always counts (`priorityWeight`). With `favourFill`, jobs closer to a fair share of
    /// the time (`maxMinutes` over `picksLeft`, see `drawWeight`) are also more likely; 4+ hrs draws
    /// ignore length, since every candidate is long.
    func drawJob(
        allJobs: [Job], maxMinutes: Int, categories: Set<String>, excludeIds: Set<UUID>, longOnly: Bool,
        picksLeft: Int? = 1, favourFill: Bool = false
    ) -> DrawPick? {
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

        let weighByFit = favourFill && !longOnly
        let picked = pickWeighted(candidates, unit: randomUnit()) { job in
            let fit = weighByFit
                ? drawWeight(neededMinutes: minutesNeeded(job: job, subtasksByParent: subtasksByParent), remainingMinutes: maxMinutes, picksLeft: picksLeft)
                : 1
            // Overdue repeating jobs count as high priority, whatever they're set to.
            return fit * priorityWeight(job.isOverdue(now: now) ? .high : job.priority)
        }
        guard let picked else { return nil }
        picked.timesDrawn += 1
        save()
        // A parent whose subtasks ran over has nothing left; it mustn't give time back to a batch.
        return DrawPick(job: picked, minutesUsed: max(minutesNeeded(job: picked, subtasksByParent: subtasksByParent), 0))
    }

    private func minutesNeeded(job: Job, subtasksByParent: [UUID: [Job]]) -> Int {
        if job.parentId == nil {
            return job.remainingMinutes(subtasks: subtasksByParent[job.id] ?? [])
        }
        return job.estimatedMinutes
    }
}
