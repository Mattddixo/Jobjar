import UIKit

/// The app's one buzz, same as Android: a crisp tick when something's done.
enum Haptics {
    static func done() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }
}
