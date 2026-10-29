import XCTest
@testable import JobJar

/// Same cases as Android's RecurrenceTest.
final class RecurrenceTests: XCTestCase {
    private let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/London")!
        return calendar
    }()

    private func at(_ y: Int, _ m: Int, _ d: Int, _ h: Int = 0, _ min: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: y, month: m, day: d, hour: h, minute: min))!
    }

    private func day(_ date: Date?) -> DateComponents? {
        date.map { calendar.dateComponents([.year, .month, .day], from: $0) }
    }

    private func ymd(_ y: Int, _ m: Int, _ d: Int) -> DateComponents { DateComponents(year: y, month: m, day: d) }

    private let days7 = RepeatRule(interval: 7)
    private func weekdays(_ mask: Int) -> RepeatRule { RepeatRule(interval: 1, unit: .week, weekdays: mask) }
    private func monthly(_ day: Int, every: Int = 1) -> RepeatRule {
        RepeatRule(interval: every, unit: .month, anchor: .schedule, monthDay: day)
    }
    private func yearly(_ month: Int, _ day: Int, every: Int = 1) -> RepeatRule {
        RepeatRule(interval: every, unit: .year, anchor: .schedule, monthDay: day, month: month)
    }

    // MARK: From when you finish

    func testIntervalKeepsLocalTimeAcrossClockChange() {
        // UK clocks go back on 25 October 2026.
        let next = days7.nextDue(previousDue: nil, completedAt: at(2026, 10, 20, 18, 30), calendar: calendar)
        XCTAssertEqual(next, at(2026, 10, 27, 18, 30))
    }

    func testMonthsFromFinishingClampToShortMonths() {
        let rule = RepeatRule(interval: 1, unit: .month)
        XCTAssertEqual(rule.nextDue(previousDue: nil, completedAt: at(2027, 1, 31, 10), calendar: calendar), at(2027, 2, 28, 10))
        let weeks = RepeatRule(interval: 2, unit: .week)
        XCTAssertEqual(day(weeks.nextDue(previousDue: nil, completedAt: at(2026, 10, 20, 9), calendar: calendar)), ymd(2026, 11, 3))
    }

    // MARK: Weekdays

    func testLateWeekdayCompletionStillLandsOnTheScheduledDay() {
        let monday = weekdays(weekdayBit(calendarWeekday: 2))
        let next = monday.nextDue(previousDue: at(2026, 10, 19), completedAt: at(2026, 10, 20, 12), calendar: calendar)
        XCTAssertEqual(next, at(2026, 10, 26))
    }

    func testWeekdayStartIsNowOnAMatchingDayOtherwiseTheNextOne() {
        let wednesday = at(2026, 10, 21, 9)
        XCTAssertNil(weekdays(weekdayBit(calendarWeekday: 4)).initialDue(now: wednesday, calendar: calendar))
        XCTAssertEqual(weekdays(weekendMask).initialDue(now: wednesday, calendar: calendar), at(2026, 10, 24))
        XCTAssertNil(days7.initialDue(now: wednesday, calendar: calendar), "from-finish rules start due now")
    }

    // MARK: On a schedule

    func testMonthlyOnTheFifteenth() {
        XCTAssertEqual(day(monthly(15).initialDue(now: at(2026, 10, 3, 9), calendar: calendar)), ymd(2026, 10, 15))
        XCTAssertEqual(day(monthly(15).initialDue(now: at(2026, 10, 20, 9), calendar: calendar)), ymd(2026, 11, 15))
        XCTAssertEqual(day(monthly(15).initialDue(now: at(2026, 10, 15, 9), calendar: calendar)), ymd(2026, 10, 15))
    }

    func testTheThirtyFirstFallsBackInShortMonths() {
        let rule = monthly(31)
        var due = rule.initialDue(now: at(2027, 1, 31, 8), calendar: calendar)!
        var dates = [day(due)]
        for _ in 0..<3 {
            due = rule.nextDue(previousDue: due, completedAt: due.addingTimeInterval(3600), calendar: calendar)
            dates.append(day(due))
        }
        XCTAssertEqual(dates, [ymd(2027, 1, 31), ymd(2027, 2, 28), ymd(2027, 3, 31), ymd(2027, 4, 30)])
    }

    func testLastDayOfTheMonth() {
        let rule = monthly(lastDayOfMonth)
        XCTAssertEqual(day(rule.initialDue(now: at(2028, 2, 3), calendar: calendar)), ymd(2028, 2, 29))
        XCTAssertEqual(day(rule.nextDue(previousDue: at(2028, 2, 29), completedAt: at(2028, 2, 29, 10), calendar: calendar)), ymd(2028, 3, 31))
    }

    func testYearlyIgnoresEarlyAndLateFinishes() {
        let rego = yearly(3, 3)
        let due = at(2027, 3, 3)
        XCTAssertEqual(day(rego.nextDue(previousDue: due, completedAt: at(2027, 2, 24, 10), calendar: calendar)), ymd(2028, 3, 3))
        XCTAssertEqual(day(rego.nextDue(previousDue: due, completedAt: at(2027, 4, 2, 10), calendar: calendar)), ymd(2028, 3, 3))
        XCTAssertEqual(day(rego.nextDue(previousDue: due, completedAt: at(2029, 5, 1, 10), calendar: calendar)), ymd(2030, 3, 3))
    }

    func testEveryTwoYearsKeepsItsCycle() {
        let licence = yearly(6, 10, every: 2)
        XCTAssertEqual(day(licence.nextDue(previousDue: at(2026, 6, 10), completedAt: at(2026, 6, 12), calendar: calendar)), ymd(2028, 6, 10))
    }

    func testLeapDayUsesFeb28InOtherYears() {
        let rule = yearly(2, 29)
        XCTAssertEqual(day(rule.initialDue(now: at(2026, 10, 1), calendar: calendar)), ymd(2027, 2, 28))
        XCTAssertEqual(day(rule.nextDue(previousDue: at(2027, 2, 28), completedAt: at(2027, 2, 28, 9), calendar: calendar)), ymd(2028, 2, 29))
    }

    func testEveryThreeMonthsFromItsDate() {
        let rule = monthly(15, every: 3)
        XCTAssertEqual(day(rule.initialDue(now: at(2026, 10, 2), calendar: calendar)), ymd(2026, 10, 15))
        XCTAssertEqual(day(rule.nextDue(previousDue: at(2026, 10, 15), completedAt: at(2026, 10, 16), calendar: calendar)), ymd(2027, 1, 15))
    }

    func testDaysOnAScheduleKeepTheirRhythm() {
        let rule = RepeatRule(interval: 3, anchor: .schedule)
        XCTAssertEqual(day(rule.nextDue(previousDue: at(2026, 3, 1), completedAt: at(2026, 3, 5, 18), calendar: calendar)), ymd(2026, 3, 7))
        XCTAssertEqual(day(rule.nextDue(previousDue: at(2026, 3, 1), completedAt: at(2026, 3, 1, 18), calendar: calendar)), ymd(2026, 3, 4))
    }

    // MARK: Lead window, overdue, storage

    func testLeadWindowStartsAtMidnightThatManyDaysBefore() {
        XCTAssertEqual(showFrom(at(2027, 3, 3), leadDays: 30, calendar: calendar), at(2027, 2, 1))
        XCTAssertEqual(showFrom(at(2027, 3, 3, 15), leadDays: 0, calendar: calendar), at(2027, 3, 3, 15))
    }

    func testShapeChanges() {
        XCTAssertTrue(days7.sameShape(as: RepeatRule(interval: 1, unit: .week)))
        XCTAssertTrue(monthly(15).sameShape(as: RepeatRule(interval: 3, unit: .month, anchor: .schedule, monthDay: 15, leadDays: 7)))
        XCTAssertFalse(monthly(15).sameShape(as: monthly(16)))
        XCTAssertFalse(days7.sameShape(as: RepeatRule(interval: 7, anchor: .schedule)))
        XCTAssertFalse(weekdays(weekendMask).sameShape(as: weekdays(weekdaysMask)))
    }

    func testStoringARuleDropsPartsThatDontApply() {
        let fromFinish = RepeatFields(rule: RepeatRule(interval: 1, unit: .month, anchor: .completion, monthDay: 15))
        XCTAssertNil(fromFinish.repeatMonthDay)
        var stored = yearly(3, 3)
        stored.leadDays = 30
        XCTAssertEqual(RepeatFields(rule: stored).rule, stored)
        XCTAssertNil(RepeatFields(rule: nil).recurrenceDays)
        XCTAssertEqual(RepeatFields(recurrenceDays: 7, repeatWeekdays: nil).rule, days7, "old rows read as days from finishing")
    }

    func testSuggestedLeads() {
        XCTAssertEqual(suggestedLeadDays(RepeatRule(interval: 1, unit: .week)), 0)
        XCTAssertEqual(suggestedLeadDays(monthly(1)), 3)
        XCTAssertEqual(suggestedLeadDays(monthly(1, every: 3)), 7)
        XCTAssertEqual(suggestedLeadDays(yearly(3, 3)), 30)
    }

    func testLabels() {
        XCTAssertEqual(formatRecurrence(monthly(15)), "Monthly on the 15th")
        XCTAssertEqual(formatRecurrence(monthly(lastDayOfMonth, every: 3)), "Every 3 months on the last day")
        XCTAssertEqual(formatRecurrence(RepeatRule(interval: 2, unit: .year)), "Every 2 years")
        XCTAssertEqual(ordinal(1), "1st")
        XCTAssertEqual(ordinal(12), "12th")
        XCTAssertEqual(ordinal(22), "22nd")
    }
}
