import Foundation
import SwiftData

/// The single seam between Views and SwiftData for anything beyond a plain read - mirrors the
/// Android app's JobRepository. Reads themselves go through @Query directly in each View (the
/// SwiftUI/SwiftData equivalent of the Android app's Room Flow-returning DAO methods), so this
/// type only holds mutation logic and the non-trivial rules (auto-grow, draw eligibility,
/// completion cascades) that don't belong scattered across views.
///
/// Every method takes the caller's current `allJobs` snapshot explicitly rather than querying
/// itself, since @Query already gives each View a live, reactive array - passing it in avoids
/// a second, possibly-stale fetch.
@MainActor
struct JobRepository {
    let modelContext: ModelContext

    /// A parent's estimate is a floor, not a ceiling: if its subtasks now add up to more than
    /// it does, the parent grows to match. This never shrinks the parent back down, and it's
    /// what makes a rough "4+ hours" starting estimate self-correct into an accurate total as
    /// the job gets broken into real subtasks.
    private func growParentToFitSubtasks(parentId: UUID, allJobs: [Job]) {
        guard let parent = allJobs.first(where: { $0.id == parentId }) else { return }
        let subtaskTotal = allJobs.filter { $0.parentId == parentId }.reduce(0) { $0 + $1.estimatedMinutes }
        if subtaskTotal > parent.estimatedMinutes {
            parent.estimatedMinutes = subtaskTotal
        }
    }

    func addJob(_ job: Job, allJobs: [Job]) {
        modelContext.insert(job)
        if let parentId = job.parentId {
            growParentToFitSubtasks(parentId: parentId, allJobs: allJobs + [job])
        }
        try? modelContext.save()
    }

    func updateJob(_ job: Job, allJobs: [Job]) {
        if let parentId = job.parentId {
            growParentToFitSubtasks(parentId: parentId, allJobs: allJobs)
        }
        try? modelContext.save()
    }

    /// Deleting a parent takes its subtasks with it - there's no meaningful orphan state for
    /// them. Deleting a single subtask instead clears dependsOnSubtaskId on any sibling that
    /// pointed at it, so nothing is left depending on a subtask that no longer exists.
    func deleteJob(_ job: Job, allJobs: [Job]) {
        if job.parentId == nil {
            for subtask in allJobs.filter({ $0.parentId == job.id }) {
                modelContext.delete(subtask)
            }
        } else if let parentId = job.parentId {
            for sibling in allJobs.filter({ $0.parentId == parentId && $0.dependsOnSubtaskId == job.id }) {
                sibling.dependsOnSubtaskId = nil
            }
        }
        modelContext.delete(job)
        try? modelContext.save()
    }

    /// A repeating job never persists isDone = true: "completing" it instead cycles it
    /// forward via cycleRepeatingJob. If it's currently resting (not due yet) - shown as
    /// "done" in the Jobs list - tapping it again means "make it available right now"
    /// instead, via wakeRepeatingJob. A one-off job keeps the plain isDone flip, and reopening
    /// a subtask reopens its parent if the parent had auto/force-completed.
    func toggleDone(_ job: Job, allJobs: [Job]) {
        if job.recurrenceDays != nil {
            if job.isPending() {
                cycleRepeatingJob(job, allJobs: allJobs)
            } else {
                wakeRepeatingJob(job)
            }
            try? modelContext.save()
            return
        }
        if job.isDone {
            job.isDone = false
            job.completedAt = nil
            if let parentId = job.parentId {
                reopenParentIfDone(parentId: parentId, allJobs: allJobs)
            }
        } else {
            job.isDone = true
            job.completedAt = .now
            if let parentId = job.parentId {
                autoCompleteParentIfFinished(parentId: parentId, allJobs: allJobs)
            }
        }
        try? modelContext.save()
    }

    /// Clears a resting repeating job's schedule so it's immediately due again. Doesn't touch
    /// completionCount - the past completion still happened.
    private func wakeRepeatingJob(_ job: Job) {
        job.nextDueAt = nil
    }

    /// Advances a repeating job to its next occurrence: bumps completionCount, records
    /// completedAt as this moment, and schedules nextDueAt this many days out from now (not
    /// from any fixed calendar date). isDone is left false throughout, since the job isn't
    /// "finished," it's cycling. Its own subtasks (if any) reset to not-done for the fresh
    /// cycle.
    private func cycleRepeatingJob(_ job: Job, allJobs: [Job]) {
        let now = Date.now
        job.completedAt = now
        job.nextDueAt = now.addingTimeInterval(Double(job.recurrenceDays!) * 86400)
        job.completionCount += 1
        for subtask in allJobs.filter({ $0.parentId == job.id && $0.isDone }) {
            subtask.isDone = false
            subtask.completedAt = nil
        }
    }

    private func autoCompleteParentIfFinished(parentId: UUID, allJobs: [Job]) {
        let siblings = allJobs.filter { $0.parentId == parentId }
        guard !siblings.isEmpty, siblings.allSatisfy({ $0.isDone }) else { return }
        guard let parent = allJobs.first(where: { $0.id == parentId }) else { return }
        if parent.recurrenceDays != nil {
            cycleRepeatingJob(parent, allJobs: allJobs)
        } else {
            parent.isDone = true
            parent.completedAt = .now
        }
    }

    /// The other half of autoCompleteParentIfFinished: reopening one subtask after its parent
    /// auto- or force-completed shouldn't leave the parent stuck done with a subtask now open
    /// again under it. A repeating parent never persists isDone = true in the first place, so
    /// this is a no-op for it - only a plain parent can actually be reopened.
    private func reopenParentIfDone(parentId: UUID, allJobs: [Job]) {
        guard let parent = allJobs.first(where: { $0.id == parentId }), parent.isDone else { return }
        parent.isDone = false
        parent.completedAt = nil
    }

    /// Draws one random eligible job matching (optionally) `category`. By default this matches
    /// jobs that *fit* `maxMinutes` (a ceiling). Pass `longOnly` = true to flip that to a floor
    /// instead - only jobs needing `longJobMinutes` or more are eligible, ignoring `maxMinutes`
    /// entirely.
    ///
    /// Eligibility is `Job.isPending`, not just "not done": a repeating job that isn't due yet
    /// is excluded even though it's technically not marked done. A subtask that's
    /// `Job.isUnblocked` = false (waiting on a linked sibling subtask) is excluded here too -
    /// that's the only place the link matters, since it's still fully completable by hand.
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
        try? modelContext.save()
        return picked
    }

    private func minutesNeeded(job: Job, subtasksByParent: [UUID: [Job]]) -> Int {
        if job.parentId == nil {
            return job.remainingMinutes(subtasks: subtasksByParent[job.id] ?? [])
        }
        return job.estimatedMinutes
    }
}
