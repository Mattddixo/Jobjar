import SwiftUI
import UIKit
import WidgetKit

@main
struct JobJarWidgets: WidgetBundle {
    var body: some Widget {
        DrawWidget()
        ComingUpWidget()
    }
}

// MARK: - Timeline

struct SnapshotEntry: TimelineEntry {
    let date: Date
    /// Nil when the app hasn't written anything the widget can read.
    let snapshot: WidgetSnapshot?
}

/// Reads what the app last wrote, with an entry at each of the next few midnights so "Due
/// tomorrow" turns into "Due today" on time. The app asks for a reload whenever it changes.
struct SnapshotProvider: TimelineProvider {
    func placeholder(in context: Context) -> SnapshotEntry {
        SnapshotEntry(date: .now, snapshot: .sample)
    }

    func getSnapshot(in context: Context, completion: @escaping (SnapshotEntry) -> Void) {
        let saved = WidgetSnapshot.load()
        completion(SnapshotEntry(date: .now, snapshot: context.isPreview ? saved ?? .sample : saved))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapshotEntry>) -> Void) {
        let saved = WidgetSnapshot.load()
        let calendar = Calendar.current
        var dates = [Date.now]
        var day = calendar.startOfDay(for: .now)
        for _ in 0..<3 {
            guard let next = calendar.date(byAdding: .day, value: 1, to: day) else { break }
            dates.append(next)
            day = next
        }
        completion(Timeline(entries: dates.map { SnapshotEntry(date: $0, snapshot: saved) }, policy: .atEnd))
    }
}

extension WidgetSnapshot {
    static let sample = WidgetSnapshot(
        inJar: 12,
        comingUp: [
            DueJob(id: UUID(), title: "Water the plants", dueDay: Calendar.current.date(byAdding: .day, value: -1, to: .now)),
            DueJob(id: UUID(), title: "Clean the fridge", dueDay: .now),
            DueJob(id: UUID(), title: "Change the bed", dueDay: Calendar.current.date(byAdding: .day, value: 2, to: .now)),
            DueJob(id: UUID(), title: "Mow the lawn", dueDay: Calendar.current.date(byAdding: .day, value: 5, to: .now)),
        ],
        rangeDays: 14
    )
}

// MARK: - Colours (match the app's)

private extension Color {
    init(light: UInt32, dark: UInt32) {
        func rgb(_ hex: UInt32) -> UIColor {
            UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        }
        self.init(uiColor: UIColor { $0.userInterfaceStyle == .dark ? rgb(dark) : rgb(light) })
    }

    static let jarAccent = Color(light: 0x1B5E57, dark: 0x8CCFC3)
    static let jarOnAccent = Color(light: 0xFFFFFF, dark: 0x0F2A27)
    static let jarHoney = Color(light: 0xFCF1DE, dark: 0x3F3220)
}

// MARK: - Draw a job

/// Opens the app and draws straight away, with the Jar tab's settings.
struct DrawWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "DrawWidget", provider: SnapshotProvider()) { entry in
            DrawWidgetView(entry: entry)
                .containerBackground(for: .widget) { Color.jarHoney }
                .widgetURL(URL(string: "jobjar://draw"))
        }
        .configurationDisplayName("Draw a job")
        .description("Tap to open Job Jar and draw a job that fits your time.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

private struct DrawWidgetView: View {
    let entry: SnapshotEntry
    @Environment(\.widgetFamily) private var family

    private var countText: String {
        guard let inJar = entry.snapshot?.inJar else { return "Uses your Jar settings" }
        switch inJar {
        case 0: return "The jar's empty"
        case 1: return "1 job in the jar"
        default: return "\(inJar) jobs in the jar"
        }
    }

    var body: some View {
        if family == .systemMedium {
            HStack(spacing: 16) {
                badge
                VStack(alignment: .leading, spacing: 4) {
                    Text("Draw a job")
                        .font(.title3.weight(.bold))
                    Text("Tap and the jar picks one that fits your time.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Text(countText)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(Color.jarAccent)
                }
                Spacer(minLength: 0)
            }
        } else {
            VStack(alignment: .leading, spacing: 4) {
                badge
                Spacer(minLength: 0)
                Text("Draw a job")
                    .font(.headline)
                Text(countText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var badge: some View {
        Image(systemName: "shuffle")
            .font(.title3.weight(.semibold))
            .foregroundStyle(Color.jarOnAccent)
            .frame(width: 44, height: 44)
            .background(Color.jarAccent, in: Circle())
    }
}

// MARK: - Coming up

/// The next few repeating jobs that are overdue, due today or due soon.
struct ComingUpWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "ComingUpWidget", provider: SnapshotProvider()) { entry in
            ComingUpWidgetView(entry: entry)
                .containerBackground(for: .widget) { Color(uiColor: .systemBackground) }
        }
        .configurationDisplayName("Coming up")
        .description("Repeating jobs that are overdue, due today or due soon.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

private struct ComingUpWidgetView: View {
    let entry: SnapshotEntry
    @Environment(\.widgetFamily) private var family

    private var rows: Int {
        switch family {
        case .systemSmall: return 3
        case .systemLarge: return 9
        default: return 4
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: family == .systemSmall ? 6 : 8) {
            Text("Coming up")
                .font(.headline)
                .foregroundStyle(Color.jarAccent)
            if let snapshot = entry.snapshot {
                let due = snapshot.comingUp.filter { WidgetSnapshot.daysUntil($0, now: entry.date) <= snapshot.rangeDays }
                if due.isEmpty {
                    message(snapshot.rangeDays == 1 ? "Nothing due in the next day." : "Nothing due in the next \(snapshot.rangeDays) days.")
                } else {
                    ForEach(due.prefix(rows), id: \.id) { job in
                        row(job)
                    }
                    if due.count > rows {
                        Text("+\(due.count - rows) more")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            } else {
                message("Open Job Jar to see what's due.")
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func row(_ job: WidgetSnapshot.DueJob) -> some View {
        let overdue = WidgetSnapshot.daysUntil(job, now: entry.date) < 0
        let content = VStack(alignment: .leading, spacing: 1) {
            Text(job.title)
                .font(family == .systemSmall ? Font.footnote.weight(.semibold) : Font.subheadline.weight(.semibold))
                .lineLimit(1)
            Text(WidgetSnapshot.dueText(job, now: entry.date))
                .font(.caption)
                .foregroundStyle(overdue ? AnyShapeStyle(Color.red) : AnyShapeStyle(.secondary))
        }
        if family == .systemSmall {
            content
        } else if let url = URL(string: "jobjar://job/\(job.id.uuidString)") {
            Link(destination: url) { content }
        } else {
            content
        }
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(.secondary)
    }
}
