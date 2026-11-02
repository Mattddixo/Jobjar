import XCTest
@testable import JobJar

final class AutoBackupTests: XCTestCase {
    func testKeepsTheNewestFourAndLeavesOtherFilesAlone() {
        let names = [
            "job-jar-2026-10-05.json", "job-jar-2026-10-12.json", "notes.txt", "job-jar-2026-10-19.json",
            "job-jar-2026-10-26.json", "job-jar-2026-11-02.json", "job-jar-export.json"
        ]
        XCTAssertEqual(AutoBackup.backupsToRemove(names), ["job-jar-2026-10-05.json"])
    }

    func testFileNamesMatchAndroid() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let date = calendar.date(from: DateComponents(year: 2026, month: 11, day: 2, hour: 12))!
        XCTAssertEqual(AutoBackup.fileName(for: date, calendar: calendar), "job-jar-2026-11-02.json")
    }
}
