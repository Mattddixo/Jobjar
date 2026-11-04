import XCTest
@testable import JobJar

/// Same cases as Android's SpendingTest.
final class SpendingTests: XCTestCase {
    private let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/London")!
        return calendar
    }()

    private func at(_ y: Int, _ m: Int, _ d: Int) -> Date {
        calendar.date(from: DateComponents(year: y, month: m, day: d, hour: 12))!
    }

    private let ids = (0..<8).map { _ in UUID() }

    private func done(_ i: Int, _ title: String, _ cents: Int?, _ date: Date?, _ category: String = "", parent: Int? = nil) -> Job {
        Job(id: ids[i], title: title, estimatedMinutes: 10, category: category, isDone: true, completedAt: date, parentId: parent.map { ids[$0] }, costCents: cents)
    }

    private func finished(_ i: Int, _ date: Date) -> Completion {
        Completion(jobId: ids[i], title: "", estimatedMinutes: 10, completedAt: date)
    }

    private lazy var jobs: [Job] = [
        done(1, "Fix tap", 4_000, at(2025, 3, 2), "House"),
        done(2, "Paint fence", 12_000, at(2026, 5, 1), "Garden"),
        // Five finishes, three of them logged.
        Job(id: ids[3], title: "Mow lawn", estimatedMinutes: 10, category: "Garden", recurrenceDays: 7, completionCount: 5, costCents: 2_000),
        done(4, "Free job", nil, at(2026, 1, 1), "House"),
        Job(id: ids[5], title: "Not done yet", estimatedMinutes: 10, costCents: 9_900),
        Job(id: ids[6], title: "Bathroom", estimatedMinutes: 60, category: "House"),
        done(7, "Buy tiles", 3_000, at(2026, 2, 3), "House", parent: 6)
    ]

    private lazy var history: [Completion] = [finished(3, at(2025, 9, 1)), finished(3, at(2026, 4, 1)), finished(3, at(2026, 6, 1))]

    func testAllTimeMatchesTheStatsTotalAndCountsEveryRepeat() {
        let all = spendingBreakdown(jobs: jobs, completions: history, calendar: calendar)
        XCTAssertEqual(all.totalCents, 4_000 + 12_000 + 5 * 2_000 + 3_000)
        XCTAssertEqual(all.groups.map(\.category), ["Garden", "House"])
        let garden = all.groups[0]
        XCTAssertEqual(garden.items.map(\.title), ["Paint fence", "Mow lawn"])
        XCTAssertEqual(garden.items[1].times, 5)
        XCTAssertEqual(garden.items[1].cents, 10_000)
        XCTAssertEqual(garden.items[1].lastDoneAt, at(2026, 6, 1))
        // The two finishes from before the history aren't in any year.
        XCTAssertEqual(all.undatedCents, 4_000)
        XCTAssertEqual(all.years, [2026, 2025])
        XCTAssertEqual(all.groups[1].items.first { $0.title == "Buy tiles" }?.parentTitle, "Bathroom")
    }

    func testAYearOnlyCountsWhatWasFinishedThen() {
        let y2025 = spendingBreakdown(jobs: jobs, completions: history, year: 2025, calendar: calendar)
        XCTAssertEqual(y2025.totalCents, 4_000 + 2_000)
        XCTAssertEqual(y2025.groups.first { $0.category == "Garden" }?.items.first?.times, 1)

        let y2026 = spendingBreakdown(jobs: jobs, completions: history, year: 2026, calendar: calendar)
        XCTAssertEqual(y2026.totalCents, 12_000 + 2 * 2_000 + 3_000)
        XCTAssertNil(y2026.groups.flatMap(\.items).first { $0.title == "Fix tap" })
    }

    func testNothingWithACostMeansNothingToShow() {
        let none = spendingBreakdown(jobs: [done(1, "Free", nil, at(2026, 1, 1))], completions: [], calendar: calendar)
        XCTAssertEqual(none.totalCents, 0)
        XCTAssertEqual(none.years, [])
    }
}
