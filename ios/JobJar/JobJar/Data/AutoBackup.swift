import Foundation
import SwiftData

/// Weekly backups into the app's own Backups folder, which the Files app shows under On My
/// iPhone › Job Jar (and the phone's iCloud backup includes). Each is a normal backup file named
/// by date; the last few are kept. Runs when the app opens and a week has passed. On unless
/// turned off in Settings.
enum AutoBackup {
    static let enabledKey = "backup_auto"
    static let lastKey = "backup_last"
    static let kept = 4
    static let everyDays = 7

    static var folder: URL? {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?.appendingPathComponent("Backups", isDirectory: true)
    }

    static func lastBackup(defaults: UserDefaults = .standard) -> Date? {
        let seconds = defaults.double(forKey: lastKey)
        return seconds > 0 ? Date(timeIntervalSince1970: seconds) : nil
    }

    static func isOn(defaults: UserDefaults = .standard) -> Bool {
        defaults.object(forKey: enabledKey) as? Bool ?? true
    }

    @MainActor
    static func runIfDue(modelContext: ModelContext, now: Date = .now, defaults: UserDefaults = .standard) {
        guard isOn(defaults: defaults) else { return }
        if let last = lastBackup(defaults: defaults), now.timeIntervalSince(last) < Double(everyDays) * 86_400 { return }
        backUp(modelContext: modelContext, now: now, defaults: defaults)
    }

    /// Writes today's backup and removes older ones past the few kept. False if it couldn't.
    @MainActor
    @discardableResult
    static func backUp(modelContext: ModelContext, now: Date = .now, defaults: UserDefaults = .standard) -> Bool {
        guard let folder else { return false }
        let jobs = (try? modelContext.fetch(FetchDescriptor<Job>())) ?? []
        let history = (try? modelContext.fetch(FetchDescriptor<Completion>())) ?? []
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let data = try JobExport.encode(jobs, history: history, exportedAt: now)
            try data.write(to: folder.appendingPathComponent(fileName(for: now)), options: .atomic)
            let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
            for old in backupsToRemove(names) {
                try? FileManager.default.removeItem(at: folder.appendingPathComponent(old))
            }
            defaults.set(now.timeIntervalSince1970, forKey: lastKey)
            return true
        } catch {
            return false
        }
    }

    /// "job-jar-2026-11-02.json": sorts by date. Same names as Android's.
    static func fileName(for date: Date, calendar: Calendar = .current) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "job-jar-%04d-%02d-%02d.json", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    /// Of the files in the folder, the older backups to remove (anything else is left alone).
    static func backupsToRemove(_ names: [String], keep: Int = kept) -> [String] {
        let backups = names.filter { $0.range(of: #"^job-jar-\d{4}-\d{2}-\d{2}\.json$"#, options: .regularExpression) != nil }
        return Array(backups.sorted(by: >).dropFirst(keep))
    }
}
