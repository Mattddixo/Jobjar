import SwiftUI

struct InfoBadge: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption.weight(.medium))
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Capsule().fill(Color.accentColor.opacity(0.15)))
            .foregroundStyle(Color.accentColor)
    }
}

struct TimeBadge: View {
    let minutes: Int
    var body: some View { InfoBadge(text: formatMinutes(minutes)) }
}

struct CategoryBadge: View {
    let category: String
    var body: some View { InfoBadge(text: category) }
}
