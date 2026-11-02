import Foundation
import SwiftData

/// One time a job got done, for the history. Kept even if the job is deleted later, so it copies
/// the title, length and category at the time. Same rules as Android: logged when you tick
/// something off (and when a parent finishes because its last subtask did); subtasks ticked off
/// along with their parent aren't logged separately.
@Model
final class Completion: Identifiable {
    var id: UUID = UUID()
    var jobId: UUID?
    var title: String = ""
    var estimatedMinutes: Int = 0
    var category: String = ""
    var completedAt: Date = Date.now

    init(id: UUID = UUID(), jobId: UUID?, title: String, estimatedMinutes: Int, category: String = "", completedAt: Date) {
        self.id = id
        self.jobId = jobId
        self.title = title
        self.estimatedMinutes = estimatedMinutes
        self.category = category
        self.completedAt = completedAt
    }
}
