import SwiftData
import XCTest
@testable import JobJar

/// Small seeded generator so the odds tests give the same answer every run.
private struct SplitMix64 {
    var state: UInt64
    mutating func nextUnit() -> Double {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        z ^= z >> 31
        return Double(z >> 11) / Double(1 << 53)
    }
}

private final class SeededUnit {
    var generator: SplitMix64
    init(seed: UInt64) { generator = SplitMix64(state: seed) }
    func next() -> Double { generator.nextUnit() }
}

@MainActor
final class DrawWeightingTests: XCTestCase {
    func testAJobOnItsFairShareWeighsMost() {
        XCTAssertEqual(drawWeight(neededMinutes: 60, remainingMinutes: 180, picksLeft: 3), 1, accuracy: 1e-9)
        XCTAssertEqual(drawWeight(neededMinutes: 30, remainingMinutes: 180, picksLeft: 3), 0.5, accuracy: 1e-9)
        XCTAssertEqual(drawWeight(neededMinutes: 15, remainingMinutes: 180, picksLeft: 3), 0.25, accuracy: 1e-9)
        XCTAssertEqual(drawWeight(neededMinutes: 90, remainingMinutes: 180, picksLeft: 3), (60.0 / 90) * (60.0 / 90), accuracy: 1e-9)
        XCTAssertEqual(drawWeight(neededMinutes: 150, remainingMinutes: 180, picksLeft: 3), 0.16, accuracy: 1e-9)
    }

    func testOneJobOrAllAimsForTheWholeTime() {
        XCTAssertEqual(drawWeight(neededMinutes: 180, remainingMinutes: 180, picksLeft: 1), 1, accuracy: 1e-9)
        XCTAssertEqual(drawWeight(neededMinutes: 90, remainingMinutes: 180, picksLeft: 1), 0.5, accuracy: 1e-9)
        XCTAssertEqual(drawWeight(neededMinutes: 90, remainingMinutes: 180, picksLeft: nil), 0.5, accuracy: 1e-9)
    }

    func testWeightsStayBetweenTheFloorAndOne() {
        XCTAssertEqual(drawWeight(neededMinutes: 5, remainingMinutes: 240, picksLeft: 1), minDrawWeight, accuracy: 1e-9)
        XCTAssertEqual(drawWeight(neededMinutes: -20, remainingMinutes: 240, picksLeft: 1), minDrawWeight, accuracy: 1e-9)
        XCTAssertEqual(drawWeight(neededMinutes: 1, remainingMinutes: 0, picksLeft: 1), 1, accuracy: 1e-9)
        XCTAssertEqual(drawWeight(neededMinutes: 30, remainingMinutes: 30, picksLeft: 0), 1, accuracy: 1e-9)
    }

    func testPickWeightedFollowsTheWeights() {
        let random = SeededUnit(seed: 7)
        var counts = [0, 0, 0]
        let weights = [1.0, 2.0, 7.0]
        for _ in 0..<30_000 {
            counts[pickWeighted([0, 1, 2], unit: random.next()) { weights[$0] }!] += 1
        }
        XCTAssertEqual(Double(counts[0]) / 30_000, 0.1, accuracy: 0.01)
        XCTAssertEqual(Double(counts[1]) / 30_000, 0.2, accuracy: 0.01)
        XCTAssertEqual(Double(counts[2]) / 30_000, 0.7, accuracy: 0.01)
        XCTAssertNil(pickWeighted([Int](), unit: 0.5) { _ in 1 })
    }

    private func makeRepository(seed: UInt64) throws -> (JobRepository, ModelContainer) {
        let container = try ModelContainer(for: Job.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let random = SeededUnit(seed: seed)
        var repository = JobRepository(modelContext: container.mainContext, calendar: FakeCalendar())
        repository.randomUnit = { random.next() }
        return (repository, container)
    }

    func testOneJobInALongWindowLeansTowardJobsThatUseTheTime() throws {
        let (repository, container) = try makeRepository(seed: 1)
        let quick = Job(title: "Quick", estimatedMinutes: 15)
        let long = Job(title: "Long", estimatedMinutes: 120)
        container.mainContext.insert(quick)
        container.mainContext.insert(long)
        var longWeighted = 0
        var longEven = 0
        for _ in 0..<2_000 {
            if repository.drawJob(allJobs: [quick, long], maxMinutes: 120, categories: [], excludeIds: [], longOnly: false, favourFill: true)?.job.id == long.id {
                longWeighted += 1
            }
            if repository.drawJob(allJobs: [quick, long], maxMinutes: 120, categories: [], excludeIds: [], longOnly: false, favourFill: false)?.job.id == long.id {
                longEven += 1
            }
        }
        // Weights 1 vs 0.125: the long job should come out about 89% of the time, not 50%.
        XCTAssertEqual(Double(longWeighted) / 2_000, 0.89, accuracy: 0.03)
        XCTAssertEqual(Double(longEven) / 2_000, 0.5, accuracy: 0.04)
        XCTAssertLessThan(longWeighted, 2_000, "the quick job still turns up")
    }

    func testABatchOfThreeLeansTowardThreeSolidJobsAndNeverRunsOver() throws {
        let (repository, container) = try makeRepository(seed: 3)
        var jobs = (0..<4).map { Job(title: "Hour \($0)", estimatedMinutes: 60) }
        jobs += (0..<6).map { Job(title: "Quick \($0)", estimatedMinutes: 15) }
        let huge = Job(title: "Huge", estimatedMinutes: 170)
        jobs.append(huge)
        jobs.forEach { container.mainContext.insert($0) }

        var hugeFirst = 0
        var threeHours = 0
        let runs = 1_000
        for _ in 0..<runs {
            var remaining = 180
            var excluded = Set<UUID>()
            var picks: [DrawPick] = []
            while picks.count < 3 {
                guard let pick = repository.drawJob(
                    allJobs: jobs, maxMinutes: remaining, categories: [], excludeIds: excluded, longOnly: false,
                    picksLeft: 3 - picks.count, favourFill: true
                ) else { break }
                picks.append(pick)
                excluded.insert(pick.job.id)
                remaining -= pick.minutesUsed
            }
            XCTAssertLessThanOrEqual(picks.reduce(0) { $0 + $1.minutesUsed }, 180, "never more than the time")
            if picks.first?.job.id == huge.id { hugeFirst += 1 }
            if picks.count == 3 && picks.allSatisfy({ $0.job.estimatedMinutes == 60 }) { threeHours += 1 }
        }
        XCTAssertLessThan(Double(hugeFirst), Double(runs) * 0.05, "huge first: \(hugeFirst)")
        XCTAssertGreaterThan(Double(threeHours), Double(runs) * 0.25, "three hours: \(threeHours)")
    }

    func testAnOverrunParentNeverGivesTimeBack() throws {
        let (repository, container) = try makeRepository(seed: 1)
        let parent = Job(title: "Garage", estimatedMinutes: 30)
        let sweep = Job(title: "Sweep", estimatedMinutes: 45, isDone: true, completedAt: .now, parentId: parent.id)
        let bins = Job(title: "Bins", estimatedMinutes: 120, parentId: parent.id)
        [parent, sweep, bins].forEach { container.mainContext.insert($0) }
        let pick = try XCTUnwrap(repository.drawJob(allJobs: [parent, sweep, bins], maxMinutes: 10, categories: [], excludeIds: [], longOnly: false, favourFill: true))
        XCTAssertEqual(pick.job.id, parent.id)
        XCTAssertEqual(pick.minutesUsed, 0)
    }
}
