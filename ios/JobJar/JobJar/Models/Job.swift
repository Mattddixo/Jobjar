import Foundation
import SwiftData

/// "4+ hrs" on the form and the Jar.
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

    /// For the priority sort (high first).
    var ordinal: Int {
        switch self {
        case .low: return 0
        case .normal: return 1
        case .high: return 2
        }
    }
}

/// A job, or a subtask if `parentId` is set (one level only). Repeating jobs can't be subtasks
/// or have subtasks.
///
/// Repeating jobs never stay done: completing one moves `nextDueAt` on and bumps
/// `completionCount`. Nil `nextDueAt` means it's due now.
@Model
final class Job: Identifiable {
    var id: UUID = UUID()
    var title: String = ""
    var notes: String = ""
    var estimatedMinutes: Int = 0
    var category: String = ""
    var priority: Priority = Priority.normal
    var isDone: Bool = false
    /// Being worked on right now. Never true at the same time as `isDone`.
    var isInProgress: Bool = false
    var createdAt: Date = Date.now
    var completedAt: Date?
    var timesDrawn: Int = 0
    var parentId: UUID?
    var recurrenceDays: Int?
    /// Set = repeats on these weekdays. Monday is bit 0, same as Android and the export file.
    var repeatWeekdays: Int?
    var nextDueAt: Date?
    var completionCount: Int = 0
    /// Subtasks only: a sibling that has to be done first.
    var dependsOnSubtaskId: UUID?
    /// When the job is booked for. Booked jobs stay out of the jar. Never set on repeating jobs.
    var scheduledDate: Date?
    /// The calendar event for `scheduledDate`, if one was written (EventKit's identifier).
    var calendarEventId: String?
    /// What the job costs, in hundredths of the currency (cents). Nil = no cost entered. A
    /// parent's own cost doesn't include its subtasks; see `totalCostCents`.
    var costCents: Int?
    /// The checklist as JSON. Use `checklist`; this is just how it's stored.
    var checklistJSON: String?

    init(
        id: UUID = UUID(),
        title: String,
        notes: String = "",
        estimatedMinutes: Int,
        category: String = "",
        priority: Priority = .normal,
        isDone: Bool = false,
        isInProgress: Bool = false,
        createdAt: Date = .now,
        completedAt: Date? = nil,
        timesDrawn: Int = 0,
        parentId: UUID? = nil,
        recurrenceDays: Int? = nil,
        repeatWeekdays: Int? = nil,
        nextDueAt: Date? = nil,
        completionCount: Int = 0,
        dependsOnSubtaskId: UUID? = nil,
        scheduledDate: Date? = nil,
        calendarEventId: String? = nil,
        costCents: Int? = nil,
        checklist: [ChecklistItem] = []
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
    }
}

/// One line of a job's checklist. Checklists are lighter than subtasks: no time, no cost, never
/// drawn, and ticking them doesn't finish the job.
struct ChecklistItem: Codable, Equatable, Hashable {
    var text: String
    var checked: Bool = false

    init(text: String, checked: Bool = false) {
        self.text = text
        self.checked = checked
    }

    /// Lenient, so a hand-edited backup with a missing field still imports.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        text = (try? container.decode(String.self, forKey: .text)) ?? ""
        checked = (try? container.decode(Bool.self, forKey: .checked)) ?? false
    }
}

/// Trims items and drops blank ones, so the stored list is always clean.
func cleanChecklist(_ items: [ChecklistItem]) -> [ChecklistItem] {
    items
        .map { ChecklistItem(text: $0.text.trimmingCharacters(in: .whitespacesAndNewlines), checked: $0.checked) }
        .filter { !$0.text.isEmpty }
}

extension Job {
    /// Empty = no checklist. Only shown on the job page.
    var checklist: [ChecklistItem] {
        get {
            guard let data = checklistJSON?.data(using: .utf8) else { return [] }
            return (try? JSONDecoder().decode([ChecklistItem].self, from: data)) ?? []
        }
        set {
            let cleaned = cleanChecklist(newValue)
            if cleaned.isEmpty {
                checklistJSON = nil
            } else if let data = try? JSONEncoder().encode(cleaned) {
                checklistJSON = String(data: data, encoding: .utf8)
            }
        }
    }
}

extension Job {
    /// Due now? For repeating jobs that's about `nextDueAt`, not `isDone`.
    func isPending(now: Date = .now) -> Bool {
        if recurrenceDays != nil {
            guard let nextDueAt else { return true }
            return nextDueAt <= now
        }
        return !isDone
    }

    /// Could the jar hand this out right now? Due, and not already started or booked.
    func isAvailableToDraw(now: Date = .now) -> Bool {
        isPending(now: now) && !isInProgress && scheduledDate == nil
    }

    /// Subtasks of a job you've already started don't get drawn on their own.
    func isParentAvailable(_ parent: Job?) -> Bool {
        parent?.isInProgress != true
    }

    /// Is the prerequisite done? Only affects the draw, you can still tick it off.
    func isUnblocked(siblingsById: [UUID: Job]) -> Bool {
        guard let prerequisiteId = dependsOnSubtaskId else { return true }
        return siblingsById[prerequisiteId]?.isDone ?? true
    }

    func remainingMinutes(subtasks: [Job]) -> Int {
        remainingMinutesOf(estimatedMinutes: estimatedMinutes, subtasks: subtasks)
    }
}

/// The job's cost plus its subtasks' costs. Nil if none of them has a cost, so jobs without
/// costs don't show "$0" anywhere.
func totalCostCents(_ job: Job, subtasks: [Job]) -> Int? {
    let costs = ([job] + subtasks).compactMap(\.costCents)
    return costs.isEmpty ? nil : costs.reduce(0, +)
}

/// Parent estimate minus finished subtasks. Can go negative.
func remainingMinutesOf(estimatedMinutes: Int, subtasks: [Job]) -> Int {
    estimatedMinutes - subtasks.filter { $0.isDone }.reduce(0) { $0 + $1.estimatedMinutes }
}

/// Siblings that `excludingSelfId` could depend on without making a loop.
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
