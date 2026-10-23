import SwiftUI

/// The pill itself, for FilterChip and for chips that open a menu.
struct ChipLabel: View {
    let label: String
    let selected: Bool
    var leadingSystemImage: String? = nil
    var showsMenuArrow: Bool = false

    var body: some View {
        HStack(spacing: 4) {
            if let leadingSystemImage {
                Image(systemName: leadingSystemImage)
                    .font(.caption)
            }
            Text(label)
                .font(.subheadline)
                .lineLimit(1)
            if showsMenuArrow {
                Image(systemName: "chevron.down")
                    .font(.caption2.weight(.semibold))
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Capsule().fill(selected ? Color.accentColor.opacity(0.14) : Palette.card))
        .overlay(Capsule().strokeBorder(selected ? Color.accentColor : Palette.cardBorder, lineWidth: selected ? 1.5 : 1))
        .foregroundStyle(selected ? Color.accentColor : Color.primary)
        .contentShape(Capsule())
    }
}

/// Tappable pill, selected or not. Like Material's FilterChip.
struct FilterChip: View {
    let label: String
    let selected: Bool
    var leadingSystemImage: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ChipLabel(label: label, selected: selected, leadingSystemImage: leadingSystemImage)
        }
        .buttonStyle(.plain)
    }
}

/// A chip that opens a menu.
struct MenuChip<Content: View>: View {
    let label: String
    let selected: Bool
    /// Leave the menu open after each pick, for multi-select menus.
    var staysOpen: Bool = false
    @ViewBuilder let content: () -> Content

    var body: some View {
        Menu {
            content()
        } label: {
            ChipLabel(label: label, selected: selected, showsMenuArrow: true)
        }
        .menuActionDismissBehavior(staysOpen ? .disabled : .automatic)
        .buttonStyle(.plain)
    }
}

/// Menu item with a tick when it's on.
struct CheckedMenuItem: View {
    let title: String
    let checked: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            if checked {
                Label(title, systemImage: "checkmark")
            } else {
                Text(title)
            }
        }
    }
}
