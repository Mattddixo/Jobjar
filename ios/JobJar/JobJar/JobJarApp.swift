import SwiftData
import SwiftUI
import UserNotifications

@main
struct JobJarApp: App {
    @AppStorage("themeMode") private var themeMode = ThemeMode.system.rawValue
    @State private var undoCenter = UndoCenter()

    init() {
        // Reminders show while the app is open, and tapping the nudge opens a draw.
        UNUserNotificationCenter.current().delegate = NotificationDelegate.shared
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(undoCenter)
                .preferredColorScheme(ThemeMode(rawValue: themeMode)?.colorScheme)
        }
        .modelContainer(for: [Job.self, Completion.self])
    }
}
