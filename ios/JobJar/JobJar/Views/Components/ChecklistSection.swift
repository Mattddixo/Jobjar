import SwiftUI

/// A job's checklist on its page. With no items it's just a small "Add checklist" link; once
/// there are items it's a card that starts collapsed to its header ("Checklist · 2 of 5") and
/// opens to the full list of tick boxes when tapped. Changes go to `onChange` as an edit to apply to the saved list;
/// `onDelete` removes the whole list (the caller offers Undo).
struct ChecklistSection: View {
    let items: [ChecklistItem]
    let onChange: (([ChecklistItem]) -> [ChecklistItem]) -> Void
    let onDelete: () -> Void

    @State private var adding = false
    @State private var expanded = false
    @State private var editingIndex: Int? = nil
    @State private var editText = ""
    @State private var newText = ""
    @FocusState private var focus: Field?

    private enum Field: Hashable { case new, edit }

    var body: some View {
        if items.isEmpty && !adding {
            Button {
                expanded = true
                adding = true
            } label: {
                Label("Add checklist", systemImage: "plus")
                    .font(.subheadline)
            }
        } else {
            card
        }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                // The whole header opens and closes the list.
                Button {
                    if expanded {
                        commitEdit()
                        finishAdding()
                    }
                    withAnimation(.easeInOut(duration: 0.15)) { expanded.toggle() }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .rotationEffect(.degrees(expanded ? 90 : 0))
                            .foregroundStyle(.secondary)
                        Text(items.isEmpty ? "Checklist" : "Checklist · \(items.filter(\.checked).count) of \(items.count)")
                            .font(.subheadline.weight(.semibold))
                        Spacer()
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint(expanded ? "Hides the checklist" : "Shows the checklist")
                Menu {
                    if items.contains(where: \.checked) {
                        Button("Untick all") {
                            onChange { list in list.map { ChecklistItem(text: $0.text, checked: false) } }
                        }
                    }
                    Button("Delete checklist", role: .destructive) {
                        adding = false
                        editingIndex = nil
                        if !items.isEmpty { onDelete() }
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .frame(width: 32, height: 32)
                        .contentShape(Rectangle())
                }
                .foregroundStyle(.secondary)
                .accessibilityLabel("Checklist options")
            }

            if expanded {
                expandedContent
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardStyle(cornerRadius: 14)
        .onChange(of: focus) { old, new in
            // Tapping away saves whatever's being typed. Checked on the next turn of the run loop
            // because Return briefly drops focus before addNewItem puts it back.
            guard new == nil else { return }
            DispatchQueue.main.async {
                guard focus == nil else { return }
                if old == .edit { commitEdit() }
                if old == .new { finishAdding() }
            }
        }
    }

    @ViewBuilder
    private var expandedContent: some View {
        ForEach(Array(items.enumerated()), id: \.offset) { index, item in
            if editingIndex == index {
                editorRow
            } else {
                itemRow(item, index: index)
            }
        }

        if adding {
            TextField("Add item", text: $newText)
                .focused($focus, equals: .new)
                .submitLabel(.next)
                .onSubmit(addNewItem)
                .padding(.vertical, 8)
                .onAppear { DispatchQueue.main.async { focus = .new } }
        } else {
            Button {
                commitEdit()
                adding = true
            } label: {
                Label("Add item", systemImage: "plus")
                    .font(.subheadline)
            }
            .padding(.vertical, 6)
        }
    }

    private func itemRow(_ item: ChecklistItem, index: Int) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Button {
                onChange { list in
                    list.enumerated().map { i, entry in i == index ? ChecklistItem(text: entry.text, checked: !entry.checked) : entry }
                }
            } label: {
                Image(systemName: item.checked ? "checkmark.square.fill" : "square")
                    .foregroundStyle(item.checked ? Color.accentColor : Color.secondary)
                    .font(.title3)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(item.checked ? "Untick \(item.text)" : "Tick \(item.text)")

            Text(item.text)
                .strikethrough(item.checked)
                .foregroundStyle(item.checked ? .secondary : .primary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture {
                    commitEdit()
                    adding = false
                    editText = item.text
                    editingIndex = index
                    DispatchQueue.main.async { focus = .edit }
                }
        }
        .padding(.vertical, 6)
    }

    private var editorRow: some View {
        HStack {
            TextField("Item", text: $editText)
                .focused($focus, equals: .edit)
                .submitLabel(.done)
                .onSubmit(commitEdit)
            Button {
                removeEditedItem()
            } label: {
                Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Remove item")
        }
        .padding(.vertical, 8)
    }

    /// Return adds the item and stays open for the next one; Return on an empty line closes it.
    private func addNewItem() {
        let text = newText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            adding = false
            return
        }
        onChange { $0 + [ChecklistItem(text: text)] }
        newText = ""
        focus = .new
    }

    private func finishAdding() {
        guard adding else { return }
        let text = newText.trimmingCharacters(in: .whitespacesAndNewlines)
        if !text.isEmpty { onChange { $0 + [ChecklistItem(text: text)] } }
        newText = ""
        adding = false
    }

    /// Saves the line being edited. Clearing it removes it. Safe to call when nothing's being
    /// edited, so losing focus after Return doesn't save twice.
    private func commitEdit() {
        guard let index = editingIndex else { return }
        editingIndex = nil
        let text = editText.trimmingCharacters(in: .whitespacesAndNewlines)
        onChange { list in
            guard list.indices.contains(index) else { return list }
            var updated = list
            if text.isEmpty {
                updated.remove(at: index)
            } else {
                updated[index].text = text
            }
            return updated
        }
    }

    private func removeEditedItem() {
        guard let index = editingIndex else { return }
        editingIndex = nil
        onChange { list in
            guard list.indices.contains(index) else { return list }
            var updated = list
            updated.remove(at: index)
            return updated
        }
    }
}
