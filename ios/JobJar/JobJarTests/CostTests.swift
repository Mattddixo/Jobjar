import SwiftData
import XCTest
@testable import JobJar

@MainActor
final class CostTests: XCTestCase {
    func testReadsTheUsualWaysOfTypingAnAmount() {
        XCTAssertEqual(parseCostInput("40"), .valid(cents: 4000))
        XCTAssertEqual(parseCostInput("12.5"), .valid(cents: 1250))
        XCTAssertEqual(parseCostInput("12,50"), .valid(cents: 1250))
        XCTAssertEqual(parseCostInput("1,234.50"), .valid(cents: 123_450))
        XCTAssertEqual(parseCostInput("1.234,50"), .valid(cents: 123_450))
        XCTAssertEqual(parseCostInput("1,234"), .valid(cents: 123_400))
        XCTAssertEqual(parseCostInput(" $40 "), .valid(cents: 4000))
        XCTAssertEqual(parseCostInput(".5"), .valid(cents: 50))
    }

    func testBlankIsNoCostAndNonsenseIsRejected() {
        XCTAssertEqual(parseCostInput("  "), .empty)
        XCTAssertEqual(parseCostInput("abc"), .invalid)
        XCTAssertEqual(parseCostInput("-5"), .invalid)
        XCTAssertEqual(parseCostInput("99999999999"), .invalid)
    }

    func testFormatsWholeAmountsWithoutCents() {
        let us = Locale(identifier: "en_US")
        XCTAssertEqual(formatCost(4000, locale: us), "$40")
        XCTAssertEqual(formatCost(1250, locale: us), "$12.50")
        XCTAssertEqual(formatCost(123_450, locale: us), "$1,234.50")
    }

    func testTheCostBoxShowsWhatWasSaved() {
        XCTAssertEqual(costInputText(nil), "")
        XCTAssertEqual(costInputText(4000, locale: Locale(identifier: "en_US")), "40")
        XCTAssertEqual(costInputText(1205, locale: Locale(identifier: "en_US")), "12.05")
        XCTAssertEqual(costInputText(1205, locale: Locale(identifier: "de_DE")), "12,05")
        XCTAssertEqual(parseCostInput(costInputText(1205, locale: Locale(identifier: "de_DE"))), .valid(cents: 1205))
    }

    func testTotalCostAddsUpTheJobAndItsSubtasks() {
        let parent = Job(title: "Garage", estimatedMinutes: 60, costCents: 4000)
        let paint = Job(title: "Paint", estimatedMinutes: 30, parentId: parent.id, costCents: 2550)
        let sweep = Job(title: "Sweep", estimatedMinutes: 30, parentId: parent.id)
        XCTAssertEqual(totalCostCents(parent, subtasks: [paint, sweep]), 6550)
        parent.costCents = nil
        XCTAssertEqual(totalCostCents(parent, subtasks: [paint, sweep]), 2550)
        paint.costCents = nil
        XCTAssertNil(totalCostCents(parent, subtasks: [paint, sweep]))
    }

    func testAddingAndEditingAJobSavesItsCost() throws {
        let container = try ModelContainer(for: Job.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let repository = JobRepository(modelContext: container.mainContext, calendar: FakeCalendar())
        var edit = JobEdit(
            title: "Paint fence", notes: "", estimatedMinutes: 60, category: "", priority: .normal,
            recurrenceDays: nil, repeatWeekdays: nil, dependsOnSubtaskId: nil, costCents: 4500
        )
        let job = try XCTUnwrap(repository.addJob(edit, parentId: nil, allJobs: []))
        XCTAssertEqual(job.costCents, 4500)
        edit.costCents = nil
        repository.updateJob(job, with: edit, allJobs: [job])
        XCTAssertNil(job.costCents)
    }

    func testBackupsKeepTheCost() throws {
        let job = Job(title: "Paint fence", estimatedMinutes: 60, costCents: 4550)
        let records = try JobExport.decode(JobExport.encode([job])).get()
        XCTAssertEqual(records.first?.costCents, 4550)
    }
}

@MainActor
final class ChecklistTests: XCTestCase {
    private var container: ModelContainer!
    private var repository: JobRepository!

