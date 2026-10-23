import Foundation

/// Parent title or subtask progress, for the card.
struct DrawnJobContext {
    var parentTitle: String? = nil
    var subtaskDone: Int = 0
    var subtaskTotal: Int = 0
    var remainingMinutes: Int? = nil
}

@Observable
final class DrawViewModel {
    var availableMinutes: Int = 30
    /// 4+ hrs mode: ignores the time.
    var longJobsOnly: Bool = false
    var selectedCategory: String? = nil
    var drawnJob: Job? = nil
    var drawnContext: DrawnJobContext? = nil
    var excludedIds: Set<UUID> = []
    var isDrawing: Bool = false
    var noMatchFound: Bool = false

    func setAvailableMinutes(_ minutes: Int) {
        availableMinutes = minutes
        longJobsOnly = false
    }

    func setLongJobsOnly() {
        longJobsOnly = true
    }

    func setCategory(_ category: String?) {
        selectedCategory = category
    }

    /// `excludeCurrent` skips the job that's showing.
    @MainActor
    func draw(repository: JobRepository, allJobs: [Job], excludeCurrent: Bool = false) {
        let excludeSet: Set<UUID>
        if excludeCurrent, let current = drawnJob {
            excludeSet = excludedIds.union([current.id])
        } else {
            excludeSet = []
        }

        isDrawing = true
        noMatchFound = false
        let job = repository.drawJob(
            allJobs: allJobs,
            maxMinutes: availableMinutes,
            category: selectedCategory,
            excludeIds: excludeSet,
            longOnly: longJobsOnly
        )
        drawnJob = job
        drawnContext = job.map { buildContext(job: $0, allJobs: allJobs) }
        excludedIds = excludeSet
        isDrawing = false
        noMatchFound = job == nil
    }

    private func buildContext(job: Job, allJobs: [Job]) -> DrawnJobContext {
        if let parentId = job.parentId {
            let parentTitle = allJobs.first(where: { $0.id == parentId })?.title
            return DrawnJobContext(parentTitle: parentTitle)
        }
        let subtasks = allJobs.filter { $0.parentId == job.id }
        if subtasks.isEmpty { return DrawnJobContext() }
        return DrawnJobContext(
            subtaskDone: subtasks.filter { $0.isDone }.count,
            subtaskTotal: subtasks.count,
            remainingMinutes: job.remainingMinutes(subtasks: subtasks)
        )
    }

    func clearDraw() {
        drawnJob = nil
        drawnContext = nil
        excludedIds = []
        noMatchFound = false
    }

    @MainActor
    func completeDrawnJob(repository: JobRepository, allJobs: [Job]) -> (String, UndoSnapshot)? {
        guard let job = drawnJob else { return nil }
        let title = job.title
        let snapshot = repository.toggleDone(job, allJobs: allJobs)
        clearDraw()
        return (title, snapshot)
    }
}
