import XCTest
@testable import JobJar

/// Same cases as Android's RepeatFormTest. Uses the current calendar, like the form does.
final class RepeatFormTests: XCTestCase {
    private let calendar = Calendar.current
    private func at(_ y: Int, _ m: Int, _ d: Int, _ h: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: y, month: m, day: d, hour: h))!
    }
    private lazy var thursday = at(2026, 10, 29)
    private lazy var now = at(2026, 10, 29, 9)
    private let sunday = 1, monday = 2, tuesday = 3, thursdayNumber = 5

    private func fresh() -> RepeatForm {
        var form = RepeatForm()
        form.startDate = thursday
        return form
    }

    private func withPreset(_ preset: RepeatPreset) -> RepeatForm {
        var form = fresh()
        form.apply(preset)
        return form
    }

    func testPresetsComeFromTheStartDate() {
        XCTAssertEqual(withPreset(.weekly).rule?.weekdays, weekdayBit(calendarWeekday: thursdayNumber))
        XCTAssertEqual(withPreset(.yearly).rule, RepeatRule(interval: 1, unit: .year, anchor: .schedule, monthDay: 29, month: 10, leadDays: 30))
        XCTAssertEqual(withPreset(.monthly).rule?.monthDay, 29)
    }

    func testPickingAStartDateSetsTheDates() {
        let nov15 = at(2026, 11, 15)
        var yearly = withPreset(.yearly)
        yearly.setStartDate(nov15)
        XCTAssertEqual(yearly.rule?.month, 11)
        XCTAssertEqual(yearly.rule?.monthDay, 15)
        XCTAssertEqual(yearly.firstDueToSave(now: now), nov15)

        var monthly = withPreset(.monthly)
        monthly.setStartDate(nov15)
        XCTAssertEqual(monthly.rule?.monthDay, 15)
        XCTAssertEqual(monthly.nextDue(now: now), nov15)

        // Weekly on one day moves to the picked date's weekday (Nov 15 is a Sunday).
        var weekly = withPreset(.fortnightly)
        weekly.setStartDate(nov15)
        XCTAssertEqual(weekly.rule?.weekdays, weekdayBit(calendarWeekday: sunday))
        XCTAssertEqual(weekly.firstDueToSave(now: now), nov15)
    }

    func testWeeklyOnSeveralDaysSnapsToTheNextOne() {
        var form = withPreset(.weekly)
        form.toggleWeekday(weekdayBit(calendarWeekday: monday))
        form.setStartDate(at(2026, 10, 31))
        XCTAssertEqual(form.rule?.weekdays, weekdayBit(calendarWeekday: monday) | weekdayBit(calendarWeekday: thursdayNumber))
        XCTAssertEqual(form.firstDueToSave(now: now), at(2026, 11, 2))
    }

    func testMonthlyChoicesComeFromTheDate() {
        XCTAssertEqual(
            MonthOption.choices(for: thursday),
            [MonthOption(monthDay: 29), MonthOption(weekOfMonth: lastDayOfMonth, weekday: thursdayNumber)]
        )
        XCTAssertEqual(
            MonthOption.choices(for: at(2026, 11, 30)),
            [MonthOption(monthDay: 30), MonthOption(monthDay: lastDayOfMonth), MonthOption(weekOfMonth: lastDayOfMonth, weekday: monday)]
        )
        XCTAssertEqual(MonthOption(weekOfMonth: 1, weekday: monday).label, "On the first Monday")
    }

    func testAMonthlyChoiceKeepsItsKindWhenTheDateChanges() {
        var form = withPreset(.monthly)
        form.setMonthOption(MonthOption(weekOfMonth: lastDayOfMonth, weekday: thursdayNumber))
        form.setStartDate(at(2026, 12, 1))
        XCTAssertEqual(form.monthOption, MonthOption(weekOfMonth: 1, weekday: tuesday))
    }

    func testAfterFinishingStartsNowUnlessYouPickADay() {
        var form = withPreset(.monthly)
        form.setMode(.completion)
        XCTAssertNil(form.firstDueToSave(now: now))
        XCTAssertNil(form.nextDue(now: now))
        form.setStartDate(at(2026, 11, 3))
        XCTAssertEqual(form.firstDueToSave(now: now), at(2026, 11, 3))
    }

    func testLeadOnlyOffersWhatFits() {
        XCTAssertEqual(withPreset(.weekly).leadOptions, [0, 1, 3])
        var shrunk = withPreset(.monthly)
        shrunk.setLeadDays(14)
        shrunk.setUnit(.week)
        XCTAssertEqual(shrunk.leadDays, 6)
        XCTAssertEqual(withPreset(.daily).leadOptions, [0])
    }
}
