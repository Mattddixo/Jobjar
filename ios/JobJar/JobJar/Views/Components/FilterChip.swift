import SwiftUI

/// A tappable pill, selected or not - the SwiftUI equivalent of Material 3's FilterChip, used
/// identically everywhere a single- or multi-choice filter/preset row shows up (time presets,
/// category filters, priority-adjacent pickers, the recurring/depends-on pickers).
struct FilterChip: View {
    let label: String
    let selected: Bool
    var leadingSystemImage: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if let leadingSystemImage {
                    Image(systemName: leadingSystemImage)
                        .font(.caption)
                }
                Text(label)
                    .font(.subheadline)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(
                Capsule().fill(selected ? Color.accentColor.opacity(0.18) : Color(.tertiarySystemFill))
            )
            .overlay(
                Capsule().strokeBorder(selected ? Color.accentColor : Color.clear, lineWidth: 1.5)
            )
            .foregroundStyle(selected ? Color.accentColor : Color.primary)
        }
        .buttonStyle(.plain)
    }
}
