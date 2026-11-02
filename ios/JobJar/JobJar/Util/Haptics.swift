import UIKit

/// The two buzzes the app makes, same as Android: a solid bump when jobs come out of the jar, and
/// a crisp tick when something's done.
enum Haptics {
    static func draw() {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }

    static func done() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }
}
