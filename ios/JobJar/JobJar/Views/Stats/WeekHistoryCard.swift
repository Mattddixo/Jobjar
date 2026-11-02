import SwiftData
import SwiftUI

/// What got done, a week at a time (Monday to Sunday), newest first and grouped by day. The
/// arrows step back through earlier weeks. Same as Android.
struct WeekHistoryCard: View {
    @Query(sort: \Completion.completedAt, order: .reverse) private var history: [Completion]
    @State private var weeksBack = 0

    private var calendar: Calendar {
        var calendar = Calendar.current
        calendar.firstWeekday = 2
        return calendar
    }

    private var weekStart: Date {
        let thisWeek = calendar.dateInterval(of: .weekOfYear, for: .now)?.start ?? calendar.startOfDay(for: .now)
        return calendar.date(byAdding: .weekOfYear, value: -weeksBack, to: thisWeek) ?? thisWeek
    }

    var body: some View {
        let start = weekStart
        let end = calendar.date(byAdding: .day, value: 7, to: start) ?? start
        let entries = history.filter { $0.completedAt >= start && $0.completedAt < end }
        let days = Dictionary(grouping: entries) { calendar.startOfDay(for: $0.completedAt) }
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Button {
                    weeksBack += 1
                } label: {
                    Image(systemName: "chevron.left").padding(8)
                }
                .accessibilityLabel("Previous week")
                VStack(spacing: 2) {
                    Text(title(start: start, end: end)).font(.headline)
                    Text(summary(entries)).font(.caption).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                Button {
                    weeksBack -= 1
                } label: {
                    Image(systemName: "chevron.right").padding(8)
                }
                .disabled(weeksBack == 0)
                .accessibilityLabel("Next week")
            }
            ForEach(days.keys.sorted(by: >), id: \.self) { day in
                Text(dayLabel(day))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.accentColor)
                    .padding(.top, 8)
                ForEach(days[day] ?? []) { entry in
                    HStack {
                        Text(entry.title).lineLimit(1)
                        Spacer()
                        Text(formatMinutes(entry.estimatedMinutes)).font(.caption).foregroundStyle(.secondary)
                    }
                    .font(.subheadline)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardStyle()
    }

    private func summary(_ entries: [Completion]) -> String {
        if entries.isEmpty { return weeksBack == 0 ? "Nothing done yet this week." : "Nothing done that week." }
        return "\(entries.count) done · \(formatMinutes(entries.reduce(0) { $0 + $1.estimatedMinutes }))"
    }

    /// "This week", "Last week", then "Oct 19 – 25".
    private func title(start: Date, end: Date) -> String {
        switch weeksBack {
        case 0: return "This week"
        case 1: return "Last week"
        default:
            let last = calendar.date(byAdding: .day, value: -1, to: end) ?? end
            let sameMonth = calendar.component(.month, from: start) == calendar.component(.month, from: last)
            let startText = start.formatted(.dateTime.month(.abbreviated).day())
            let endText = sameMonth ? last.formatted(.dateTime.day()) : last.formatted(.dateTime.month(.abbreviated).day())
            return "\(startText) – \(endText)"
        }
    }

    /// "Today", "Yesterday", or "Mon, Nov 2".
    private func dayLabel(_ day: Date) -> String {
        if calendar.isDateInToday(day) { return "Today" }
        if calendar.isDateInYesterday(day) { return "Yesterday" }
        return day.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
    }
}

/// "Last done: Oct 12, Sep 14, Aug 10" for a repeating job's page.
struct RecentHistoryLine: View {
    @Query private var entries: [Completion]

    init(jobId: UUID) {
        let target: UUID? = jobId
        var descriptor = FetchDescriptor<Completion>(
            predicate: #Predicate { $0.jobId == target },
            sortBy: [SortDescriptor(\.completedAt, order: .reverse)]
        )
        descriptor.fetchLimit = 5
        _entries = Query(descriptor)
    }

    var body: some View {
        if !entries.isEmpty {
            let thisYear = Calendar.current.component(.year, from: .now)
            let dates = entries.map { entry in
                Calendar.current.component(.year, from: entry.completedAt) == thisYear
                    ? entry.completedAt.formatted(.dateTime.month(.abbreviated).day())
                    : entry.completedAt.formatted(.dateTime.month(.abbreviated).day().year())
            }
            Text("Last done: \(dates.joined(separator: ", "))")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}
