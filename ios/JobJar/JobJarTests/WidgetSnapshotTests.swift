import XCTest
@testable import JobJar

final class WidgetSnapshotTests: XCTestCase {
    private let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/London")!
        return calendar
    }()

    private func at(_ y: Int, _ m: Int, _ d: Int, _ h: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: y, month: m, day: d, hour: h))!
    }

    private func repeating(_ title: String, due: Date?) -> Job {
        let job = Job(title: title, estimatedMinutes: 10, recurrenceDays: 7, nextDueAt: due)
        job.repeatUnit = .week
        job.repeatAnchor = .completion
        return job
    }

    func testKeepsTheRangePlusAFewDaysMostUrgentFirst() {
        let defaults = UserDefaults(suiteName: "WidgetSnapshotTests")!
        defaults.removePersistentDomain(forName: "WidgetSnapshotTests")
        defaults.set(7, forKey: comingUpRangeKey)
        let now = at(2026, 11, 2, 8)
        let jobs = [
            repeating("Bins", due: at(2026, 11, 2)),
            repeating("Filter", due: at(2026, 10, 30)),
            repeating("Far off", due: at(2026, 12, 1)),
            repeating("Just past range", due: at(2026, 11, 11)),
            Job(title: "One-off", estimatedMinutes: 5)
        ]
        let snapshot = WidgetSync.snapshot(jobs: jobs, now: now, defaults: defaults, calendar: calendar)
        XCTAssertEqual(snapshot.rangeDays, 7)
        XCTAssertEqual(snapshot.comingUp.map(\.title), ["Filter", "Bins", "Just past range"])
        // Filter and Bins are due, plus the one-off.
        XCTAssertEqual(snapshot.inJar, 3)
    }

    func testDueTextFollowsTheDay() {
        let job = WidgetSnapshot.DueJob(id: UUID(), title: "Bins", dueDay: at(2026, 11, 4))
        XCTAssertEqual(WidgetSnapshot.dueText(job, now: at(2026, 11, 2, 8), calendar: calendar), "Due Wed")
        XCTAssertEqual(WidgetSnapshot.dueText(job, now: at(2026, 11, 3, 8), calendar: calendar), "Due tomorrow")
        XCTAssertEqual(WidgetSnapshot.dueText(job, now: at(2026, 11, 4, 8), calendar: calendar), "Due today")
        XCTAssertEqual(WidgetSnapshot.dueText(job, now: at(2026, 11, 6, 8), calendar: calendar), "Overdue 2 days")
        XCTAssertEqual(WidgetSnapshot.dueText(job, now: at(2026, 10, 20, 8), calendar: calendar), "Due Nov 4")
        let now = WidgetSnapshot.DueJob(id: UUID(), title: "Plants", dueDay: nil)
        XCTAssertEqual(WidgetSnapshot.dueText(now, now: at(2026, 11, 2, 8), calendar: calendar), "Due now")
    }
}
