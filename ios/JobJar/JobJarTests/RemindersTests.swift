import XCTest
@testable import JobJar

/// Same cases as Android's ReminderTest.
final class RemindersTests: XCTestCase {
    private let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/London")!
        return calendar
    }()

    private func at(_ y: Int, _ m: Int, _ d: Int, _ h: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: y, month: m, day: d, hour: h))!
    }

    private func repeating(_ title: String, due: Date?, lead: Int = 0) -> Job {
        let job = Job(title: title, estimatedMinutes: 10, recurrenceDays: 1, nextDueAt: due)
        job.repeatUnit = .year
        job.repeatAnchor = .schedule
        job.repeatMonth = 1
        job.repeatMonthDay = 1
        job.leadDays = lead
        return job
    }

    func testOneNotificationPerDayWithOverdueInTheFirst() {
        let now = at(2026, 11, 2, 8)
        let jobs = [
            repeating("Bins", due: at(2026, 11, 2)),
            repeating("Filter", due: at(2026, 10, 30)),
            repeating("Rego", due: at(2026, 11, 9), lead: 7),
            repeating("Plants", due: nil),
            Job(title: "One-off", estimatedMinutes: 5)
        ]
        let planned = planDueReminders(jobs: jobs, now: now, minutes: 9 * 60, calendar: calendar)
        XCTAssertEqual(planned.map(\.date), [at(2026, 11, 2, 9), at(2026, 11, 9, 9)])
        XCTAssertEqual(planned[0].title, "3 repeating jobs coming due")
        XCTAssertEqual(planned[0].body, "Filter · Overdue 3 days\nBins · Due today\nRego · Due Nov 9")
        XCTAssertEqual(planned[1].title, "Rego · Due today")
    }

    func testAfterTodaysTimeItStartsTomorrow() {
        let planned = planDueReminders(jobs: [repeating("Bins", due: at(2026, 11, 2))], now: at(2026, 11, 2, 10), minutes: 9 * 60, calendar: calendar)
        XCTAssertEqual(planned.map(\.date), [at(2026, 11, 3, 9)])
        XCTAssertEqual(planned.first?.title, "Bins · Overdue 1 day")
    }
}
