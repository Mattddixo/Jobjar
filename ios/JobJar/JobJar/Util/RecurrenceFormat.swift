import Foundation

private let dayInterval: TimeInterval = 24 * 60 * 60

/// Friendly label for a "repeats every N days" interval.
func formatRecurrenceInterval(_ days: Int) -> String {
    switch days {
    case 1: return "Daily"
    case 7: return "Weekly"
    case 14: return "Biweekly"
    case 30: return "Monthly"
    default: return "Every \(days) days"
    }
}

/// "Due now" if nextDueAt is nil (never completed yet) or already passed, otherwise how far
/// out the next occurrence is. Rounds up so "due tomorrow morning" doesn't read as "in 0 days".
func formatDueStatus(_ nextDueAt: Date?, now: Date = .now) -> String {
    guard let nextDueAt, nextDueAt > now else { return "Due now" }
    let daysAway = Int(ceil(nextDueAt.timeIntervalSince(now) / dayInterval))
    return daysAway == 1 ? "Next: tomorrow" : "Next: in \(daysAway) days"
}
