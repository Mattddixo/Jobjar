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
        var notes: String = ""
    }

    var events: [String: Event] = [:]
    var hasAccess = true
    private var nextId = 1

    func insertEvent(for job: Job, start: Date, end: Date) -> String? {
        guard hasAccess else { return nil }
        let id = "event-\(nextId)"
        nextId += 1
        events[id] = Event(ownerId: job.id, title: job.title, start: start, end: end, notes: job.notes)
        return id
    }

    func updateEvent(_ eventId: String, owner: Job, title: String, notes: String, start: Date, end: Date) -> Bool {
        guard ownsEvent(eventId, owner: owner) else { return false }
        events[eventId] = Event(ownerId: owner.id, title: title, start: start, end: end, notes: notes)
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

    func findEvent(_ eventId: String, owner: Job) -> EventLookup {
        guard hasAccess else { return .unknown }
        if let event = events[eventId], event.ownerId == owner.id { return .found(eventId: eventId, start: event.start) }
        if let moved = events.first(where: { $0.value.ownerId == owner.id }) {
            return .found(eventId: moved.key, start: moved.value.start)
        }
        return .missing
    }

    /// What someone dragging the event in the Calendar app does.
    func moveEvent(_ eventId: String, to start: Date) {
        var event = events[eventId]!
        let length = event.end.timeIntervalSince(event.start)
        event.start = start
        event.end = start.addingTimeInterval(length)
        events[eventId] = event
    }

    /// Calendars can hand an event a new identifier when they sync.
    func rekeyEvent(_ eventId: String) -> String {
        let newId = "event-\(nextId)"
        nextId += 1
        events[newId] = events.removeValue(forKey: eventId)
        return newId
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

    func testDeletingAParentDeletesItsBookedSubtasksEventsAndUndoBringsThemBack() throws {
        let parent = add("Garage", minutes: 60)
        let sub = add("Sweep", minutes: 20, parent: parent)
        repository.scheduleJob(sub, at: clock + 3600)
        let subId = sub.id
        let snapshot = repository.deleteJob(parent, allJobs: all)
        XCTAssertTrue(calendar.events.isEmpty)
        repository.undo(snapshot)
        let restored = try XCTUnwrap(job(subId))
        XCTAssertEqual(calendar.events[try XCTUnwrap(restored.calendarEventId)]?.start, clock + 3600)
    }

    func testEditingNotesOfABookedJobUpdatesItsEvent() {
        let target = add("Dentist")
        repository.scheduleJob(target, at: clock + 3600)
        var withNotes = edit("Dentist")
        withNotes.notes = "Bring the forms"
        repository.updateJob(target, with: withNotes, allJobs: all)
        XCTAssertEqual(calendar.events.values.first?.notes, "Bring the forms")
    }

    func testImportDeletesOldEventsAndBooksUpcomingJobs() {
        let target = add("Dentist")
        repository.scheduleJob(target, at: clock + 3600)
        repository.replaceAll(with: [
            JobRecord(id: UUID(), title: "Upcoming", estimatedMinutes: 30, scheduledDate: clock + 7200),
            JobRecord(id: UUID(), title: "Already past", estimatedMinutes: 30, scheduledDate: clock - 7200)
        ])
        XCTAssertEqual(calendar.events.count, 1)
        let event = calendar.events.values.first
        XCTAssertEqual(event?.title, "Upcoming")
        XCTAssertEqual(event?.end, clock + 7200 + 30 * 60)
        XCTAssertNotNil(all.first { $0.title == "Upcoming" }?.calendarEventId)
        XCTAssertNil(all.first { $0.title == "Already past" }?.calendarEventId)
    }

    func testEventDescriptionHasNotesThenWhereItCameFrom() {
        XCTAssertTrue(eventDescription("").hasPrefix("Scheduled from Job Jar"))
        XCTAssertTrue(eventDescription(" Bring the forms ").hasPrefix("Bring the forms\n\nScheduled from Job Jar"))
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

    /// Yearly, due 10 days from now, with a 30-day lead window.
    private var rego: RepeatRule {
        let due = Calendar.current.date(byAdding: .day, value: 10, to: clock)!
        let dueParts = Calendar.current.dateComponents([.month, .day], from: due)
        return RepeatRule(interval: 1, unit: .year, anchor: .schedule, monthDay: dueParts.day, month: dueParts.month, leadDays: 30)
    }

    private func addRule(_ title: String, _ rule: RepeatRule) -> Job {
        var change = edit(title)
        change.rule = rule
        return repository.addJob(change, parentId: nil, allJobs: all)!
    }

    private func dueDay(_ job: Job) -> Date? { job.nextDueAt.map { Calendar.current.startOfDay(for: $0) } }

    func testYearlyJobShowsInItsLeadWindowAndKeepsItsDate() {
        let job = addRule("Car registration", rego)
        let firstDue = Calendar.current.startOfDay(for: Calendar.current.date(byAdding: .day, value: 10, to: clock)!)
        XCTAssertEqual(dueDay(job), firstDue)
        XCTAssertTrue(job.isPending(now: clock), "inside the 30 days before")
        repository.toggleDone(job, allJobs: all)
        // Done early: next year's date, not a year from today.
        XCTAssertEqual(dueDay(job), Calendar.current.date(byAdding: .year, value: 1, to: firstDue))
        XCTAssertFalse(job.isPending(now: clock))
    }

    func testChangingJustTheNumberOrLeadKeepsTheDueDate() {
        let job = addRule("Car registration", rego)
        repository.toggleDone(job, allJobs: all)
        let kept = job.nextDueAt
        var change = edit("Car registration")
        var longer = rego
        longer.interval = 2
        longer.leadDays = 7
        change.rule = longer
        repository.updateJob(job, with: change, allJobs: all)
        XCTAssertEqual(job.nextDueAt, kept)
        XCTAssertEqual(job.repeatRule, longer)
    }

    func testCompletingFromComingUpFinishesTheCycleEvenBeforeItsDue() {
        var rule = rego
        rule.leadDays = 0
        let job = addRule("Car registration", rule)
        XCTAssertFalse(job.isPending(now: clock))
        let firstDue = job.nextDueAt
        XCTAssertNotNil(repository.completeCycle(job))
        XCTAssertEqual(job.completionCount, 1)
        XCTAssertEqual(dueDay(job), firstDue.flatMap { Calendar.current.date(byAdding: .year, value: 1, to: $0) })
        XCTAssertNil(repository.completeCycle(add("Shelf")))
    }

    func testPickingWhenItStartsSetsTheFirstDueDate() {
        let wednesday = 4
        let bins = RepeatRule(interval: 2, unit: .week, weekdays: weekdayBit(calendarWeekday: wednesday), anchor: .schedule)
        var change = edit("Bins")
        change.rule = bins
        let inAWeek = Calendar.current.date(byAdding: .day, value: 7, to: clock)!
        let job = repository.addJob(change, parentId: nil, allJobs: all, firstDue: inAWeek)!
        let expected = bins.firstOnOrAfter(inAWeek)
        XCTAssertEqual(job.nextDueAt, expected)
        XCTAssertEqual(Calendar.current.component(.weekday, from: expected), wednesday)
        // Editing without picking keeps it; picking again moves it.
        var longer = change
        longer.rule = RepeatRule(interval: 2, unit: .week, weekdays: weekdayBit(calendarWeekday: wednesday), anchor: .schedule, leadDays: 1)
        repository.updateJob(job, with: longer, allJobs: all)
        XCTAssertEqual(job.nextDueAt, expected)
        repository.updateJob(job, with: change, allJobs: all, firstDue: clock)
        XCTAssertEqual(job.nextDueAt, bins.firstOnOrAfter(clock))
    }

    func testOverdueJobsAreDrawnLikeHighPriority() {
        let filter = add("Filter", repeatDays: 7)
        filter.nextDueAt = Calendar.current.date(byAdding: .day, value: -3, to: clock)
        var shelfEdit = edit("Shelf")
        shelfEdit.priority = .low
        repository.addJob(shelfEdit, parentId: nil, allJobs: all)
        XCTAssertTrue(filter.isOverdue(now: clock))
        let seed = SeededUnit(seed: 11)
        let seeded = JobRepository(modelContext: container.mainContext, calendar: calendar, now: { [unowned self] in clock }, randomUnit: { seed.next() })
        let picks = (0..<400).compactMap { _ in
            seeded.drawJob(allJobs: all, maxMinutes: 60, categories: [], excludeIds: [], longOnly: false)?.job.id
        }
        let share = Double(picks.filter { $0 == filter.id }.count) / Double(picks.count)
        let expected = priorityWeight(.high) / (priorityWeight(.high) + priorityWeight(.low))
        XCTAssertEqual(share, expected, accuracy: 0.07)
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

    func testReschedulingReplacesTheEvent() {
        let target = add("Dentist")
        repository.scheduleJob(target, at: clock + 3600)
        let oldId = target.calendarEventId
        repository.scheduleJob(target, at: clock + 7200)
        let newId = target.calendarEventId!
        XCTAssertNotEqual(newId, oldId)
        XCTAssertEqual(Set(calendar.events.keys), [newId], "the old one is gone")
        XCTAssertEqual(calendar.events[newId]?.start, clock + 7200)
    }

    func testReschedulingAfterTheEventWasDeletedInTheCalendarStillWritesOne() {
        let target = add("Dentist")
        repository.scheduleJob(target, at: clock + 3600)
        calendar.events.removeAll()
        XCTAssertTrue(repository.scheduleJob(target, at: clock + 7200))
        XCTAssertEqual(calendar.events.values.first?.start, clock + 7200)
        XCTAssertEqual(calendar.events.keys.first, target.calendarEventId)
    }

    func testUnschedulingThenReschedulingWritesANewEvent() {
        let target = add("Dentist")
        repository.scheduleJob(target, at: clock + 3600)
        repository.unscheduleJob(target)
        XCTAssertTrue(calendar.events.isEmpty)
        repository.scheduleJob(target, at: clock + 7200)
        XCTAssertEqual(calendar.events.count, 1)
        XCTAssertEqual(calendar.events.keys.first, target.calendarEventId)
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

    // MARK: Missed bookings

    private let hour: TimeInterval = 3600

    func testABookedJobStaysOutOfTheJarUntilItsSlotIsOverThenComesBack() {
        let target = add("Paint fence", minutes: 60)
        repository.scheduleJob(target, at: clock + hour)
        XCTAssertFalse(target.isAvailableToDraw(now: clock))
        clock += 90 * 60
        XCTAssertTrue(target.isBooked(now: clock), "still booked mid-slot")
        clock += hour
        XCTAssertTrue(target.isMissed(now: clock))
        XCTAssertFalse(target.isBooked(now: clock))
        XCTAssertTrue(target.isAvailableToDraw(now: clock))
        XCTAssertEqual(repository.drawJob(allJobs: all, maxMinutes: 120, categories: [], excludeIds: [], longOnly: false)?.job.id, target.id)
    }

    func testStartingAMissedJobKeepsItsPastEventButUnlinksIt() {
        let target = add("Paint fence", minutes: 30)
        repository.scheduleJob(target, at: clock + hour)
        clock += 3 * hour
        repository.toggleInProgress(target)
        XCTAssertNil(target.scheduledDate)
        XCTAssertEqual(calendar.events.count, 1, "the past event stays as a record")
    }

    func testCompletingOrDeletingOnlyRemovesUpcomingEvents() {
        let upcoming = add("Upcoming", minutes: 30)
        let past = add("Past", minutes: 30)
        repository.scheduleJob(upcoming, at: clock + 5 * hour)
        repository.scheduleJob(past, at: clock + hour)
        clock += 3 * hour
        repository.toggleDone(upcoming, allJobs: all)
        repository.deleteJob(past, allJobs: all)
        XCTAssertEqual(calendar.events.values.map(\.title), ["Past"])
    }

    func testUndoingAMissedJobsCompletionDoesNotAddASecondEvent() {
        let target = add("Paint fence", minutes: 30)
        repository.scheduleJob(target, at: clock + hour)
        clock += 3 * hour
        let snapshot = repository.toggleDone(target, allJobs: all)
        repository.undo(snapshot)
        XCTAssertEqual(calendar.events.count, 1)
        XCTAssertTrue(target.isMissed(now: clock))
    }

    func testReschedulingAMissedJobKeepsThePastEventAndAddsANewOne() {
        let target = add("Paint fence", minutes: 30)
        repository.scheduleJob(target, at: clock + hour)
        let pastId = target.calendarEventId!
        clock += 3 * hour
        repository.scheduleJob(target, at: clock + hour)
        XCTAssertEqual(calendar.events.count, 2)
        XCTAssertNotNil(calendar.events[pastId], "the past one stays as a record")
        XCTAssertNotEqual(target.calendarEventId, pastId)
        XCTAssertTrue(target.isBooked(now: clock))
    }

    // MARK: Picking up calendar changes

    func testMovingTheEventInTheCalendarMovesTheBooking() {
        let target = add("Dentist")
        repository.scheduleJob(target, at: clock + hour)
        calendar.moveEvent(target.calendarEventId!, to: clock + 26 * hour)
        repository.syncCalendar()
        XCTAssertEqual(target.scheduledDate, clock + 26 * hour)
    }

    func testDeletingTheEventInTheCalendarUnbooksTheJob() {
        let target = add("Dentist")
        repository.scheduleJob(target, at: clock + hour)
        calendar.events.removeAll()
        repository.syncCalendar()
        XCTAssertNil(target.scheduledDate)
        XCTAssertNil(target.calendarEventId)
        XCTAssertTrue(target.isAvailableToDraw(now: clock))
    }

    func testAnEventWithANewIdGetsRelinked() {
        let target = add("Dentist")
        repository.scheduleJob(target, at: clock + hour)
        let newId = calendar.rekeyEvent(target.calendarEventId!)
        repository.syncCalendar()
        XCTAssertEqual(target.calendarEventId, newId)
        XCTAssertEqual(target.scheduledDate, clock + hour)
    }

    func testWithoutCalendarAccessBookingsAreLeftAlone() {
        let target = add("Dentist")
        repository.scheduleJob(target, at: clock + hour)
        calendar.events.removeAll()
        calendar.hasAccess = false
        repository.syncCalendar()
        XCTAssertEqual(target.scheduledDate, clock + hour)
    }

    func testCalendarLinksParseBackToTheJob() {
        let target = add("Dentist")
        let url = URL(string: "jobjar://job/\(target.id.uuidString)")!
        XCTAssertEqual(DeviceCalendar.jobId(from: url), target.id)
        XCTAssertNil(DeviceCalendar.jobId(from: URL(string: "https://example.com/job/\(target.id.uuidString)")!))
        XCTAssertNil(DeviceCalendar.jobId(from: URL(string: "jobjar://job/not-a-job")!))
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
        let parent = add("Garage", minutes: 90)
        let sweep = add("Sweep", minutes: 40, parent: parent)
        let bins = add("Bins", minutes: 10, parent: parent)
        repository.toggleDone(sweep, allJobs: all)
        // 50 minutes left on the parent, so it fits a 50 minute slot. Bins is left out to keep
        // the draw deterministic.
        let pick = repository.drawJob(allJobs: all, maxMinutes: 50, categories: [], excludeIds: [bins.id], longOnly: false)
        XCTAssertEqual(pick?.job.id, parent.id)
        XCTAssertEqual(pick?.minutesUsed, 50)

        let parent2 = add("Shed", minutes: 60)
        add("Tidy", minutes: 10, parent: parent2)
        repository.toggleInProgress(parent2)
        let excluded: Set<UUID> = [parent.id, bins.id]
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
