import SwiftData
import SwiftUI

/// App-wide "Undo" for completing and deleting jobs. Whichever screen made the change offers
/// the repository's snapshot here; the toast at the bottom of the app (UndoToast) shows it.
@Observable
@MainActor
final class UndoCenter {
    struct Offer: Identifiable, Equatable {
        let id = UUID()
        let message: String
        let snapshot: UndoSnapshot
    }

    private(set) var current: Offer?

    func offer(_ message: String, _ snapshot: UndoSnapshot) {
        withAnimation(.easeOut(duration: 0.2)) { current = Offer(message: message, snapshot: snapshot) }
    }

    func dismiss(_ offer: Offer) {
        guard current?.id == offer.id else { return }
        withAnimation(.easeIn(duration: 0.2)) { current = nil }
    }

    func offerCompleted(_ title: String, _ snapshot: UndoSnapshot) {
        offer("Completed “\(title)”", snapshot)
    }

    func offerDeleted(_ title: String, _ snapshot: UndoSnapshot) {
        offer("Deleted “\(title)”", snapshot)
    }
}

struct UndoToast: View {
    @Environment(UndoCenter.self) private var undoCenter
    @Environment(\.modelContext) private var modelContext

    var body: some View {
        if let offer = undoCenter.current {
            HStack(spacing: 12) {
                Text(offer.message)
                    .font(.subheadline)
                    .lineLimit(2)
                Spacer(minLength: 8)
                Button("Undo") {
                    JobRepository(modelContext: modelContext).undo(offer.snapshot)
                    undoCenter.dismiss(offer)
                }
                .font(.subheadline.weight(.semibold))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            .shadow(color: .black.opacity(0.12), radius: 8, y: 2)
            .padding(.horizontal, 16)
            .padding(.bottom, 58)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .task(id: offer.id) {
                try? await Task.sleep(for: .seconds(6))
                undoCenter.dismiss(offer)
            }
        }
    }
}
