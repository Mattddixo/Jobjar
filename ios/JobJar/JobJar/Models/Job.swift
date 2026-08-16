import Foundation
import SwiftData

/// The threshold, in minutes, at which a job is treated as "long" - the "4+ hrs" option on
/// both the job form and the draw screen. Mirrors LONG_JOB_MINUTES in the Android app.
let longJobMinutes = 240

enum Priority: String, Codable, CaseIterable, Identifiable {
    case low, normal, high

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .low: return "Low"
        case .normal: return "Normal"
        case .high: return "High"
        }
    }

    /// Declaration order, used for "priority" sort (high first) - Codable's rawValue alone
    /// doesn't sort that way alphabetically.
    var ordinal: Int {
        switch self {
        case .low: return 0
        case .normal: return 1
        case .high: return 2
        }
    }
}

/// A job can optionally be a subtask of another job via [parentId]. Subtasks are one level
/// deep only - a job that already has a parent cannot itself have subtasks. Only a top-level
/// job (one that isn't itself a subtask) can repeat.
///
/// A repeating job (non-nil [recurrenceDays]) never persists [isDone] = true - completing it
/// instead advances [nextDueAt] and bumps [completionCount] (see JobRepository), so the same
/// row just cycles rather than piling up a new "done" row per occurrence. [nextDueAt] is the
/// schedule: nil means due right now; once completed it's set to that moment plus
/// [recurrenceDays] days, i.e. the schedule is relative to when you actually did it, not a
/// fixed calendar date.
@Model
final class Job: Identifiable {
    var id: UUID = UUID()
    var title: String = ""
    var notes: String = ""
    var estimatedMinutes: Int = 0
    var category: String = ""
    var priority: Priority = Priority.normal
    var isDone: Bool = false
    var createdAt: Date = Date.now
    var completedAt: Date?
    var timesDrawn: Int = 0
    var parentId: UUID?
    var recurrenceDays: Int?
    var nextDueAt: Date?
    var completionCount: Int = 0
    /// Only meaningful when this is itself a subtask: the sibling subtask (same parentId)
    /// that must be done first, if any.
    var dependsOnSubtaskId: UUID?

    init(
        id: UUID = UUID(),
        title: String,
        notes: String = "",
        estimatedMinutes: Int,
        category: String = "",
        priority: Priority = .normal,
        isDone: Bool = false,
        createdAt: Date = .now,
        completedAt: Date? = nil,
        timesDrawn: Int = 0,
        parentId: UUID? = nil,
        recurrenceDays: Int? = nil,
        nextDueAt: Date? = nil,
        completionCount: Int = 0,
        dependsOnSubtaskId: UUID? = nil
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
        self.nextDueAt = nextDueAt
        self.completionCount = completionCount
        self.dependsOnSubtaskId = dependsOnSubtaskId
    }
}

extension Job {
    /// Is this job something you could act on right now? For a repeating job that's a
    /// due-date check, not isDone (which a repeating job never persists as true).
    func isPending(now: Date = .now) -> Bool {
        if recurrenceDays != nil {
            guard let nextDueAt else { return true }
            return nextDueAt <= now
        }
        return !isDone
    }

    /// A blocked subtask (prerequisite not yet done) is only excluded from the random draw
    /// pool - it's still fully completable by hand at any time, out of order if you want.
    /// [siblingsById] should map every subtask sharing this job's parentId by id.
    func isUnblocked(siblingsById: [UUID: Job]) -> Bool {
        guard let prerequisiteId = dependsOnSubtaskId else { return true }
        return siblingsById[prerequisiteId]?.isDone ?? true
    }

    func remainingMinutes(subtasks: [Job]) -> Int {
        remainingMinutesOf(estimatedMinutes: estimatedMinutes, subtasks: subtasks)
    }
}

/// Minutes still "owed" against a parent's estimate: estimatedMinutes minus time already
/// accounted for by completed subtasks. Not clamped to zero, matching the Android version.
func remainingMinutesOf(estimatedMinutes: Int, subtasks: [Job]) -> Int {
    estimatedMinutes - subtasks.filter { $0.isDone }.reduce(0) { $0 + $1.estimatedMinutes }
}

/// Which of [siblings] (subtasks sharing the same parent) [excludingSelfId] could validly
/// depend on, without creating a cycle. A candidate is excluded if it already (transitively)
/// depends on the subtask being edited, since linking to it would close a loop.
func subtasksAvailableAsDependency(siblings: [Job], excludingSelfId: UUID?) -> [Job] {
    let byId = Dictionary(uniqueKeysWithValues: siblings.map { ($0.id, $0) })
    func eventuallyDependsOn(startId: UUID, targetId: UUID) -> Bool {
        var seen = Set<UUID>()
        var current: UUID? = startId
        while let c = current, seen.insert(c).inserted {
            if c == targetId { return true }
            current = byId[c]?.dependsOnSubtaskId
        }
        return false
    }
    return siblings.filter { candidate in
        candidate.id != excludingSelfId &&
            (excludingSelfId == nil || !eventuallyDependsOn(startId: candidate.id, targetId: excludingSelfId!))
    }
}
