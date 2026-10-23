import SwiftData
import XCTest
@testable import JobJar

/// Stands in for the phone's calendar. Remembers which job each event belongs to so the
/// ownership checks behave like the real thing.
@MainActor
final class FakeCalendar: CalendarGateway {
    struct Event: Equatable {
        var ownerId: UUID
        var title: String
        var start: Date
        var end: Date
    }

    var events: [String: Event] = [:]
    var hasAccess = true
    private var nextId = 1

    func insertEvent(for job: Job, start: Date, end: Date) -> String? {
        guard hasAccess else { return nil }
        let id = "event-\(nextId)"
        nextId += 1
        events[id] = Event(ownerId: job.id, title: job.title, start: start, end: end)
        return id
    }

    func updateEvent(_ eventId: String, owner: Job, title: String, start: Date, end: Date) -> Bool {
        guard ownsEvent(eventId, owner: owner) else { return false }
        events[eventId] = Event(ownerId: owner.id, title: title, start: start, end: end)
        return true
    }

    func deleteEvent(_ eventId: String, owner: Job) -> Bool {
        guard ownsEvent(eventId, owner: owner) else { return false }
        events[eventId] = nil
        return true
    }

    func ownsEvent(_ eventId: String, owner: Job) -> Bool {
        hasAccess && events[eventId]?.ownerId == owner.id
    }
}

@MainActor
final class JobRepositoryTests: XCTestCase {
    private var container: ModelContainer!
    private var calendar: FakeCalendar!
    private var clock = Date(timeIntervalSince1970: 1_760_000_000)
    private var repository: JobRepository!

