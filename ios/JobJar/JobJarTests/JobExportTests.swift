import SwiftData
import XCTest
@testable import JobJar

@MainActor
final class JobExportTests: XCTestCase {
    private func decode(_ json: String) -> Result<[JobRecord], JobExport.Failure> {
        JobExport.decode(Data(json.utf8))
    }

    func testRoundTripKeepsEveryField() throws {
        let garage = Job(title: "Garage", estimatedMinutes: 120, category: "Home", priority: .high, createdAt: Date(timeIntervalSince1970: 10))
        let sweep = Job(
            title: "Sweep", estimatedMinutes: 30, isDone: true, createdAt: Date(timeIntervalSince1970: 11),
            completedAt: Date(timeIntervalSince1970: 20), parentId: garage.id
        )
        let haul = Job(
            title: "Haul", estimatedMinutes: 45, isInProgress: true, createdAt: Date(timeIntervalSince1970: 12),
            parentId: garage.id, dependsOnSubtaskId: sweep.id
        )
        let paint = Job(
            title: "Paint", estimatedMinutes: 60, createdAt: Date(timeIntervalSince1970: 13),
            scheduledDate: Date(timeIntervalSince1970: 99), calendarEventId: "phone-only"
        )
        let bins = Job(
            title: "Bins", estimatedMinutes: 5, createdAt: Date(timeIntervalSince1970: 14), recurrenceDays: 7,
            repeatWeekdays: weekdaysMask, nextDueAt: Date(timeIntervalSince1970: 50), completionCount: 3
        )

        let data = try JobExport.encode([bins, haul, garage, paint, sweep])
        let records = try JobExport.decode(data).get()
        let byTitle = Dictionary(uniqueKeysWithValues: records.map { ($0.title, $0) })

        let parsedGarage = try XCTUnwrap(byTitle["Garage"])
        XCTAssertEqual(parsedGarage.category, "Home")
        XCTAssertEqual(parsedGarage.priority, .high)
        XCTAssertEqual(byTitle["Sweep"]?.parentId, parsedGarage.id)
        XCTAssertEqual(byTitle["Sweep"]?.isDone, true)
        XCTAssertEqual(byTitle["Sweep"]?.completedAt, Date(timeIntervalSince1970: 20))
        XCTAssertEqual(byTitle["Haul"]?.dependsOnSubtaskId, byTitle["Sweep"]?.id)
        XCTAssertEqual(byTitle["Haul"]?.isInProgress, true)
        XCTAssertEqual(byTitle["Paint"]?.scheduledDate, Date(timeIntervalSince1970: 99))
        // Event ids only mean something on the phone that wrote them.
        XCTAssertNil(byTitle["Paint"]?.calendarEventId)
        XCTAssertEqual(byTitle["Bins"]?.recurrenceDays, 7)
        XCTAssertEqual(byTitle["Bins"]?.repeatWeekdays, weekdaysMask)
        XCTAssertEqual(byTitle["Bins"]?.nextDueAt, Date(timeIntervalSince1970: 50))
        XCTAssertEqual(byTitle["Bins"]?.completionCount, 3)
    }

    func testReadsAnAndroidBackup() throws {
        let json = """
        {"format":"job-jar","version":1,"exportedAt":1,"jobs":[
          {"id":5,"title":"Dentist","estimatedMinutes":30,"priority":"HIGH","isInProgress":false,
           "createdAt":1000,"scheduledDate":1761000000000,"calendarEventId":42}
        ]}
        """
        let records = try decode(json).get()
        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(records[0].priority, .high)
        XCTAssertEqual(records[0].scheduledDate, Date(timeIntervalSince1970: 1_761_000_000))
        XCTAssertNil(records[0].calendarEventId)
    }

    func testRejectsFilesThatArentExports() {
        XCTAssertEqual(failure(decode("not json")), .notABackup)
        XCTAssertEqual(failure(decode(#"{"jobs":[]}"#)), .notABackup)
        XCTAssertEqual(failure(decode(#"{"format":"job-jar","version":99,"jobs":[]}"#)), .newerVersion)
        XCTAssertEqual(failure(decode(#"{"format":"job-jar","version":1,"exportedAt":0,"jobs":[{"id":1}]}"#)), .corrupt)
    }

    func testRepairsRuleBreakingData() throws {
        let json = """
        {"format":"job-jar","version":1,"exportedAt":0,"jobs":[
          {"id":1,"title":"Chore","estimatedMinutes":10,"recurrenceDays":7,"isDone":true,"scheduledDate":5},
          {"id":2,"title":"Sub of repeating","estimatedMinutes":5,"parentId":1},
          {"id":3,"title":"Orphan","estimatedMinutes":5,"parentId":42,"dependsOnSubtaskId":2},
          {"id":4,"title":"Nested","estimatedMinutes":5,"parentId":2},
          {"id":5,"title":"Done and started","estimatedMinutes":5,"isDone":true,"isInProgress":true}
        ]}
        """
        let byTitle = Dictionary(uniqueKeysWithValues: try decode(json).get().map { ($0.title, $0) })
        XCTAssertEqual(byTitle["Chore"]?.isDone, false, "repeating jobs never persist done")
        XCTAssertNil(byTitle["Chore"]?.scheduledDate, "repeating jobs can't be booked")
        XCTAssertNil(byTitle["Sub of repeating"]?.parentId, "repeating jobs can't have subtasks")
        XCTAssertNil(byTitle["Orphan"]?.parentId)
        XCTAssertNil(byTitle["Orphan"]?.dependsOnSubtaskId, "dependencies only on siblings")
        XCTAssertNil(byTitle["Nested"]?.parentId, "subtasks are one level deep")
        XCTAssertEqual(byTitle["Done and started"]?.isInProgress, false)
    }

    private func failure(_ result: Result<[JobRecord], JobExport.Failure>) -> JobExport.Failure? {
        if case .failure(let failure) = result { return failure }
        return nil
    }
}