    override func setUp() async throws {
        container = try ModelContainer(for: Job.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        repository = JobRepository(modelContext: container.mainContext, calendar: FakeCalendar())
    }

    private func add(_ title: String, repeatDays: Int? = nil) -> Job {
        let edit = JobEdit(
            title: title, notes: "", estimatedMinutes: 30, category: "", priority: .normal,
            recurrenceDays: repeatDays, repeatWeekdays: nil, dependsOnSubtaskId: nil
        )
        return repository.addJob(edit, parentId: nil, allJobs: [])!
    }

    func testChecklistIsSavedTrimmedWithoutBlankLines() {
        let job = add("Paint fence")
        repository.updateChecklist(job) { _ in
            [ChecklistItem(text: " Sandpaper "), ChecklistItem(text: "  "), ChecklistItem(text: "Brushes", checked: true)]
        }
        XCTAssertEqual(job.checklist, [ChecklistItem(text: "Sandpaper"), ChecklistItem(text: "Brushes", checked: true)])
    }

    func testEditsApplyToTheSavedList() {
        let job = add("Paint fence")
        repository.updateChecklist(job) { $0 + [ChecklistItem(text: "Sandpaper")] }
        repository.updateChecklist(job) { $0 + [ChecklistItem(text: "Brushes")] }
        XCTAssertEqual(job.checklist.map(\.text), ["Sandpaper", "Brushes"])
    }

    func testCompletingARepeatingJobUnticksItsChecklistAndUndoBringsTheTicksBack() {
        let job = add("Weekly clean", repeatDays: 7)
        repository.updateChecklist(job) { _ in [ChecklistItem(text: "Kitchen", checked: true), ChecklistItem(text: "Bathroom")] }
        let snapshot = repository.toggleDone(job, allJobs: [job])
        XCTAssertFalse(job.checklist.contains(where: \.checked))
        repository.undo(snapshot)
        XCTAssertEqual(job.checklist.first?.checked, true)
    }

    func testCompletingAOneOffJobLeavesItsTicksAlone() {
        let job = add("Paint fence")
        repository.updateChecklist(job) { _ in [ChecklistItem(text: "Sandpaper", checked: true)] }
        repository.toggleDone(job, allJobs: [job])
        XCTAssertEqual(job.checklist.first?.checked, true)
    }

    func testDeletingAChecklistCanBeUndone() {
        let job = add("Paint fence")
        repository.updateChecklist(job) { _ in [ChecklistItem(text: "Sandpaper")] }
        let snapshot = repository.deleteChecklist(job)
        XCTAssertTrue(job.checklist.isEmpty)
        repository.undo(try! XCTUnwrap(snapshot))
        XCTAssertEqual(job.checklist, [ChecklistItem(text: "Sandpaper")])
        XCTAssertNil(repository.deleteChecklist(add("Other")))
    }

    func testBackupsKeepTheChecklistAndReadAndroidFiles() throws {
        let job = Job(title: "Garage", estimatedMinutes: 60, checklist: [ChecklistItem(text: "Bin bags", checked: true)])
        let records = try JobExport.decode(JobExport.encode([job])).get()
        XCTAssertEqual(records.first?.checklist, [ChecklistItem(text: "Bin bags", checked: true)])

        let android = """
        {"format":"job-jar","version":1,"exportedAt":1,"jobs":[
          {"id":1,"title":"Garage","estimatedMinutes":60,"checklist":[{"text":"Gloves","checked":false},{"text":" "},{"text":"Tape"}]}
        ]}
        """
        let fromAndroid = try JobExport.decode(Data(android.utf8)).get()
        XCTAssertEqual(fromAndroid.first?.checklist, [ChecklistItem(text: "Gloves"), ChecklistItem(text: "Tape")])
    }
}