    override func setUp() async throws {
        container = try ModelContainer(for: Job.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        calendar = FakeCalendar()
        repository = JobRepository(modelContext: container.mainContext, calendar: calendar, now: { [unowned self] in clock })
    }

    private var all: [Job] { (try? container.mainContext.fetch(FetchDescriptor<Job>())) ?? [] }

    private func job(_ id: UUID) -> Job? { all.first { $0.id == id } }

    private func edit(_ title: String, minutes: Int = 30, category: String = "", repeatDays: Int? = nil, weekdays: Int? = nil) -> JobEdit {
        JobEdit(
            title: title, notes: "", estimatedMinutes: minutes, category: category, priority: .normal,
            recurrenceDays: repeatDays, repeatWeekdays: weekdays, dependsOnSubtaskId: nil
        )
    }

    @discardableResult
    private func add(_ title: String, minutes: Int = 30, category: String = "", parent: Job? = nil, repeatDays: Int? = nil, weekdays: Int? = nil) -> Job {
        let created = repository.addJob(
            edit(title, minutes: minutes, category: category, repeatDays: repeatDays, weekdays: weekdays),
            parentId: parent?.id,
            allJobs: all
        )
        return created!
    }

    // MARK: Done and subtasks

    func testCompletingParentCompletesOpenSubtasks() {
        let parent = add("Garage", minutes: 60)
        let a = add("Sweep", minutes: 20, parent: parent)
        let b = add("Bins", minutes: 20, parent: parent)
        repository.toggleDone(parent, allJobs: all)
        XCTAssertTrue(parent.isDone)
        XCTAssertTrue(a.isDone)
        XCTAssertTrue(b.isDone)
        XCTAssertEqual(a.completedAt, parent.completedAt)
    }

    func testReopeningParentReopensOnlyTheSubtasksItClosed() {
        let parent = add("Garage", minutes: 60)
        let mine = add("Sweep", minutes: 20, parent: parent)
        let closedByParent = add("Bins", minutes: 20, parent: parent)
        repository.toggleDone(mine, allJobs: all)
        clock += 60
        repository.toggleDone(parent, allJobs: all)
        repository.toggleDone(parent, allJobs: all)
        XCTAssertFalse(parent.isDone)
        XCTAssertTrue(mine.isDone)
        XCTAssertFalse(closedByParent.isDone)
    }

    func testParentAutoCompletesFromLastSubtaskAndReopeningKeepsSubtasksDone() {
        let parent = add("Garage", minutes: 60)
        let a = add("Sweep", minutes: 20, parent: parent)
        let b = add("Bins", minutes: 20, parent: parent)
        repository.toggleDone(a, allJobs: all)
        repository.toggleDone(b, allJobs: all)
        XCTAssertTrue(parent.isDone)
        repository.toggleDone(parent, allJobs: all)
        XCTAssertFalse(parent.isDone)
        XCTAssertTrue(a.isDone)
        XCTAssertTrue(b.isDone)
    }

    func testReopeningASubtaskReopensItsParent() {
        let parent = add("Garage", minutes: 60)
        let a = add("Sweep", minutes: 20, parent: parent)
        repository.toggleDone(parent, allJobs: all)
        repository.toggleDone(a, allJobs: all)
        XCTAssertFalse(a.isDone)
        XCTAssertFalse(parent.isDone)
    }

    func testParentGrowsToFitItsSubtasks() {
        let parent = add("Garage", minutes: 30)
        add("Sweep", minutes: 20, parent: parent)
        add("Bins", minutes: 25, parent: parent)
        XCTAssertEqual(parent.estimatedMinutes, 45)
    }

    func testRepeatingJobCannotHaveSubtasks() {
        let parent = add("Bins", repeatDays: 7)
        XCTAssertNil(repository.addJob(edit("Sub"), parentId: parent.id, allJobs: all))
    }

    func testJobWithSubtasksIsSavedAsOneOffEvenIfAskedToRepeat() {
        let parent = add("Garage")
        add("Sweep", parent: parent)
        repository.updateJob(parent, with: edit("Garage", repeatDays: 7), allJobs: all)
        XCTAssertNil(parent.recurrenceDays)
    }

    // MARK: Undo

    func testUndoCompletionRestoresEverythingIncludingCalendarEvent() throws {
        let target = add("Paint fence", minutes: 90)
        let start = clock + 86_400
        XCTAssertTrue(repository.scheduleJob(target, at: start))
        let snapshot = repository.toggleDone(target, allJobs: all)
        XCTAssertTrue(target.isDone)
        XCTAssertNil(target.scheduledDate)
        XCTAssertTrue(calendar.events.isEmpty)

        repository.undo(snapshot)
        XCTAssertFalse(target.isDone)
        XCTAssertEqual(target.scheduledDate, start)
        let eventId = try XCTUnwrap(target.calendarEventId)
        XCTAssertEqual(calendar.events[eventId]?.start, start)
        XCTAssertEqual(calendar.events[eventId]?.end, start + 90 * 60)
    }

    func testUndoDeleteBringsBackParentAndSubtasks() {
        let parent = add("Garage", minutes: 60)
        add("Sweep", parent: parent)
        let parentId = parent.id
        let snapshot = repository.deleteJob(parent, allJobs: all)
        XCTAssertTrue(all.isEmpty)
        repository.undo(snapshot)
        XCTAssertEqual(all.count, 2)
        XCTAssertEqual(all.filter { $0.parentId == parentId }.count, 1)
    }

    func testDeletingASubtaskClearsSiblingDependencyAndUndoRestoresIt() {
        let parent = add("Garage", minutes: 60)
        let first = add("Clear", parent: parent)
        let second = add("Paint", parent: parent)
        var withDependency = edit("Paint")
        withDependency.dependsOnSubtaskId = first.id
        repository.updateJob(second, with: withDependency, allJobs: all)
        XCTAssertEqual(second.dependsOnSubtaskId, first.id)

        let firstId = first.id
        let snapshot = repository.deleteJob(first, allJobs: all)
        XCTAssertNil(second.dependsOnSubtaskId)
        repository.undo(snapshot)
        XCTAssertEqual(second.dependsOnSubtaskId, firstId)
    }

    func testDeletingABookedJobRemovesItsEvent() {
        let target = add("Dentist")
        repository.scheduleJob(target, at: clock + 3600)
        repository.deleteJob(target, allJobs: all)
        XCTAssertTrue(calendar.events.isEmpty)
    }

    // MARK: Repeating

    func testCompletingIntervalJobCyclesIt() {
        let bins = add("Bins", repeatDays: 7)
        repository.toggleDone(bins, allJobs: all)
        XCTAssertFalse(bins.isDone)
        XCTAssertEqual(bins.completionCount, 1)
        XCTAssertEqual(bins.nextDueAt, Calendar.current.date(byAdding: .day, value: 7, to: clock))
        XCTAssertFalse(bins.isPending(now: clock))
    }

    func testRestingRepeatingJobWakesWhenToggled() {
        let bins = add("Bins", repeatDays: 7)
        repository.toggleDone(bins, allJobs: all)
        repository.toggleDone(bins, allJobs: all)
        XCTAssertNil(bins.nextDueAt)
        XCTAssertTrue(bins.isPending(now: clock))
        XCTAssertEqual(bins.completionCount, 1)
    }

    func testWeekdayJobCreatedOnAnotherDayWaitsForItsDay() {
        let today = Calendar.current.component(.weekday, from: clock)
        let otherDay = mondayFirstWeekdays.first { $0 != today }!
        let job = add("Gym", repeatDays: 7, weekdays: weekdayBit(calendarWeekday: otherDay))
        XCTAssertNotNil(job.nextDueAt)
        XCTAssertFalse(job.isPending(now: clock))
    }

    func testTurningRepeatOnClearsScheduleAndCalendarEvent() {
        let target = add("Mow")
        repository.scheduleJob(target, at: clock + 3600)
        repository.updateJob(target, with: edit("Mow", repeatDays: 14), allJobs: all)
        XCTAssertNil(target.scheduledDate)
        XCTAssertNil(target.calendarEventId)
        XCTAssertTrue(calendar.events.isEmpty)
    }

    // MARK: Scheduling

    func testReschedulingMovesTheSameEvent() {
        let target = add("Dentist")
        repository.scheduleJob(target, at: clock + 3600)
        let eventId = target.calendarEventId
        repository.scheduleJob(target, at: clock + 7200)
        XCTAssertEqual(target.calendarEventId, eventId)
        XCTAssertEqual(calendar.events.count, 1)
        XCTAssertEqual(calendar.events[eventId!]?.start, clock + 7200)
    }

    func testAnEventIdThatIsntOursIsNeverDeleted() {
        let mine = add("Dentist")
        let other = add("Other")
        let foreignId = calendar.insertEvent(for: other, start: clock, end: clock + 60)!
        mine.calendarEventId = foreignId
        mine.scheduledDate = clock
        repository.unscheduleJob(mine)
        XCTAssertNotNil(calendar.events[foreignId])
        XCTAssertNil(mine.scheduledDate)
    }

    func testEditingTitleOfABookedJobRenamesItsEvent() {
        let target = add("Dentist", minutes: 30)
        repository.scheduleJob(target, at: clock + 3600)
        repository.updateJob(target, with: edit("Dentist checkup", minutes: 45), allJobs: all)
        let event = calendar.events[target.calendarEventId!]
        XCTAssertEqual(event?.title, "Dentist checkup")
        XCTAssertEqual(event?.end, clock + 3600 + 45 * 60)
    }

    func testStartingAScheduledJobUnschedulesIt() {
        let target = add("Dentist")
        repository.scheduleJob(target, at: clock + 3600)
        repository.toggleInProgress(target)
        XCTAssertTrue(target.isInProgress)
        XCTAssertNil(target.scheduledDate)
        XCTAssertTrue(calendar.events.isEmpty)
        repository.toggleInProgress(target)
        XCTAssertFalse(target.isInProgress)
    }

    func testSchedulingWithoutCalendarAccessStillBooksInApp() {
        calendar.hasAccess = false
        let target = add("Dentist")
        XCTAssertFalse(repository.scheduleJob(target, at: clock + 3600))
        XCTAssertEqual(target.scheduledDate, clock + 3600)
        XCTAssertNil(target.calendarEventId)
    }

    func testRepeatingJobsCantBeScheduled() {
        let bins = add("Bins", repeatDays: 7)
        XCTAssertFalse(repository.scheduleJob(bins, at: clock + 3600))
        XCTAssertNil(bins.scheduledDate)
    }

    // MARK: Categories

    func testTypedCategoryAdoptsExistingSpelling() {
        add("A", category: "Garden")
        let b = add("B", category: " garden ")
        XCTAssertEqual(b.category, "Garden")
    }

    func testRecasingACategoryOnlyOneJobHasSticks() {
        let a = add("A", category: "garden")
        repository.updateJob(a, with: edit("A", category: "Garden"), allJobs: all)
        XCTAssertEqual(a.category, "Garden")
    }

    func testLaunchRulesMergeCategoryCaseToTheMostCommonSpelling() {
        let a = add("A", category: "Garden")
        let b = add("B", category: "Garden")
        let c = add("C")
        c.category = "garden"
        repository.applyLaunchRules()
        XCTAssertEqual([a, b, c].map(\.category), ["Garden", "Garden", "Garden"])
    }

    func testRemoveCategoryBlanksIt() {
        let a = add("A", category: "Garden")
        let b = add("B", category: "House")
        repository.removeCategory("Garden", allJobs: all)
        XCTAssertEqual(a.category, "")
        XCTAssertEqual(b.category, "House")
    }

    // MARK: Drawing

    func testDrawSkipsBlockedScheduledInProgressAndRestingJobs() {
        let parent = add("Garage", minutes: 60)
        let first = add("Clear", minutes: 10, parent: parent)
        let blocked = add("Paint", minutes: 10, parent: parent)
        var dependency = edit("Paint", minutes: 10)
        dependency.dependsOnSubtaskId = first.id
        repository.updateJob(blocked, with: dependency, allJobs: all)

        let booked = add("Booked", minutes: 10)
        repository.scheduleJob(booked, at: clock + 3600)
        let started = add("Started", minutes: 10)
        repository.toggleInProgress(started)
        let resting = add("Resting", minutes: 10, repeatDays: 7)
        repository.toggleDone(resting, allJobs: all)
        let free = add("Free", minutes: 10)

        var drawn = Set<UUID>()
        for _ in 0..<50 {
            if let pick = repository.drawJob(allJobs: all, maxMinutes: 30, categories: [], excludeIds: [], longOnly: false) {
                drawn.insert(pick.job.id)
            }
        }
        XCTAssertEqual(drawn, [first.id, free.id])
    }

    func testDrawSkipsSubtasksOfAStartedParentAndCountsParentsByRemainingTime() {
        let parent = add("Garage", minutes: 60)
        let sub = add("Sweep", minutes: 40, parent: parent)
        repository.toggleDone(sub, allJobs: all)
        // 20 minutes left on the parent, so it fits a 30 minute slot.
        let pick = repository.drawJob(allJobs: all, maxMinutes: 30, categories: [], excludeIds: [], longOnly: false)
        XCTAssertEqual(pick?.job.id, parent.id)
        XCTAssertEqual(pick?.minutesUsed, 20)

        let parent2 = add("Shed", minutes: 60)
        add("Tidy", minutes: 10, parent: parent2)
        repository.toggleInProgress(parent2)
        let excluded: Set<UUID> = [parent.id]
        XCTAssertNil(repository.drawJob(allJobs: all, maxMinutes: 30, categories: [], excludeIds: excluded, longOnly: false))
    }

    func testDrawFiltersByAnyOfTheChosenCategories() {
        add("A", minutes: 10, category: "Garden")
        add("B", minutes: 10, category: "House")
        let c = add("C", minutes: 10, category: "Car")
        var seen = Set<String>()
        for _ in 0..<40 {
            if let pick = repository.drawJob(allJobs: all, maxMinutes: 30, categories: ["Garden", "House"], excludeIds: [], longOnly: false) {
                seen.insert(pick.job.category)
            }
        }
        XCTAssertEqual(seen, ["Garden", "House"])
        XCTAssertFalse(seen.contains(c.category))
    }
}
