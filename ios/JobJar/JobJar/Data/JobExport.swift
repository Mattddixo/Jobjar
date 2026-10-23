import Foundation
import SwiftUI
import UniformTypeIdentifiers

/// Backup file for Settings > Export/Import, same JSON as the Android app so backups move
/// between them.
///
/// ```json
/// { "format": "job-jar", "version": 1, "exportedAt": 1761000000000, "jobs": [ { "id": 1, ... } ] }
/// ```
///
/// Ids in the file are numbers. UUIDs get numbered 1, 2, 3... on export and new UUIDs are made
/// on import. Dates are epoch ms. Calendar event ids only mean something on the phone that made
/// them, so they're left out. On import, upcoming bookings get a new event on this phone (see
/// JobRepository.replaceAll).
enum JobExport {
    static let version = 1
    private static let format = "job-jar"

    enum Failure: Error {
        case notABackup, newerVersion, corrupt
    }

    private struct Header: Decodable {
        var format: String?
        var version: Int?
    }

    private struct File: Codable {
        var format: String
        var version: Int
        var exportedAt: Int64
        var jobs: [Entry]
    }

    private struct Entry: Codable {
        var id: Int64
        var title: String
        var notes: String?
        var estimatedMinutes: Int
        var category: String?
        var priority: String?
        var isDone: Bool?
        var isInProgress: Bool?
        var createdAt: Int64?
        var completedAt: Int64?
        var timesDrawn: Int?
        var parentId: Int64?
        var recurrenceDays: Int?
        var repeatWeekdays: Int?
        var nextDueAt: Int64?
        var completionCount: Int?
        var dependsOnSubtaskId: Int64?
        var scheduledDate: Int64?
    }

    private static func millis(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }
    private static func date(_ millis: Int64) -> Date { Date(timeIntervalSince1970: Double(millis) / 1000) }

    static func encode(_ jobs: [Job], exportedAt: Date = .now) throws -> Data {
        let ordered = jobs.sorted { $0.createdAt < $1.createdAt }
        var numbers: [UUID: Int64] = [:]
        for (index, job) in ordered.enumerated() {
            numbers[job.id] = Int64(index + 1)
        }
        let entries = ordered.map { job in
            Entry(
                id: numbers[job.id]!,
                title: job.title,
                notes: job.notes,
                estimatedMinutes: job.estimatedMinutes,
                category: job.category,
                priority: job.priority.rawValue.uppercased(),
                isDone: job.isDone,
                isInProgress: job.isInProgress,
                createdAt: millis(job.createdAt),
                completedAt: job.completedAt.map(millis),
                timesDrawn: job.timesDrawn,
                parentId: job.parentId.flatMap { numbers[$0] },
                recurrenceDays: job.recurrenceDays,
                repeatWeekdays: job.repeatWeekdays,
                nextDueAt: job.nextDueAt.map(millis),
                completionCount: job.completionCount,
                dependsOnSubtaskId: job.dependsOnSubtaskId.flatMap { numbers[$0] },
                scheduledDate: job.scheduledDate.map(millis)
            )
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(File(format: format, version: version, exportedAt: millis(exportedAt), jobs: entries))
    }

    /// Also fixes up anything the app would never save itself, in case the file was edited:
    /// missing, nested or repeating parents, and dependencies on non-siblings.
    static func decode(_ data: Data) -> Result<[JobRecord], Failure> {
        let decoder = JSONDecoder()
        guard let header = try? decoder.decode(Header.self, from: data), header.format == format else {
            return .failure(.notABackup)
        }
        if (header.version ?? 0) > version { return .failure(.newerVersion) }
        guard let file = try? decoder.decode(File.self, from: data) else { return .failure(.corrupt) }
        let ids = file.jobs.map(\.id)
        guard Set(ids).count == ids.count, ids.allSatisfy({ $0 > 0 }) else { return .failure(.corrupt) }

        let byNumber = Dictionary(uniqueKeysWithValues: file.jobs.map { ($0.id, $0) })
        let uuids = Dictionary(uniqueKeysWithValues: file.jobs.map { ($0.id, UUID()) })

        func validParent(_ entry: Entry) -> Int64? {
            guard let parentNumber = entry.parentId, let parent = byNumber[parentNumber],
                  parent.parentId == nil, parent.recurrenceDays == nil else { return nil }
            return parentNumber
        }

        let records = file.jobs.map { entry -> JobRecord in
            let parentNumber = validParent(entry)
            let dependency = entry.dependsOnSubtaskId.flatMap { byNumber[$0] }
            let validDependency = parentNumber != nil && dependency != nil && dependency!.id != entry.id &&
                validParent(dependency!) == parentNumber
            let repeats = parentNumber == nil && (entry.recurrenceDays ?? 0) > 0
            let weekdays = entry.repeatWeekdays.flatMap { $0 & everyDayMask != 0 ? $0 : nil }
            let title = entry.title.trimmingCharacters(in: .whitespacesAndNewlines)
            let isDone = repeats ? false : (entry.isDone ?? false)
            return JobRecord(
                id: uuids[entry.id]!,
                title: title.isEmpty ? "Untitled job" : title,
                notes: entry.notes ?? "",
                estimatedMinutes: max(entry.estimatedMinutes, 1),
                category: entry.category ?? "",
                priority: Priority(rawValue: (entry.priority ?? "").lowercased()) ?? .normal,
                isDone: isDone,
                isInProgress: (entry.isInProgress ?? false) && !isDone,
                createdAt: entry.createdAt.map(date) ?? .now,
                completedAt: entry.completedAt.map(date),
                timesDrawn: entry.timesDrawn ?? 0,
                parentId: parentNumber.flatMap { uuids[$0] },
                recurrenceDays: repeats ? entry.recurrenceDays : nil,
                repeatWeekdays: repeats ? weekdays : nil,
                nextDueAt: repeats ? entry.nextDueAt.map(date) : nil,
                completionCount: entry.completionCount ?? 0,
                dependsOnSubtaskId: validDependency ? entry.dependsOnSubtaskId.flatMap { uuids[$0] } : nil,
                scheduledDate: repeats || isDone ? nil : entry.scheduledDate.map(date)
            )
        }
        return .success(records)
    }
}

struct JobExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }

    var data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
