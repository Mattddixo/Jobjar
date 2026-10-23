import SwiftUI

/// Pale grey-teal tag. `compact` = tighter padding, for the Jar cards.
struct InfoBadge: View {
    let text: String
    var compact: Bool = false
    var systemImage: String? = nil

    var body: some View {
        HStack(spacing: 3) {
            if let systemImage {
                Image(systemName: systemImage).font(.caption2)
            }
            Text(text)
        }
        .font(.caption.weight(.medium))
        .lineLimit(1)
        .padding(.horizontal, compact ? 7 : 10)
        .padding(.vertical, compact ? 2 : 4)
        .background(Capsule().fill(Palette.badge))
        .foregroundStyle(Palette.onBadge)
    }
}

struct TimeBadge: View {
    let minutes: Int
    var compact: Bool = false
    var body: some View { InfoBadge(text: formatMinutes(minutes), compact: compact) }
}

struct CategoryBadge: View {
    let category: String
    var compact: Bool = false
    var body: some View { InfoBadge(text: category, compact: compact) }
}

/// Teal "In progress" tag.
struct InProgressBadge: View {
    var compact: Bool = false

    var body: some View {
        Text("In progress")
            .font(.caption.weight(.semibold))
            .padding(.horizontal, compact ? 7 : 10)
            .padding(.vertical, compact ? 2 : 4)
            .background(Capsule().fill(Color.accentColor.opacity(0.14)))
            .foregroundStyle(Color.accentColor)
    }
}

/// "Scheduled Nov 4, 2:00 PM".
struct ScheduledBadge: View {
    let date: Date
    var compact: Bool = false

    var body: some View {
        InfoBadge(text: "Scheduled \(formatScheduledDateTime(date))", compact: compact, systemImage: "calendar")
    }
}
