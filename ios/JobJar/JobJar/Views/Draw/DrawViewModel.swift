import Foundation

/// Parent title or subtask progress, for the card.
struct DrawnJobContext {
    var parentTitle: String? = nil
    var subtaskDone: Int = 0
    var subtaskTotal: Int = 0
    var remainingMinutes: Int? = nil
}

struct DrawnJobEntry: Identifiable {
    /// Copied out of `job` up front: once a job's been deleted its properties can't be read.
    let id: UUID
    let job: Job
    let context: DrawnJobContext
    let minutesUsed: Int
}

/// Jobs per draw. `all` = as many as fit, up to `maxBatchJobs`.
enum DrawBatchSize: String, CaseIterable, Identifiable {
    case one, two, three, four, all

    var id: String { rawValue }

    var label: String {
        switch self {
        case .one: return "1"
        case .two: return "2"
        case .three: return "3"
        case .four: return "4"
        case .all: return "All"
        }
    }

    var count: Int? {
        switch self {
        case .one: return 1
        case .two: return 2
        case .three: return 3
        case .four: return 4
        case .all: return nil
        }
    }
}

let maxBatchJobs = 10

/// The Jar tab. The panel settings are remembered between launches, the draw itself isn't.
@Observable
final class DrawViewModel {
    private enum Key {
        static let availableMinutes = "draw_available_minutes"
        static let longJobsOnly = "draw_long_jobs_only"
        static let selectedCategories = "draw_selected_categories"
        static let batchSize = "draw_batch_size"
    }

    @ObservationIgnored private let defaults: UserDefaults

    private(set) var availableMinutes: Int
    /// 4+ hrs mode: ignores the time and always draws a single job.
    private(set) var longJobsOnly: Bool
    /// Empty = any category.
    private(set) var selectedCategories: Set<String>
    private(set) var batchSize: DrawBatchSize

    private(set) var drawnJobs: [DrawnJobEntry] = []
    /// Unused minutes after the last draw.
    private(set) var remainingMinutesAfterDraw: Int = 0
    private(set) var excludedIds: Set<UUID> = []
    private(set) var noMatchFound: Bool = false

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let savedMinutes = defaults.integer(forKey: Key.availableMinutes)
        availableMinutes = savedMinutes > 0 ? savedMinutes : 30
        longJobsOnly = defaults.bool(forKey: Key.longJobsOnly)
        selectedCategories = Set(defaults.stringArray(forKey: Key.selectedCategories) ?? [])
        batchSize = DrawBatchSize(rawValue: defaults.string(forKey: Key.batchSize) ?? "") ?? .one
    }

    /// Saves the panel settings (not the draw itself).
    private func saveSettings() {
        defaults.set(availableMinutes, forKey: Key.availableMinutes)
        defaults.set(longJobsOnly, forKey: Key.longJobsOnly)
        defaults.set(Array(selectedCategories).sorted(), forKey: Key.selectedCategories)
        defaults.set(batchSize.rawValue, forKey: Key.batchSize)
    }

    func setAvailableMinutes(_ minutes: Int) {
        availableMinutes = minutes
        longJobsOnly = false
        saveSettings()
    }

    func setLongJobsOnly() {
        longJobsOnly = true
        saveSettings()
    }

    func toggleCategory(_ category: String) {
        if selectedCategories.contains(category) {
            selectedCategories.remove(category)
        } else {
            selectedCategories.insert(category)
        }
        saveSettings()
    }

    func setBatchSize(_ size: DrawBatchSize) {
        batchSize = size
        saveSettings()
    }

    /// Drop categories that no longer exist, or every draw would come up empty.
    func pruneCategories(to existing: [String]) {
        let valid = selectedCategories.intersection(existing)
        guard valid != selectedCategories else { return }
        selectedCategories = valid
        saveSettings()
    }

    /// Draws a batch: keep picking random jobs that fit what's left of the time until we have
    /// enough or nothing fits. 4+ hrs mode draws one.
    ///
    /// `excludeCurrent` (Draw again) leaves out everything already shown.
    @MainActor
    func draw(repository: JobRepository, allJobs: [Job], excludeCurrent: Bool = false) {
        let seedExcluded = excludeCurrent ? excludedIds.union(drawnJobs.map(\.id)) : []
        let maxJobs = longJobsOnly ? 1 : (batchSize.count ?? maxBatchJobs)
        var excluded = seedExcluded
        var picks: [DrawPick] = []
        var remaining = availableMinutes

        while picks.count < maxJobs {
            guard let pick = repository.drawJob(
                allJobs: allJobs,
                maxMinutes: remaining,
                categories: selectedCategories,
                excludeIds: excluded,
                longOnly: longJobsOnly
            ) else { break }
            picks.append(pick)
            excluded.insert(pick.job.id)
            remaining -= pick.minutesUsed
        }

        drawnJobs = picks.map {
            DrawnJobEntry(id: $0.job.id, job: $0.job, context: buildContext(job: $0.job, allJobs: allJobs), minutesUsed: $0.minutesUsed)
        }
        remainingMinutesAfterDraw = remaining
        excludedIds = seedExcluded
        noMatchFound = drawnJobs.isEmpty
    }

    private func buildContext(job: Job, allJobs: [Job]) -> DrawnJobContext {
        if let parentId = job.parentId {
            return DrawnJobContext(parentTitle: allJobs.first(where: { $0.id == parentId })?.title)
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
        drawnJobs = []
        excludedIds = []
        noMatchFound = false
    }

    /// Drops cards for jobs that were deleted or finished from somewhere else.
    func dropStaleCards(allJobs: [Job]) {
        let ids = Set(allJobs.filter { $0.isAvailableToDraw() }.map(\.id))
        if drawnJobs.contains(where: { !ids.contains($0.id) }) {
            drawnJobs.removeAll { !ids.contains($0.id) }
        }
    }

    /// Starts a drawn job and takes its card off the list.
    @MainActor
    func startJob(_ job: Job, repository: JobRepository) {
        if !job.isInProgress { repository.toggleInProgress(job) }
        drawnJobs.removeAll { $0.id == job.id }
    }

    /// Schedules a drawn job and takes its card off the list.
    @MainActor
    func scheduleJob(_ job: Job, at date: Date, repository: JobRepository) {
        repository.scheduleJob(job, at: date)
        drawnJobs.removeAll { $0.id == job.id }
    }
}
